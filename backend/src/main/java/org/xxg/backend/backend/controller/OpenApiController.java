package org.xxg.backend.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.entity.ApiKey;
import org.xxg.backend.backend.service.ApiKeyService;
import org.xxg.backend.backend.service.CardService;
import org.xxg.backend.backend.service.KeyManagerService;
import org.xxg.backend.backend.util.AdvancedCryptoUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@CrossOrigin
public class OpenApiController {

    private static final Logger log = LoggerFactory.getLogger(OpenApiController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiKeyService apiKeyService;
    private final CardService cardService;
    private final org.xxg.backend.backend.util.CustomCardObfuscator customCardObfuscator;
    private final KeyManagerService keyManagerService;
    private final AdvancedCryptoUtil advancedCryptoUtil;

    public OpenApiController(
            ApiKeyService apiKeyService,
            CardService cardService,
            org.xxg.backend.backend.util.CustomCardObfuscator customCardObfuscator,
            KeyManagerService keyManagerService,
            AdvancedCryptoUtil advancedCryptoUtil) {
        this.apiKeyService = apiKeyService;
        this.cardService = cardService;
        this.customCardObfuscator = customCardObfuscator;
        this.keyManagerService = keyManagerService;
        this.advancedCryptoUtil = advancedCryptoUtil;
    }

    /**
     * 处理卡密使用逻辑
     */
    private ResponseEntity<Map<String, Object>> executeUseCard(
            String apiKey, String cardKey, String deviceId, String ipAddress, String machineCode, HttpServletRequest request
    ) {
        // Default IP if not provided
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = request.getRemoteAddr();
        }

        Map<String, Object> response = new HashMap<>();

        // 1. Validate Parameters
        if (apiKey == null || apiKey.isEmpty()) {
            response.put("code", 401);
            response.put("message", "API Key is required");
            response.put("success", false);
            return ResponseEntity.status(401).body(response);
        }

        if (cardKey == null || cardKey.isEmpty()) {
            response.put("code", 400);
            response.put("message", "Card Key is required");
            response.put("success", false);
            return ResponseEntity.badRequest().body(response);
        }

        // 2. Validate API Key
        ApiKey keyEntity = apiKeyService.getByApiKey(apiKey);
        if (keyEntity == null) {
            response.put("code", 403);
            response.put("message", "Invalid API Key");
            response.put("success", false);
            return ResponseEntity.status(403).body(response);
        }

        if (keyEntity.getStatus() != 1) {
            response.put("code", 403);
            response.put("message", "API Key is disabled");
            response.put("success", false);
            return ResponseEntity.status(403).body(response);
        }

        // Check encryption
        if (Boolean.TRUE.equals(keyEntity.getEnableCardEncryption())) {
            try {
                String decryptedKey = customCardObfuscator.deobfuscate(cardKey);
                if (decryptedKey == null) {
                    throw new RuntimeException("Decryption failed");
                }
                cardKey = decryptedKey;
            } catch (Exception e) {
                response.put("code", 400);
                response.put("message", "卡密格式错误或解密失败(Encrypted Card Key Required)");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
        }

        // 3. Use Card
        try {
            cardService.useCard(cardKey, deviceId, ipAddress, keyEntity.getId(), machineCode);
            
            // 4. Update API Key Usage
            apiKeyService.updateUsage(keyEntity.getId());

            response.put("code", 200);
            response.put("message", "Card used successfully");
            response.put("success", true);
            attachSignature(response, cardKey, machineCode);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("code", 400);
            response.put("message", e.getMessage());
            response.put("success", false);
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 对核销成功响应附加 ECC 签名,供客户端校验,防止本地伪造成功响应。
     * 签名载荷字段顺序固定(success, machine_code, card_sha256, timestamp),
     * 使用 LinkedHashMap + 单例 ObjectMapper 保证序列化结果稳定。
     */
    private void attachSignature(Map<String, Object> response, String cardKey, String machineCode) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("machine_code", machineCode == null ? "" : machineCode);
            payload.put("card_sha256", sha256Hex(cardKey));
            payload.put("timestamp", System.currentTimeMillis() / 1000);
            String canonical = MAPPER.writeValueAsString(payload);
            String signature = advancedCryptoUtil.sign(
                    canonical,
                    keyManagerService.getEccKeyPair().getPrivate()
            );
            Map<String, Object> sign = new LinkedHashMap<>();
            sign.put("payload", canonical);
            sign.put("signature", signature);
            response.put("sign", sign);
        } catch (Exception e) {
            // 核销已成功,签名失败只影响新版客户端校验;只记一条 warning,不打裸堆栈
            log.warn("attachSignature failed: {}", e.getMessage());
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * JSON POST Request
     */
    @PostMapping(value = "/use_card", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> useCardJson(
            HttpServletRequest request,
            @RequestBody Map<String, String> jsonBody
    ) {
        return executeUseCard(
            jsonBody.get("api_key"),
            jsonBody.get("card_key"),
            jsonBody.get("device_id"),
            jsonBody.get("ip_address"),
            jsonBody.get("machine_code"),
            request
        );
    }

    /**
     * GET Request or Form POST Request
     */
    @RequestMapping(value = "/use_card")
    public ResponseEntity<Map<String, Object>> useCard(
            HttpServletRequest request,
            @RequestParam(value = "api_key", required = false) String apiKey,
            @RequestParam(value = "card_key", required = false) String cardKey,
            @RequestParam(value = "device_id", required = false) String deviceId,
            @RequestParam(value = "ip_address", required = false) String ipAddress,
            @RequestParam(value = "machine_code", required = false) String machineCode
    ) {
        return executeUseCard(apiKey, cardKey, deviceId, ipAddress, machineCode, request);
    }
}
