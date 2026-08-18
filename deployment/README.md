# Ubuntu 22.04 IP 一键部署

项目提供 `deployment/one-click-ip.sh`，适用于 Ubuntu 22.04，使用服务器 IP 访问，不需要域名或 HTTPS。

## 一键部署

你的 GitHub 仓库已公开，因此服务器可以直接拉取代码。先登录服务器：

```bash
ssh root@服务器公网IP
```

下载脚本并执行：

```bash
apt update && apt install -y curl
curl -fsSL https://raw.githubusercontent.com/a1159645714/KM/master/deployment/one-click-ip.sh -o /root/one-click-ip.sh
chmod +x /root/one-click-ip.sh
bash /root/one-click-ip.sh
```

脚本会自动完成：

- 安装 Git、Nginx、MySQL、Redis、JDK 21、Maven、Node.js 20
- 从 `https://github.com/a1159645714/KM.git` 的 `master` 分支拉取代码
- 创建 MySQL 数据库和独立应用账号
- 导入 `databaes/kami.sql`，失败时尝试 `kami_mysql56.sql`
- 生成 JWT 密钥和数据库密码
- 创建 `backend/keys/`，由应用首次启动生成缺失的加密密钥
- 编译 Vue 前端和 Spring Boot 后端
- 创建 systemd 服务 `xxgkami.service`
- 配置 Nginx 将 `/api` 反向代理到 `127.0.0.1:8080`
- 通过服务器 IP 输出访问地址

部署完成后访问：

```text
用户端：http://服务器公网IP/
管理端：http://服务器公网IP/#/admin
```

## 可选环境变量

默认值可以通过环境变量覆盖：

```bash
SERVER_IP=1.2.3.4 \
ADMIN_PASSWORD='请使用至少10位强密码' \
DB_PASSWORD='数据库强密码' \
JWT_SECRET='至少32位随机字符串' \
bash /root/one-click-ip.sh
```

`ADMIN_PASSWORD` 只会被脚本读取并写入部署记录；不要把密码写进公开脚本或 Git 仓库。

## 部署后检查

```bash
systemctl status xxgkami
journalctl -u xxgkami -f
nginx -t
curl http://服务器公网IP/api/maintenance/status
```

防火墙/安全组只开放：

- `22`：SSH
- `80`：HTTP

不要开放：

- `3306`：MySQL
- `6379`：Redis
- `8080`：Spring Boot

## 重要说明

- 该方案使用 HTTP + IP，没有 HTTPS，适合测试或内网场景；公网生产环境传输的登录信息和 Token 可能被窃听。
- 公开仓库可以被任何人读取。仓库中不应放入 `backend/keys/`、数据库备份、生产 `.env`、支付密钥或 JWT 密钥。
- 应用首次启动会生成 `backend/keys/` 下的 AES/ECC/Pepper 文件。请做好备份；更换这些密钥可能导致历史高级卡密无法解密或验签。
- 脚本默认以 root 运行，并将后端 systemd 服务以 root 运行以兼容项目当前的文件路径逻辑；正式生产环境建议后续改为专用低权限用户。
- 该脚本暂未自动配置 HTTPS，因为你明确选择只用 IP 且没有域名。
