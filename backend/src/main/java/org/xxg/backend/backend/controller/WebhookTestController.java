package org.xxg.backend.backend.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class WebhookTestController {

    @GetMapping("/callback")
    public String handleGetCallback(@RequestParam Map<String, String> params) {
        return "Webhook GET callback received successfully.";
    }

    @PostMapping("/callback")
    public String handlePostCallback(@RequestBody(required = false) Map<String, Object> body, @RequestParam Map<String, String> params) {
        return "Webhook POST callback received successfully.";
    }
}
