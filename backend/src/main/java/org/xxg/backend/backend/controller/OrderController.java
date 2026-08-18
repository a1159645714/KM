package org.xxg.backend.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.dto.CreateOrderRequest;
import org.xxg.backend.backend.entity.Order;
import org.xxg.backend.backend.entity.User;
import org.xxg.backend.backend.service.OrderService;
import org.xxg.backend.backend.service.PaymentService;
import org.xxg.backend.backend.service.UserService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final UserService userService;

    public OrderController(OrderService orderService, PaymentService paymentService, UserService userService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.userService = userService;
    }

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
            throw new RuntimeException("管理员账户不能下单");
        }
        User user = userService.getUserByUsername(authentication.getName());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            User currentUser = requireCurrentUser();
            request.setUserId(currentUser.getId().intValue());
            request.setUsername(currentUser.getUsername());

            Order order = orderService.createOrder(request);
            Map<String, Object> response = new HashMap<>();

            if ("pending".equals(order.getStatus()) && paymentService.isPaymentEnabled()) {
                String paymentUrl = paymentService.createPaymentUrl(order, request.getPaymentMethod());
                response.put("paymentUrl", paymentUrl);
            }

            response.put("success", true);
            response.put("message", "下单成功");
            response.put("data", order);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "下单失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAllOrders(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cardType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        try {
            List<Order> orders = orderService.searchOrders(orderId, username, status, cardType, startTime, endTime);
            return ResponseEntity.ok(Map.of("success", true, "data", orders));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/admin/updateStatus")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateOrderStatus(@RequestBody Map<String, String> payload) {
        String orderNo = payload.get("orderNo");
        String status = payload.get("status");
        try {
            if (orderService.updateOrderStatus(orderNo, status)) {
                return ResponseEntity.ok(Map.of("success", true, "message", "状态更新成功"));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "更新失败，订单不存在"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getUserOrders() {
        try {
            User currentUser = requireCurrentUser();
            return ResponseEntity.ok(orderService.getUserOrders(currentUser.getId().intValue()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
