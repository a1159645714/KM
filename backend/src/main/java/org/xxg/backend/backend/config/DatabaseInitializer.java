package org.xxg.backend.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;

@Configuration
public class DatabaseInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Autowired
    private DataSource dataSource;

    @PostConstruct
    public void init() {
        try {
            ResourceDatabasePopulator resourceDatabasePopulator = new ResourceDatabasePopulator(false, false, "UTF-8", new ClassPathResource("schema-advanced.sql"));
            resourceDatabasePopulator.setContinueOnError(true);
            resourceDatabasePopulator.execute(dataSource);
        } catch (Exception e) {
            logger.error("Failed to initialize advanced schema", e);
        }
        
        // Force update columns to ensure length is sufficient (in case schema.sql didn't run or was old)
        try {
            java.sql.Connection conn = dataSource.getConnection();
            java.sql.Statement stmt = conn.createStatement();
            try {
                stmt.execute("ALTER TABLE cards MODIFY COLUMN card_key VARCHAR(512)");
                stmt.execute("ALTER TABLE cards MODIFY COLUMN encrypted_key VARCHAR(255)");
                stmt.execute("ALTER TABLE cards MODIFY COLUMN encryption_type VARCHAR(50)");
                logger.info("Updated cards table columns.");
            } catch (Exception e) {
                logger.warn("Column update failed; it may already be applied.", e);
            } finally {
                stmt.close();
                conn.close();
            }
        } catch (Exception e) {
            logger.error("Database initialization failed", e);
        }

        // Migrate plaintext passwords in admins table
        try {
            java.sql.Connection conn = dataSource.getConnection();
            java.sql.Statement stmt = conn.createStatement();
            java.sql.ResultSet rs = stmt.executeQuery("SELECT id, password FROM admins");
            
            org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            // 标准 bcrypt 哈希格式：$2a$/$2b$/$2y$ + 两位 cost + 53 位盐哈希。
            // 旧逻辑只认 $2a$/$2y$，会把 install.sh 写入的 $2b$ 哈希误判为明文再哈希一次，
            // 导致「重启后原本正确的管理员密码失效」。
            java.util.regex.Pattern bcryptPattern = java.util.regex.Pattern.compile("\\A\\$2[aby]\\$\\d{2}\\$[./0-9A-Za-z]{53}\\z");
            
            while (rs.next()) {
                long id = rs.getLong("id");
                String pwd = rs.getString("password");
                
                boolean isPlain = pwd == null || !bcryptPattern.matcher(pwd).matches();
                
                if (isPlain && pwd != null && !pwd.isEmpty()) {
                    String newHash = encoder.encode(pwd);
                    java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE admins SET password = ? WHERE id = ?");
                    ps.setString(1, newHash);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                    ps.close();
                    logger.info("Migrated legacy plaintext administrator password for id {}", id);
                }
            }
            rs.close();
            stmt.close();
            conn.close();
        } catch (Exception e) {
             logger.error("Password migration check failed", e);
        }
    }
}
