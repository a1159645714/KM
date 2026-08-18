package org.xxg.backend.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.entity.ApiKey;
import org.xxg.backend.backend.entity.Card;
import org.xxg.backend.backend.entity.User;
import org.xxg.backend.backend.mapper.AdminMapper;
import org.xxg.backend.backend.service.ApiKeyService;
import org.xxg.backend.backend.service.CardService;
import org.xxg.backend.backend.service.UserService;
import org.xxg.backend.backend.util.CustomCardObfuscator;

import java.util.List;
import java.util.Map;

/**
 * 卡密控制器
 */
@RestController
@RequestMapping("/cards")
public class CardController {

    @Autowired
    private CardService cardService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private CustomCardObfuscator customCardObfuscator;

    @Autowired
    private UserService userService;

    @Autowired
    private AdminMapper adminMapper;

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            throw new RuntimeException("未登录");
        }
        return authentication;
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private User requireCurrentUser() {
        Authentication authentication = requireAuthentication();
        if (isAdmin(authentication)) {
            throw new RuntimeException("管理员账户不能访问用户卡密接口");
        }
        User user = userService.getUserByUsername(authentication.getName());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    /**
     * 获取用户的卡密
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getUserCards(@PathVariable Long userId) {
        try {
            User currentUser = requireCurrentUser();
            if (!currentUser.getId().equals(userId)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "无权查看其他用户卡密"));
            }
            List<Card> cards = cardService.getUserCards(userId);
            return ResponseEntity.ok(Map.of("success", true, "data", cards));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 管理员批量创建卡密
     */
    @PostMapping("/admin/create")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createCards(@RequestBody CreateCardRequest request) {
        try {
            Authentication authentication = requireAuthentication();
            String adminName = authentication.getName();
            var admin = adminMapper.findByUsername(adminName);
            if (admin == null) {
                throw new RuntimeException("管理员不存在");
            }

            List<Card> cards = cardService.createCards(
                    request.getCount(),
                    request.getCardType(),
                    request.getDuration(),
                    request.getTotalCount(),
                    request.getVerifyMethod(),
                    request.getEncryptionType(),
                    request.getAllowReverify(),
                    "admin",
                    admin.getId(),
                    adminName,
                    request.getApiKeyId(),
                    Boolean.TRUE.equals(request.getStackTimeIfSameMachine()),
                    Boolean.TRUE.equals(request.getAllowSelfUnbind())
            );
            return ResponseEntity.ok(Map.of("success", true, "data", cards));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 管理员获取所有卡密
     */
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAllCards() {
        try {
            List<Card> cards = cardService.getAllCards();
            return ResponseEntity.ok(Map.of("success", true, "data", cards));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/apikey/{apiKeyId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getApiKeyCards(@PathVariable Long apiKeyId) {
        try {
            List<Card> cards = cardService.getCardsByApiKey(apiKeyId);
            return ResponseEntity.ok(Map.of("success", true, "data", cards));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 管理员编辑卡密（含机器码重置）
     */
    @PutMapping("/admin/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateCard(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            cardService.adminUpdateCard(id, body);
            return ResponseEntity.ok(Map.of("success", true, "message", "卡密更新成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 管理员更新卡密启用/暂停状态（status: 2=暂停，1=恢复启用）
     */
    @PatchMapping("/admin/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateAdminCardStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> body) {
        try {
            Integer status = body != null ? body.get("status") : null;
            if (status == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少 status"));
            }
            String msg = cardService.updateAdminCardStatus(id, status);
            return ResponseEntity.ok(Map.of("success", true, "message", msg));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 删除卡密
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteCard(@PathVariable Long id) {
        try {
            cardService.deleteCard(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "卡密删除成功"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 使用卡密
     */
    @RequestMapping(value = "/use", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Map<String, Object>> useCard(
            @RequestParam(required = false) Map<String, String> requestParams,
            @RequestBody(required = false) Map<String, String> requestBody,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        Map<String, String> params = requestParams != null ? requestParams : new java.util.HashMap<>();
        if (requestBody != null) {
            params.putAll(requestBody);
        }

        String cardKey = params.get("card_key");
        String deviceId = params.get("device_id");
        String apiKeyStr = params.get("api_key");
        String machineCode = params.get("machine_code");

        String ipAddress = params.get("ip_address");
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = httpRequest.getRemoteAddr();
        }

        if (cardKey == null || cardKey.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Card key is required"));
        }

        try {
            Long apiKeyId = null;
            if (apiKeyStr != null && !apiKeyStr.isEmpty()) {
                ApiKey apiKey = apiKeyService.getByApiKey(apiKeyStr);
                if (apiKey == null) {
                    return ResponseEntity.status(403).body(Map.of("success", false, "message", "Invalid API Key"));
                }
                if (apiKey.getStatus() != 1) {
                    return ResponseEntity.status(403).body(Map.of("success", false, "message", "API Key is disabled"));
                }
                apiKeyId = apiKey.getId();

                if (Boolean.TRUE.equals(apiKey.getEnableCardEncryption())) {
                    try {
                        String decryptedKey = customCardObfuscator.deobfuscate(cardKey);
                        if (decryptedKey == null) {
                            throw new RuntimeException("Decryption failed");
                        }
                        cardKey = decryptedKey;
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "卡密格式错误或解密失败(Encrypted Card Key Required)"));
                    }
                }

                apiKeyService.updateUsage(apiKeyId);
            }

            cardService.useCard(cardKey, deviceId, ipAddress, apiKeyId, machineCode);
            return ResponseEntity.ok(Map.of("success", true, "message", "Card used successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 获取卡密使用趋势
     */
    @GetMapping("/trend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getUsageTrend(@RequestParam(defaultValue = "7") int days) {
        try {
            Map<String, Object> trend = cardService.getUsageTrend(days);
            return ResponseEntity.ok(trend);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 创建卡密请求对象
     */
    public static class CreateCardRequest {
        private int count;

        @JsonProperty("card_type")
        private String cardType;

        private int duration;

        @JsonProperty("total_count")
        private int totalCount;

        @JsonProperty("verify_method")
        private String verifyMethod;

        @JsonProperty("encryption_type")
        private String encryptionType;

        @JsonProperty("allow_reverify")
        private int allowReverify;

        @JsonProperty("api_key_id")
        private Long apiKeyId;

        @JsonProperty("stack_time_if_same_machine")
        private Boolean stackTimeIfSameMachine;

        @JsonProperty("allow_self_unbind")
        private Boolean allowSelfUnbind;

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public String getCardType() { return cardType; }
        public void setCardType(String cardType) { this.cardType = cardType; }
        public int getDuration() { return duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public int getTotalCount() { return totalCount; }
        public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
        public String getVerifyMethod() { return verifyMethod; }
        public void setVerifyMethod(String verifyMethod) { this.verifyMethod = verifyMethod; }
        public String getEncryptionType() { return encryptionType; }
        public void setEncryptionType(String encryptionType) { this.encryptionType = encryptionType; }
        public int getAllowReverify() { return allowReverify; }
        public void setAllowReverify(int allowReverify) { this.allowReverify = allowReverify; }
        public Long getApiKeyId() { return apiKeyId; }
        public void setApiKeyId(Long apiKeyId) { this.apiKeyId = apiKeyId; }
        public Boolean getStackTimeIfSameMachine() { return stackTimeIfSameMachine; }
        public void setStackTimeIfSameMachine(Boolean stackTimeIfSameMachine) { this.stackTimeIfSameMachine = stackTimeIfSameMachine; }
        public Boolean getAllowSelfUnbind() { return allowSelfUnbind; }
        public void setAllowSelfUnbind(Boolean allowSelfUnbind) { this.allowSelfUnbind = allowSelfUnbind; }
    }
}
