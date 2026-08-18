package org.xxg.backend.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.xxg.backend.backend.entity.Admin;
import org.xxg.backend.backend.entity.User;
import org.xxg.backend.backend.mapper.AdminMapper;
import org.xxg.backend.backend.mapper.UserMapper;
import org.xxg.backend.backend.util.JwtUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AdminMapper adminMapper;
    private final UserMapper userMapper;

    public JwtRequestFilter(JwtUtil jwtUtil, AdminMapper adminMapper, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.adminMapper = adminMapper;
        this.userMapper = userMapper;
    }

    private boolean isPersistedTokenValid(String username, String role, String jwt) {
        if (username == null || role == null || jwt == null) {
            return false;
        }
        if ("admin".equals(role)) {
            Admin admin = adminMapper.findByUsername(username);
            return admin != null && jwt.equals(admin.getAccessToken());
        }
        User user = userMapper.findByUsername(username);
        return user != null && jwt.equals(user.getAccessToken());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String authorizationHeader = request.getHeader("Authorization");

        String username = null;
        String jwt = null;
        String role = null;

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                username = jwtUtil.extractUsername(jwt);
                role = jwtUtil.extractRole(jwt);
            } catch (Exception e) {
                // Token invalid or expired
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            if (jwtUtil.validateToken(jwt, username) && isPersistedTokenValid(username, role, jwt)) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (role != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                }

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        username, null, authorities);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        chain.doFilter(request, response);
    }
}
