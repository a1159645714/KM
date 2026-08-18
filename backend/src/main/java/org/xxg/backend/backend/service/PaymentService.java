package org.xxg.backend.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.xxg.backend.backend.entity.Order;
import org.xxg.backend.backend.mapper.OrderMapper;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final SettingsService settingsService;
    private final OrderService orderService;
    private final OrderMapper orderMapper;

    public PaymentService(SettingsService settingsService, OrderService orderService, OrderMapper orderMapper) {
        this.settingsService = settingsService;
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    public boolean isPaymentEnabled() {
        return "true".equals(settingsService.getSetting("payment_enabled"));
    }

    public String createPaymentUrl(Order order, String paymentType) {
        String apiUrl = settingsService.getSetting("epay_api_url");
        String pid = settingsService.getSetting("epay_pid");
        String key = settingsService.getSetting("epay_key");

        if (apiUrl == null || pid == null || key == null) {
            throw new RuntimeException("支付配置未完善");
        }

        if (!apiUrl.endsWith("/")) {
            apiUrl += "/";
        }

        String submitUrl = apiUrl + "submit.php";

        Map<String, String> params = new HashMap<>();
        params.put("pid", pid);
        params.put("type", paymentType);
        params.put("out_trade_no", order.getOrderNo());

        String notifyUrl = settingsService.getSetting("epay_notify_url");
        String returnUrl = settingsService.getSetting("epay_return_url");
        String siteUrl = settingsService.getSetting("site_url");

        if (notifyUrl == null || notifyUrl.trim().isEmpty()) {
            if (siteUrl != null && !siteUrl.trim().isEmpty()) {
                if (siteUrl.endsWith("/")) siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
                notifyUrl = siteUrl + "/api/payment/notify";
            } else {
                String host = "127.0.0.1";
                try {
                    host = java.net.InetAddress.getLocalHost().getHostAddress();
                } catch (Exception ignored) {
                }
                notifyUrl = "http://" + host + ":8080/api/payment/notify";
            }
        }

        if (returnUrl == null || returnUrl.trim().isEmpty()) {
            if (siteUrl != null && !siteUrl.trim().isEmpty()) {
                if (siteUrl.endsWith("/")) siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
                returnUrl = siteUrl + "/api/payment/return";
            } else {
                String host = "127.0.0.1";
                try {
                    host = java.net.InetAddress.getLocalHost().getHostAddress();
                } catch (Exception ignored) {
                }
                returnUrl = "http://" + host + ":8080/api/payment/return";
            }
        }

        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", order.getCardSpec());
        params.put("money", order.getTotalPrice().toString());
        params.put("clientip", "127.0.0.1");
        params.put("device", "pc");
        params.put("sign_type", "MD5");

        String sign = generateSign(params, key);
        params.put("sign", sign);

        String queryString = params.entrySet().stream()
                .map(e -> {
                    try {
                        return e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8.toString());
                    } catch (Exception ex) {
                        return e.getKey() + "=" + e.getValue();
                    }
                })
                .collect(Collectors.joining("&"));

        return submitUrl + "?" + queryString;
    }

    public boolean verifySign(Map<String, String> params) {
        String key = settingsService.getSetting("epay_key");
        if (key == null) return false;

        String sign = params.get("sign");
        if (sign == null) return false;

        Map<String, String> signParams = new HashMap<>(params);
        signParams.remove("sign");
        signParams.remove("sign_type");

        String calculatedSign = generateSign(signParams, key);
        return calculatedSign.equals(sign);
    }

    private String generateSign(Map<String, String> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>(params);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();

            if ("sign".equals(k) || "sign_type".equals(k) || v == null || v.isEmpty()) {
                continue;
            }

            sb.append(k).append("=").append(v).append("&");
        }

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }

        sb.append(key);
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8)).toLowerCase();
    }

    public String handleNotify(Map<String, String> params) {
        if (!verifySign(params)) {
            logger.warn("Payment signature verification failed for order {}", params.get("out_trade_no"));
            return "fail";
        }

        String status = params.get("trade_status");
        String orderNo = params.get("out_trade_no");
        String merchantId = params.get("pid");
        String paidAmount = params.get("money");

        if (orderNo == null || orderNo.isBlank()) {
            return "fail";
        }

        Order order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            logger.warn("Payment notify references unknown order {}", orderNo);
            return "fail";
        }

        String expectedPid = settingsService.getSetting("epay_pid");
        if (expectedPid == null || !expectedPid.equals(merchantId)) {
            logger.warn("Payment notify merchant mismatch for order {}", orderNo);
            return "fail";
        }

        try {
            BigDecimal expectedAmount = order.getTotalPrice();
            BigDecimal actualAmount = new BigDecimal(paidAmount);
            if (expectedAmount == null || expectedAmount.compareTo(actualAmount) != 0) {
                logger.warn("Payment notify amount mismatch for order {}", orderNo);
                return "fail";
            }
        } catch (Exception e) {
            logger.warn("Payment notify amount parse failed for order {}", orderNo, e);
            return "fail";
        }

        if ("TRADE_SUCCESS".equals(status)) {
            // 原子完成：内部先 pending→processing，防止并发回调重复发卡
            orderService.completeOrderSafely(orderNo);
        }

        return "success";
    }
}
