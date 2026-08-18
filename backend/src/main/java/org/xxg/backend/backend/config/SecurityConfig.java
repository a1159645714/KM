package org.xxg.backend.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.xxg.backend.backend.filter.JwtRequestFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/admin/login", "/auth/user/login", "/auth/refresh",
                        "/auth/email-code", "/auth/register", "/auth/register-bind",
                        "/auth/reset-code", "/auth/reset-password", "/auth/totp/recovery-code",
                        "/oauth/**", "/error").permitAll()
                .requestMatchers("/payment/notify", "/payment/return").permitAll()
                .requestMatchers("/maintenance/status", "/settings/public").permitAll()
                .requestMatchers("/public/cards/**", "/v1/**", "/custom/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/pricing").permitAll()
                .requestMatchers("/admin/**", "/cards/admin/**", "/cards/apikey/**", "/cards/trend",
                        "/pricing/**", "/settings/**", "/backup/**", "/maintenance/**",
                        "/monitor/**", "/stats/**", "/online/list").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"message\":\"Unauthorized: Login required or token expired\",\"success\":false}");
                })
            )
            .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 允许所有来源，方便调试和部署。生产环境建议指定具体域名。
        // configuration.setAllowedOrigins(List.of("*")); 
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(List.of("*"));
        // 当 AllowedOrigins 为 * 时，AllowCredentials 不能为 true，需改为 false 或指定具体 Origin
        // 这里为了兼容性，建议前端通过 Nginx 转发，或者指定具体 IP
        // 但为了方便用户直接测试 IP:8080，我们可以用 setAllowedOriginPatterns("*")
        String allowedOrigins = System.getenv().getOrDefault("CORS_ALLOWED_ORIGINS", "http://localhost:5173");
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
