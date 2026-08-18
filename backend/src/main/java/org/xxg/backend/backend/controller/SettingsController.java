package org.xxg.backend.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.dto.LoginResponse;
import org.xxg.backend.backend.service.EmailService;
import org.xxg.backend.backend.service.SettingsService;

import java.util.Map;

/**
 * 系统设置控制器
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsService settingsService;
    private final EmailService emailService;

    public SettingsController(SettingsService settingsService, EmailService emailService) {
        this.settingsService = settingsService;
        this.emailService = emailService;
    }

    @GetMapping("/public")
    public LoginResponse getPublicSettings() {
        try {
            return LoginResponse.success("Success", settingsService.getPublicSettings());
        } catch (Exception e) {
            logger.error("Failed to load public settings", e);
            return LoginResponse.error("Failed to load public settings");
        }
    }

    /**
     * 获取所有设置
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse getAllSettings() {
        try {
            return LoginResponse.success("Success", settingsService.getAllSettings());
        } catch (Exception e) {
            logger.error("Failed to load settings", e);
            return LoginResponse.error("Failed to load settings");
        }
    }

    /**
     * 批量保存设置
     */
    @PostMapping("/save")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse saveSettings(@RequestBody Map<String, String> settings) {
        try {
            settingsService.saveSettings(settings);
            return LoginResponse.success("Settings saved successfully", null);
        } catch (Exception e) {
            logger.error("Failed to save settings", e);
            return LoginResponse.error("Failed to save settings");
        }
    }

    /**
     * 发送测试邮件
     */
    @PostMapping("/email/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> sendTestEmail(@RequestBody Map<String, String> request) {
        String to = request.get("to");
        if (to == null || to.isEmpty()) {
            return ResponseEntity.badRequest().body("Recipient email is required");
        }

        Map<String, String> configOverrides = new java.util.HashMap<>(request);
        configOverrides.remove("to");

        try {
            emailService.sendTestEmail(to, configOverrides);
            return ResponseEntity.ok("Test email sent successfully");
        } catch (Exception e) {
            logger.error("Failed to send test email", e);
            return ResponseEntity.internalServerError().body("Failed to send test email");
        }
    }
}
