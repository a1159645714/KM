#!/usr/bin/env bash
set -Eeuo pipefail

# One-click Ubuntu deployment for KM using the server IP (no domain required).
# Run as root: bash one-click-ip.sh

APP_NAME="xxgkami"
APP_ROOT="${APP_ROOT:-/opt/xxgkami}"
WEB_ROOT="${WEB_ROOT:-/var/www/xxgkami}"
SERVICE_NAME="xxgkami.service"
REPO_URL="${REPO_URL:-https://github.com/a1159645714/KM.git}"
BRANCH="${BRANCH:-master}"
DB_NAME="kami"
DB_USER="kami_app"
ENV_DIR="/etc/xxgkami"
ENV_FILE="${ENV_DIR}/backend.env"
NGINX_SITE="/etc/nginx/sites-available/${APP_NAME}"

log() { printf '\n[KM] %s\n' "$*"; }
fail() { printf '\n[KM][ERROR] %s\n' "$*" >&2; exit 1; }
trap 'fail "Deployment failed at line ${LINENO}. Check the output above."' ERR

[[ "$(id -u)" == "0" ]] || fail "Please run as root."
source /etc/os-release
[[ "${ID:-}" == "ubuntu" ]] || fail "This script supports Ubuntu only."

read_secret() {
    local prompt="$1" value
    read -r -s -p "$prompt" value
    printf '\n' >&2
    printf '%s' "$value"
}

random_secret() {
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 64
}

get_server_ip() {
    local ip
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [[ -n "$ip" ]] || ip="$(curl -4fsS --max-time 5 https://api.ipify.org || true)"
    [[ -n "$ip" ]] || fail "Could not determine server IP. Set SERVER_IP before running."
    printf '%s' "$ip"
}

SERVER_IP="${SERVER_IP:-$(get_server_ip)}"

log "Installing system packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl git nginx mysql-server redis-server openssl unzip apache2-utils

if ! command -v java >/dev/null 2>&1; then
    log "Installing Java 21"
    apt-get install -y openjdk-21-jdk
fi
if ! command -v mvn >/dev/null 2>&1; then
    apt-get install -y maven
fi
if ! command -v node >/dev/null 2>&1 || [[ "$(node -p 'parseInt(process.versions.node, 10)')" -lt 18 ]]; then
    log "Installing Node.js 20"
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
fi

systemctl enable --now mysql nginx redis-server

log "Collecting deployment settings"
DB_PASSWORD="${DB_PASSWORD:-$(random_secret)}"
JWT_SECRET="${JWT_SECRET:-$(random_secret)}"
CORS_ALLOWED_ORIGINS="http://${SERVER_IP}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(read_secret 'Set a new admin password: ')}"
[[ "${#ADMIN_PASSWORD}" -ge 10 ]] || fail "Admin password must be at least 10 characters."
export ADMIN_PASSWORD
[[ "${#JWT_SECRET}" -ge 32 ]] || fail "JWT_SECRET must be at least 32 characters."

# The repository is public, so no GitHub token is required.
# For a private fork, set GITHUB_TOKEN before running this script.
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

log "Preparing application source"
mkdir -p "$(dirname "$APP_ROOT")"
if [[ -d "${APP_ROOT}/.git" ]]; then
    git -C "$APP_ROOT" fetch origin "$BRANCH"
    git -C "$APP_ROOT" reset --hard "origin/${BRANCH}"
else
    rm -rf "$APP_ROOT"
    if [[ -n "$GITHUB_TOKEN" ]]; then
        ASKPASS="$(mktemp)"
        cat > "$ASKPASS" <<'EOF'
#!/usr/bin/env sh
case "$1" in
  *Username*) printf '%s' "x-access-token" ;;
  *Password*) printf '%s' "${GITHUB_TOKEN:-}" ;;
esac
EOF
        chmod 700 "$ASKPASS"
        GITHUB_TOKEN="$GITHUB_TOKEN" GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
            git clone --branch "$BRANCH" "$REPO_URL" "$APP_ROOT"
        rm -f "$ASKPASS"
    else
        git clone --branch "$BRANCH" "$REPO_URL" "$APP_ROOT"
    fi
fi

