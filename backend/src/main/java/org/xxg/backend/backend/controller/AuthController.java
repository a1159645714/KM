package org.xxg.backend.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xxg.backend.backend.dto.LoginRequest;
import org.xxg.backend.backend.dto.LoginResponse;
import org.xxg.backend.backend.dto.RegisterBindRequest;
import org.xxg.backend.backend.dto.RegisterRequest;
import org.xxg.backend.backend.dto.ResetPasswordRequest;
import org.xxg.backend.backend.dto.TokenRefreshRequest;
import org.xxg.backend.backend.service.AuthService;
import org.xxg.backend.backend.service.SettingsService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private SettingsService settingsService;

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            throw new RuntimeException("未登录");
        }
        return authentication;
    }

    private void requireAdminAccess(Authentication authentication, Long targetAdminId) {
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new RuntimeException("权限不足");
        }
        Map<String, Object> current = authService.getUserInfo(authentication.getName(), "admin");
        if (current == null || current.get("id") == null) {
            throw new RuntimeException("管理员不存在");
        }
        Long currentAdminId = Long.valueOf(current.get("id").toString());
        if (!currentAdminId.equals(targetAdminId)) {
            throw new RuntimeException("不能操作其他管理员账户");
        }
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody TokenRefreshRequest request) {
        Map<String, Object> data = authService.refreshToken(request.getRefreshToken());
        if (data != null) {
            return LoginResponse.success("刷新成功", data);
        }
        return LoginResponse.error("Refresh Token无效或已过期");
    }

    @PostMapping("/logout")
    public LoginResponse logout(@RequestBody Map<String, Object> request) {
        try {
            Authentication authentication = requireAuthentication();
            if (request.get("id") == null || request.get("role") == null) {
                return LoginResponse.error("参数错误");
            }
            Long userId = Long.valueOf(request.get("id").toString());
            String role = (String) request.get("role");
            String authRole = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "admin" : "user";
            Map<String, Object> currentUser = authService.getUserInfo(authentication.getName(), authRole);
            if (currentUser == null || currentUser.get("id") == null) {
                return LoginResponse.error("用户不存在");
            }
            Long currentUserId = Long.valueOf(currentUser.get("id").toString());
            if (!currentUserId.equals(userId) || !authRole.equals(role)) {
                return LoginResponse.error("无权登出其他用户");
            }
            authService.logout(userId, role);
            return LoginResponse.success("登出成功", null);
        } catch (Exception e) {
            return LoginResponse.error(e.getMessage());
        }
    }

    @PostMapping("/admin/login")
    public LoginResponse loginAdmin(@RequestBody LoginRequest request) {
        try {
            Map<String, Object> data = authService.loginAdmin(request.getUsername(), request.getPassword(), request.getTotpCode());
            if (data != null) {
                if (data.containsKey("requireTotp")) {
                    return LoginResponse.error("TOTP_REQUIRED");
                }
                return LoginResponse.success("登录成功", data);
            }
            return LoginResponse.error("用户名或密码错误");
        } catch (Exception e) {
            return LoginResponse.error("登录失败: " + e.getMessage());
        }
    }

    @PostMapping("/totp/setup")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse setupTotp(@RequestBody Map<String, Long> request) {
        try {
            Long adminId = Long.valueOf(request.get("id").toString());
            requireAdminAccess(requireAuthentication(), adminId);
            return LoginResponse.success("获取成功", authService.generateTotpSetup(adminId));
        } catch (Exception e) {
            return LoginResponse.error("获取失败: " + e.getMessage());
        }
    }

    @PostMapping("/totp/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse enableTotp(@RequestBody Map<String, Object> request) {
        try {
            Long adminId = Long.valueOf(request.get("id").toString());
            requireAdminAccess(requireAuthentication(), adminId);
            String secret = (String) request.get("secret");
            String code = (String) request.get("code");
            authService.enableTotp(adminId, secret, code);
            return LoginResponse.success("启用成功", null);
        } catch (Exception e) {
            return LoginResponse.error("启用失败: " + e.getMessage());
        }
    }

    @PostMapping("/totp/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse disableTotp(@RequestBody Map<String, Object> request) {
        try {
            Long adminId = Long.valueOf(request.get("id").toString());
            requireAdminAccess(requireAuthentication(), adminId);
            authService.disableTotp(adminId);
            return LoginResponse.success("禁用成功", null);
        } catch (Exception e) {
            return LoginResponse.error("禁用失败: " + e.getMessage());
        }
    }

    @PostMapping("/admin/update")
    @PreAuthorize("hasRole('ADMIN')")
    public LoginResponse updateAdmin(@RequestBody Map<String, Object> request) {
        try {
            Long id = Long.valueOf(request.get("id").toString());
            requireAdminAccess(requireAuthentication(), id);
            String username = (String) request.get("username");
            String password = (String) request.get("password");
            String email = (String) request.get("email");

            authService.updateAdmin(id, username, password, email);
            return LoginResponse.success("更新成功", null);
        } catch (Exception e) {
            return LoginResponse.error("更新失败: " + e.getMessage());
        }
    }

    @PostMapping("/user/login")
    public LoginResponse loginUser(@RequestBody LoginRequest request) {
        try {
            Map<String, Object> data = authService.loginUser(request.getUsername(), request.getPassword());
            if (data != null) {
                return LoginResponse.success("登录成功", data);
            }
            return LoginResponse.error("用户名或密码错误");
        } catch (Exception e) {
            return LoginResponse.error("系统错误: " + e.getMessage());
        }
    }

    @PostMapping("/email-code")
    public LoginResponse sendEmailCode(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String type = request.get("type");
        if (email == null || type == null) {
            return LoginResponse.error("参数错误");
        }
        try {
            authService.sendEmailCode(email, type);
            return LoginResponse.success("验证码已发送", null);
        } catch (Exception e) {
            return LoginResponse.error(e.getMessage());
        }
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.ok(LoginResponse.success("注册成功", null));
    }

    @PostMapping("/register-bind")
    public ResponseEntity<LoginResponse> registerBind(@RequestBody RegisterBindRequest request) {
        return ResponseEntity.ok(authService.registerBind(request));
    }

    @PostMapping("/reset-code")
    public LoginResponse sendResetCode(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String email = request.get("email");
        if (username == null || email == null) {
            return LoginResponse.error("参数错误");
        }
        try {
            authService.sendResetPasswordCode(username, email);
            return LoginResponse.success("验证码已发送", null);
        } catch (Exception e) {
            return LoginResponse.error(e.getMessage());
        }
    }

    @PostMapping("/reset-password")
    public LoginResponse resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            authService.resetPassword(request);
            return LoginResponse.success("密码重置成功", null);
        } catch (Exception e) {
            return LoginResponse.error("重置失败: " + e.getMessage());
        }
    }

    @GetMapping("/user/info")
    public LoginResponse getUserInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return LoginResponse.error("未登录");
        }

        String username = authentication.getName();
        String role = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "admin" : "user";

        try {
            Map<String, Object> data = authService.getUserInfo(username, role);
            if (data != null) {
                return LoginResponse.success("获取成功", data);
            }
            return LoginResponse.error("用户不存在");
        } catch (Exception e) {
            return LoginResponse.error("获取失败: " + e.getMessage());
        }
    }

    @PostMapping("/totp/recovery-code")
    public LoginResponse sendRecoveryCode(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        if (username == null) {
            return LoginResponse.error("参数错误");
        }
        try {
            authService.sendRecoveryCode(username);
            return LoginResponse.success("验证码已发送至管理员邮箱", null);
        } catch (Exception e) {
            return LoginResponse.error("发送失败: " + e.getMessage());
        }
    }

    @PostMapping("/totp/disable-by-recovery")
    public LoginResponse disableTotpByRecovery(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String code = request.get("code");
        if (username == null || code == null) {
            return LoginResponse.error("参数错误");
        }
        try {
            authService.disableTotpByRecoveryCode(username, code);
            return LoginResponse.success("TOTP已关闭，请重新登录", null);
        } catch (Exception e) {
            return LoginResponse.error("操作失败: " + e.getMessage());
        }
    }

    @GetMapping("/bind/token")
    public LoginResponse getBindToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return LoginResponse.error("未登录");
        }

        String username = authentication.getName();
        String role = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "admin" : "user";

        try {
            Map<String, Object> data = authService.getUserInfo(username, role);
            if (data != null && data.get("id") != null) {
                Long userId = Long.valueOf(data.get("id").toString());
                String token = authService.generateBindToken(userId);
                String siteUrl = settingsService.getSetting("site_url");
                if (siteUrl == null || siteUrl.isEmpty()) {
                    siteUrl = "";
                }

                Map<String, String> result = new HashMap<>();
                result.put("token", token);
                result.put("siteUrl", siteUrl);
                return LoginResponse.success("获取成功", result);
            }
            return LoginResponse.error("用户不存在");
        } catch (Exception e) {
            return LoginResponse.error("获取失败: " + e.getMessage());
        }
    }

    @PostMapping("/bind/validate")
    public LoginResponse validateBindToken(@RequestBody Map<String, Object> request) {
        if (request.get("userId") == null || request.get("token") == null) {
            return LoginResponse.error("参数错误");
        }

        try {
            Long userId = Long.valueOf(request.get("userId").toString());
            String token = (String) request.get("token");
            boolean isValid = authService.validateBindToken(userId, token);

            if (isValid) {
                return LoginResponse.success("验证成功", null);
            }
            return LoginResponse.error("验证失败或Token已过期");
        } catch (Exception e) {
            return LoginResponse.error("验证失败: " + e.getMessage());
        }
    }
}
