package org.xxg.backend.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.entity.Order;
import org.xxg.backend.backend.entity.User;
import org.xxg.backend.backend.service.OrderService;
import org.xxg.backend.backend.service.PaymentService;
import org.xxg.backend.backend.service.UserService;

import org.xxg.backend.backend.service.SettingsService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderService orderService;
    private final SettingsService settingsService;
    private final UserService userService;

    public PaymentController(PaymentService paymentService, OrderService orderService,
                             SettingsService settingsService, UserService userService) {
        this.paymentService = paymentService;
        this.orderService = orderService;
        this.settingsService = settingsService;
        this.userService = userService;
    }

    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> pay(@RequestBody Map<String, String> payload) {
        String orderNo = payload.get("orderNo");
        String paymentType = payload.get("paymentMethod"); // alipay, wxpay, etc.

        if (orderNo == null || orderNo.isBlank() || paymentType == null || paymentType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少参数"));
        }

        // 鉴权：未登录一律拒绝
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录"));
        }

        // Find order by orderNo
        Order order = orderService.getOrderByNo(orderNo);

        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "订单不存在"));
        }

        // 归属校验：管理员可操作任意订单，普通用户只能支付自己的订单
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            User currentUser = userService.getUserByUsername(auth.getName());
            if (currentUser == null || !order.getUserId().equals(currentUser.getId().intValue())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "无权操作该订单"));
            }
        }

        try {
            String payUrl = paymentService.createPaymentUrl(order, paymentType);
            return ResponseEntity.ok(Map.of("success", true, "payUrl", payUrl));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 支付平台服务端通知：多数平台使用 POST（部分使用 GET），统一兼容。
     */
    @RequestMapping(value = "/notify", method = {RequestMethod.GET, RequestMethod.POST})
    public String notify(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));

        try {
            return paymentService.handleNotify(params);
        } catch (Exception e) {
            return "fail";
        }
    }
    
    @GetMapping("/return")
    public void returnUrl(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        
        // Do not complete orders from the browser return URL; only the server notify callback may finalize payment.

        // Redirect to user page
        String siteUrl = settingsService.getSetting("site_url");
        String redirectUrl;
        
        if (siteUrl != null && !siteUrl.trim().isEmpty()) {
             if (siteUrl.endsWith("/")) siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
             
             // 如果配置了 site_url，优先使用不带端口的地址跳转回前端
             // 因为后端在 8080，前端在 80，直接跳转 site_url 即可回到前端
             // 但需要确保 site_url 填的是不带 8080 的域名
             // 如果用户误填了 8080，我们尝试智能修复一下
             if (siteUrl.contains(":8080")) {
                 redirectUrl = siteUrl.replace(":8080", "") + "/#/user?payment=success";
             } else {
                 redirectUrl = siteUrl + "/#/user?payment=success";
             }
        } else {
            // Fallback for local dev
            redirectUrl = "http://localhost:5173/#/user?payment=success";
        }
        
        response.sendRedirect(redirectUrl);
    }
}
