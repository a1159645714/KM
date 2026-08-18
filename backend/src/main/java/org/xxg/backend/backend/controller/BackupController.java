package org.xxg.backend.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.xxg.backend.backend.service.BackupService;

import java.util.Map;

@RestController
@RequestMapping("/backup")
public class BackupController {

    @Autowired
    private BackupService backupService;

    @PostMapping("/create")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createBackup() {
        try {
            String backupPath = backupService.createBackup();
            return ResponseEntity.ok(Map.of("success", true, "message", "Backup created successfully", "path", backupPath));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Backup failed: " + e.getMessage()));
        }
    }
}