[[ -f "${APP_ROOT}/package.json" ]] || fail "package.json not found in ${APP_ROOT}."
[[ -f "${APP_ROOT}/backend/pom.xml" ]] || fail "backend/pom.xml not found in ${APP_ROOT}."

log "Initializing database"
mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL

if [[ -f "${APP_ROOT}/databaes/kami.sql" ]]; then
    log "Importing database seed"
    mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "$DB_NAME" < "${APP_ROOT}/databaes/kami.sql" || {
        log "MySQL 8 seed failed; trying MySQL 5.6-compatible seed"
        mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "$DB_NAME" < "${APP_ROOT}/databaes/kami_mysql56.sql"
    }
else
    log "No seed SQL found; the application initializer will create advanced tables."
fi

log "Setting the administrator password"
ADMIN_HASH="$(htpasswd -bnBC 12 '' "$ADMIN_PASSWORD" | tr -d '\r\n' | sed 's/^:\$2y\$/\$2a\$/')"
mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "$DB_NAME" -e \
    "UPDATE admins SET password='${ADMIN_HASH}', access_token=NULL, refresh_token=NULL WHERE username='admin';"

log "Creating application environment"
install -d -m 700 "$ENV_DIR"
cat > "$ENV_FILE" <<EOF
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS}
JWT_ACCESS_EXPIRATION_MS=3600000
JWT_REFRESH_EXPIRATION_MS=604800000
EOF
chmod 600 "$ENV_FILE"

log "Creating application key directory"
install -d -m 700 "${APP_ROOT}/backend/keys"
# KeyManagerService generates keys on first start. Existing keys are never overwritten.

log "Building frontend"
cd "$APP_ROOT"
printf 'VITE_API_BASE_URL=/api\n' > .env.production
npm install --no-audit --no-fund
npm run build
rm -f .env.production
install -d -m 755 "$WEB_ROOT"
rm -rf "${WEB_ROOT:?}"/*
cp -a "$APP_ROOT/dist/." "$WEB_ROOT/"

log "Building backend"
cd "$APP_ROOT/backend"
./mvnw clean package -DskipTests 2>/dev/null || mvn clean package -DskipTests
JAR_PATH="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
[[ -n "$JAR_PATH" ]] || fail "Backend JAR was not created."
install -d -m 755 "${APP_ROOT}/backend/logs" "${APP_ROOT}/backend/backups"

log "Creating systemd service"
cat > "/etc/systemd/system/${SERVICE_NAME}" <<EOF
[Unit]
Description=KM Spring Boot backend
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
Type=simple
User=root
WorkingDirectory=${APP_ROOT}/backend
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/bin/java -jar ${APP_ROOT}/backend/${JAR_PATH#target/}
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
cp "$JAR_PATH" "${APP_ROOT}/backend/${JAR_PATH#target/}"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

log "Configuring Nginx for IP ${SERVER_IP}"
cat > "$NGINX_SITE" <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    root ${WEB_ROOT};
    index index.html;

    location / {
        try_files \\$uri \\$uri/ /index.html;
    }

    location /api {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host \\$host;
        proxy_set_header X-Real-IP \\$remote_addr;
        proxy_set_header X-Forwarded-For \\$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \\$scheme;
        proxy_read_timeout 60s;
    }
}
EOF
ln -sfn "$NGINX_SITE" "/etc/nginx/sites-enabled/${APP_NAME}"
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx
systemctl restart "$SERVICE_NAME"
sleep 5

log "Checking services"
systemctl --no-pager --full status "$SERVICE_NAME" | tail -n 20
curl -fsS --max-time 10 "http://${SERVER_IP}/api/maintenance/status" >/dev/null

cat <<EOF

Deployment complete.
Frontend: http://${SERVER_IP}/
Admin:    http://${SERVER_IP}/#/admin
API:      http://${SERVER_IP}/api/
Service:  systemctl status ${SERVICE_NAME}
Logs:     journalctl -u ${SERVICE_NAME} -f
Env:      ${ENV_FILE}

Important: this deployment uses HTTP and an IP address only. Do not expose ports 3306,
6379, or 8080 in the firewall. Change the generated database/admin credentials after
confirming the service works, and keep ${ENV_FILE} private.
EOF
