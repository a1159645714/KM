package org.xxg.backend.backend.service;

import io.jsonwebtoken.Claims;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xxg.backend.backend.dto.LoginResponse;
import org.xxg.backend.backend.dto.RegisterBindRequest;
import org.xxg.backend.backend.dto.RegisterRequest;
import org.xxg.backend.backend.dto.ResetPasswordRequest;
import org.xxg.backend.backend.entity.Admin;
import org.xxg.backend.backend.entity.ApiKey;
import org.xxg.backend.backend.entity.SocialUser;
import org.xxg.backend.backend.entity.User;
import org.xxg.backend.backend.entity.VerificationCode;
import org.xxg.backend.backend.mapper.AdminMapper;
import org.xxg.backend.backend.mapper.ApiKeyMapper;
import org.xxg.backend.backend.mapper.SocialUserMapper;
import org.xxg.backend.backend.mapper.UserMapper;
import org.xxg.backend.backend.mapper.VerificationCodeMapper;
import org.xxg.backend.backend.util.JwtUtil;
import org.xxg.backend.backend.util.PasswordUtil;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final int RECOVERY_CODE_LIMIT_PER_HOUR = 5;
    private static final int LOGIN_FAILURE_LIMIT = 10;
    private static final int LOGIN_LOCK_MINUTES = 15;

    private final AdminMapper adminMapper;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final VerificationCodeMapper verificationCodeMapper;
    private final EmailService emailService;
    private final ApiKeyMapper apiKeyMapper;
    private final TotpService totpService;
    private final SettingsService settingsService;
    private final SocialUserMapper socialUserMapper;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    public AuthService(AdminMapper adminMapper, UserMapper userMapper, JwtUtil jwtUtil,
                       VerificationCodeMapper verificationCodeMapper, EmailService emailService, ApiKeyMapper apiKeyMapper,
                       TotpService totpService, SettingsService settingsService, SocialUserMapper socialUserMapper) {
        this.adminMapper = adminMapper;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.verificationCodeMapper = verificationCodeMapper;
        this.emailService = emailService;
        this.apiKeyMapper = apiKeyMapper;
        this.totpService = totpService;
        this.settingsService = settingsService;
        this.socialUserMapper = socialUserMapper;
    }

    public LoginResponse registerBind(RegisterBindRequest request) {
        String registerToken = request.getRegisterToken();
        if (registerToken == null || !jwtUtil.validateRegisterToken(registerToken)) {
            throw new RuntimeException("注册令牌无效或已过期，请重新通过第三方登录");
        }

        Claims claims = jwtUtil.extractAllClaims(registerToken);
        String socialUid = (String) claims.get("socialUid");
        String socialType = (String) claims.get("socialType");

        if (socialUid == null || socialType == null) {
            throw new RuntimeException("注册令牌信息不完整");
        }

        if (userMapper.findByUsernameOrEmail(request.getUsername()) != null) {
            throw new RuntimeException("用户名已存在");
        }

        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            if (userMapper.findByUsernameOrEmail(request.getEmail()) != null) {
                throw new RuntimeException("邮箱已存在");
            }
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(PasswordUtil.hashPassword(request.getPassword()));
        user.setNickname(request.getUsername());
        user.setEmail(request.getEmail());
        user.setStatus(1);
        user.setEmailVerified(0);
        user.setLoginCount(0);
        user.setCreateTime(LocalDateTime.now());
        user.setRegisterIp("127.0.0.1");

        userMapper.insertUser(user);

        if (user.getId() == null) {
            User savedUser = userMapper.findByUsername(request.getUsername());
            if (savedUser != null) {
                user.setId(savedUser.getId());
            }
        }

        SocialUser socialUser = new SocialUser();
        socialUser.setUserId(user.getId());
        socialUser.setSocialUid(socialUid);
        socialUser.setSocialType(socialType);
        socialUserMapper.insert(socialUser);

        try {
            ApiKey unassignedKey = apiKeyMapper.findFirstUnassignedKey();
            if (unassignedKey != null) {
                apiKeyMapper.assignUser(unassignedKey.getId(), user.getId());
            }
        } catch (Exception e) {
            logger.warn("Failed to auto-assign API key during social registration", e);
        }

        String token = jwtUtil.generateToken(user.getUsername(), "user");
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername(), "user");
        try {
            userMapper.updateLastLogin(user.getId(), "127.0.0.1", token, refreshToken);
        } catch (Exception e) {
            logger.warn("Failed to update social registration login state", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("userInfo", user);

        return LoginResponse.success("注册并绑定成功", result);
    }

    public String generateBindToken(Long userId) {
        if (redissonClient == null) {
            throw new IllegalStateException("绑定功能依赖 Redis，请先完成 Redis 配置");
        }

        String token = UUID.randomUUID().toString();
        String key = "bind_token:" + userId;

        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.set(token, 5, TimeUnit.MINUTES);
        return token;
    }

    public boolean validateBindToken(Long userId, String token) {
        if (redissonClient == null) {
            throw new IllegalStateException("绑定功能依赖 Redis，请先完成 Redis 配置");
        }

        String key = "bind_token:" + userId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        String storedToken = bucket.get();

        if (storedToken != null && storedToken.equals(token)) {
            bucket.delete();
            return true;
        }
        return false;
    }

    private void ensureLoginAllowed(String scope, String username) {
        if (redissonClient == null || username == null || username.isBlank()) {
            return;
        }
        String key = "auth:lock:" + scope + ":" + username;
        Long lockUntil = redissonClient.<Long>getBucket(key).get();
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        if (lockUntil != null && lockUntil > now) {
            throw new RuntimeException("登录失败次数过多，请稍后再试");
        }
    }

    private void recordLoginFailure(String scope, String username) {
        if (redissonClient == null || username == null || username.isBlank()) {
            return;
        }
        String failureKey = "auth:fail:" + scope + ":" + username;
        String lockKey = "auth:lock:" + scope + ":" + username;
        RBucket<Integer> failureBucket = redissonClient.getBucket(failureKey);
        Integer failures = failureBucket.get();
        int count = failures == null ? 1 : failures + 1;
        failureBucket.set(count, LOGIN_LOCK_MINUTES, TimeUnit.MINUTES);
        if (count >= LOGIN_FAILURE_LIMIT) {
            redissonClient.getBucket(lockKey).set(LocalDateTime.now().plusMinutes(LOGIN_LOCK_MINUTES).toEpochSecond(ZoneOffset.UTC), LOGIN_LOCK_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearLoginFailure(String scope, String username) {
        if (redissonClient == null || username == null || username.isBlank()) {
            return;
        }
        redissonClient.getBucket("auth:fail:" + scope + ":" + username).delete();
        redissonClient.getBucket("auth:lock:" + scope + ":" + username).delete();
    }

    public Map<String, Object> loginAdmin(String username, String password, String totpCode) {
        ensureLoginAllowed("admin", username);
        Admin admin = null;
        try {
            admin = adminMapper.findByUsername(username);
        } catch (Exception e) {
            logger.warn("Database error finding admin {}", username, e);
        }

        if (admin != null && PasswordUtil.verifyPasswordSimple(password, admin.getPassword())) {
            String globalTotp = settingsService.getSetting("authenticatorLogin");
            boolean isGlobalTotpEnabled = "true".equals(globalTotp);

            if (isGlobalTotpEnabled && Boolean.TRUE.equals(admin.getTotpEnabled())) {
                if (totpCode == null || totpCode.isEmpty()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("requireTotp", true);
                    return result;
                }
                if (!totpService.verifyCode(admin.getTotpSecret(), totpCode)) {
                    recordLoginFailure("admin", username);
                    throw new RuntimeException("验证码错误");
                }
            }

            String token = jwtUtil.generateToken(username, "admin");
            String refreshToken = jwtUtil.generateRefreshToken(username, "admin");
            try {
                adminMapper.updateLastLogin(admin.getId(), token, refreshToken);
            } catch (Exception e) {
                logger.warn("Failed to update admin token", e);
            }

            clearLoginFailure("admin", username);

            Map<String, Object> result = new HashMap<>();
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", admin.getId());
            userInfo.put("username", admin.getUsername());
            userInfo.put("role", "admin");
            userInfo.put("totpEnabled", admin.getTotpEnabled());

            result.put("userInfo", userInfo);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
            return result;
        }

        recordLoginFailure("admin", username);
        return null;
    }

    public Map<String, Object> loginAdmin(String username, String password) {
        return loginAdmin(username, password, null);
    }

    public Map<String, Object> loginUser(String username, String password) {
        ensureLoginAllowed("user", username);
        User user = null;
        try {
            user = userMapper.findByUsernameOrEmail(username);
        } catch (Exception e) {
            logger.warn("Database error finding user {}", username, e);
        }

        if (user != null && PasswordUtil.verifyPasswordSimple(password, user.getPassword())) {
            String token = jwtUtil.generateToken(user.getUsername(), "user");
            String refreshToken = jwtUtil.generateRefreshToken(user.getUsername(), "user");
            try {
                userMapper.updateLastLogin(user.getId(), "127.0.0.1", token, refreshToken);
            } catch (Exception e) {
                logger.warn("Failed to update user token", e);
            }

            clearLoginFailure("user", username);

            Map<String, Object> result = new HashMap<>();
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", user.getId());
            userInfo.put("username", user.getUsername());
            userInfo.put("nickname", user.getNickname());
            userInfo.put("email", user.getEmail());
            userInfo.put("avatar", user.getAvatar());
            userInfo.put("role", "user");

            result.put("userInfo", userInfo);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
            return result;
        }

        recordLoginFailure("user", username);
        return null;
    }

    public Map<String, Object> refreshToken(String requestRefreshToken) {
        String username = jwtUtil.extractUsername(requestRefreshToken);
        String role = jwtUtil.extractRole(requestRefreshToken);

        if (username != null && jwtUtil.validateRefreshToken(requestRefreshToken, username)) {
            String persistedToken = null;
            if ("admin".equals(role)) {
                Admin admin = adminMapper.findByUsername(username);
                if (admin != null) persistedToken = admin.getRefreshToken();
            } else {
                User user = userMapper.findByUsername(username);
                if (user != null) persistedToken = user.getRefreshToken();
            }

            if (requestRefreshToken.equals(persistedToken)) {
                String newAccessToken = jwtUtil.generateToken(username, role);
                String newRefreshToken = jwtUtil.generateRefreshToken(username, role);

                if ("admin".equals(role)) {
                    Admin admin = adminMapper.findByUsername(username);
                    if (admin != null) {
                        adminMapper.updateLastLogin(admin.getId(), newAccessToken, newRefreshToken);
                    }
                } else {
                    User user = userMapper.findByUsername(username);
                    if (user != null) {
                        userMapper.updateLastLogin(user.getId(), "127.0.0.1", newAccessToken, newRefreshToken);
                    }
                }

                Map<String, Object> result = new HashMap<>();
                result.put("token", newAccessToken);
                result.put("refreshToken", newRefreshToken);
                return result;
            }
        }
        return null;
    }

    public void logout(Long id, String role) {
        if ("admin".equals(role)) {
            adminMapper.clearTokens(id);
        } else {
            userMapper.clearTokens(id);
        }
    }

    public Map<String, Object> getUserInfo(String username, String role) {
        if ("admin".equals(role)) {
            Admin admin = adminMapper.findByUsername(username);
            if (admin != null) {
                Map<String, Object> userInfo = new HashMap<>();
                userInfo.put("id", admin.getId());
                userInfo.put("username", admin.getUsername());
                userInfo.put("role", "admin");
                userInfo.put("totpEnabled", admin.getTotpEnabled());
                return userInfo;
            }
        } else {
            User user = userMapper.findByUsername(username);
            if (user != null) {
                Map<String, Object> userInfo = new HashMap<>();
                userInfo.put("id", user.getId());
                userInfo.put("username", user.getUsername());
                userInfo.put("nickname", user.getNickname());
                userInfo.put("email", user.getEmail());
                userInfo.put("avatar", user.getAvatar());
                userInfo.put("role", "user");
                return userInfo;
            }
        }
        return null;
    }

    public void updateAdmin(Long id, String username, String password, String email) {
        Admin admin = adminMapper.findById(id);
        if (admin == null) {
            throw new RuntimeException("管理员不存在");
        }

        String previousUsername = admin.getUsername();
        boolean passwordChanged = false;

        if (username != null && !username.isEmpty() && !username.equals(admin.getUsername())) {
            Admin existing = adminMapper.findByUsername(username);
            if (existing != null) {
                throw new RuntimeException("用户名已存在");
            }
            admin.setUsername(username);
        }

        if (password != null && !password.isEmpty()) {
            admin.setPassword(PasswordUtil.hashPassword(password));
            passwordChanged = true;
        }

        if (email != null && !email.isEmpty()) {
            admin.setEmail(email);
        }

        adminMapper.updateAdmin(admin);

        if (passwordChanged) {
            adminMapper.clearTokens(id);
            clearLoginFailure("admin", admin.getUsername());
            if (!previousUsername.equals(admin.getUsername())) {
                clearLoginFailure("admin", previousUsername);
            }
        }
    }

    public Map<String, String> generateTotpSetup(Long adminId) {
        Admin admin = adminMapper.findById(adminId);
        if (admin == null) {
            throw new RuntimeException("管理员不存在");
        }

        String secret = totpService.generateSecret();
        String qrCode = totpService.getQrCodeImageUri(secret, admin.getUsername());

        Map<String, String> result = new HashMap<>();
        result.put("secret", secret);
        result.put("qrCode", qrCode);
        return result;
    }

    public void enableTotp(Long adminId, String secret, String code) {
        if (!totpService.verifyCode(secret, code)) {
            throw new RuntimeException("验证码错误");
        }
        adminMapper.updateTotp(adminId, secret, true);
    }

    public void disableTotp(Long adminId) {
        adminMapper.updateTotp(adminId, null, false);
    }

    public void sendEmailCode(String email, String type) {
        if ("register".equals(type)) {
            User existing = userMapper.findByUsernameOrEmail(email);
            if (existing != null) {
                throw new RuntimeException("该邮箱已被注册");
            }
        }

        int count = verificationCodeMapper.countCodesInLastHour(email);
        if (count >= 10) {
            throw new RuntimeException("每小时最多发送10次验证码");
        }

        VerificationCode lastCode = verificationCodeMapper.findLatestByEmailAndType(email, type);
        if (lastCode != null && lastCode.getCreateTime().plusSeconds(60).isAfter(LocalDateTime.now())) {
            throw new RuntimeException("请勿频繁发送验证码，请稍后再试");
        }

        String code = String.format("%06d", new Random().nextInt(999999));

        VerificationCode vc = new VerificationCode();
        vc.setEmail(email);
        vc.setCode(code);
        vc.setType(type);
        vc.setExpireTime(LocalDateTime.now().plusMinutes(5));

        verificationCodeMapper.insert(vc);
        emailService.sendVerificationEmail(email, code, type);
    }

    public void register(RegisterRequest request) {
        VerificationCode vc = verificationCodeMapper.findLatestByEmailAndType(request.getEmail(), "register");
        if (vc == null) {
            throw new RuntimeException("验证码无效或不存在");
        }
        if (vc.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("验证码已过期");
        }
        if (!vc.getCode().equals(request.getCode())) {
            throw new RuntimeException("验证码错误");
        }

        if (userMapper.findByUsernameOrEmail(request.getUsername()) != null) {
            throw new RuntimeException("用户名已存在");
        }
        if (userMapper.findByUsernameOrEmail(request.getEmail()) != null) {
            throw new RuntimeException("邮箱已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(PasswordUtil.hashPassword(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setEmailVerified(1);
        user.setStatus(1);
        user.setRegisterIp("127.0.0.1");

        userMapper.insertUser(user);

        try {
            User savedUser = userMapper.findByUsername(user.getUsername());
            if (savedUser != null) {
                ApiKey unassignedKey = apiKeyMapper.findFirstUnassignedKey();
                if (unassignedKey != null) {
                    apiKeyMapper.assignUser(unassignedKey.getId(), savedUser.getId());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to auto-assign API key after register", e);
        }

        verificationCodeMapper.deleteByEmailAndType(request.getEmail(), "register");

        try {
            emailService.sendRegistrationSuccess(request.getEmail());
        } catch (Exception e) {
            logger.warn("Failed to send registration success email", e);
        }
    }

    public void sendResetPasswordCode(String username, String email) {
        User user = userMapper.findByUsername(username);
        if (user == null || !user.getEmail().equals(email)) {
            throw new RuntimeException("用户名与邮箱不匹配或用户不存在");
        }

        int count = verificationCodeMapper.countCodesInLastHour(email);
        if (count >= 10) {
            throw new RuntimeException("每小时最多发送10次验证码");
        }

        VerificationCode lastCode = verificationCodeMapper.findLatestByEmailAndType(email, "reset_password");
        if (lastCode != null && lastCode.getCreateTime().plusSeconds(60).isAfter(LocalDateTime.now())) {
            throw new RuntimeException("请勿频繁发送验证码，请稍后再试");
        }

        String code = String.format("%06d", new Random().nextInt(999999));

        VerificationCode vc = new VerificationCode();
        vc.setEmail(email);
        vc.setCode(code);
        vc.setType("reset_password");
        vc.setExpireTime(LocalDateTime.now().plusMinutes(5));

        verificationCodeMapper.insert(vc);
        emailService.sendVerificationEmail(email, code, "reset_password");
    }

    public void resetPassword(ResetPasswordRequest request) {
        User user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        VerificationCode vc = verificationCodeMapper.findLatestByEmailAndType(user.getEmail(), "reset_password");
        if (vc == null || vc.getExpireTime().isBefore(LocalDateTime.now()) || !vc.getCode().equals(request.getCode())) {
            throw new RuntimeException("验证码无效或已过期");
        }

        userMapper.updatePassword(user.getUsername(), PasswordUtil.hashPassword(request.getPassword()));
        userMapper.clearTokens(user.getId());
        verificationCodeMapper.deleteByEmailAndType(user.getEmail(), "reset_password");
    }

    public void sendRecoveryCode(String username) {
        Admin admin = adminMapper.findByUsername(username);
        if (admin == null) {
            throw new RuntimeException("管理员不存在");
        }
        if (admin.getEmail() == null || admin.getEmail().isEmpty()) {
            throw new RuntimeException("管理员未绑定邮箱，无法重置");
        }

        String email = admin.getEmail();
        int count = verificationCodeMapper.countCodesInLastHour(email);
        if (count >= RECOVERY_CODE_LIMIT_PER_HOUR) {
            throw new RuntimeException("每小时最多发送5次验证码");
        }

        VerificationCode lastCode = verificationCodeMapper.findLatestByEmailAndType(email, "totp_recovery");
        if (lastCode != null && lastCode.getCreateTime().plusSeconds(60).isAfter(LocalDateTime.now())) {
            throw new RuntimeException("请勿频繁发送验证码，请稍后再试");
        }

        String code = String.format("%06d", new Random().nextInt(999999));

        VerificationCode vc = new VerificationCode();
        vc.setEmail(email);
        vc.setCode(code);
        vc.setType("totp_recovery");
        vc.setExpireTime(LocalDateTime.now().plusMinutes(5));

        verificationCodeMapper.insert(vc);
        emailService.sendVerificationEmail(email, code, "totp_recovery");
    }

    public void disableTotpByRecoveryCode(String username, String code) {
        Admin admin = adminMapper.findByUsername(username);
        if (admin == null) {
            throw new RuntimeException("管理员不存在");
        }

        String email = admin.getEmail();
        if (email == null) {
            throw new RuntimeException("管理员未绑定邮箱");
        }

        VerificationCode vc = verificationCodeMapper.findLatestByEmailAndType(email, "totp_recovery");
        if (vc == null || vc.getExpireTime().isBefore(LocalDateTime.now()) || !vc.getCode().equals(code)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        adminMapper.updateTotp(admin.getId(), null, false);
        adminMapper.clearTokens(admin.getId());
        verificationCodeMapper.deleteByEmailAndType(email, "totp_recovery");
    }
}
