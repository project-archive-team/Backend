package com.projectarchive.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Long userId = jwtService.parseAccess(header.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                auth.setDetails(userId);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // 토큰이 깨졌으면 인증 없이 통과시키고, 접근 제어는 SecurityConfig가 판단한다.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
