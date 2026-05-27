package com.example.driving.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String BLACKLIST_PREFIX = "blackList:";

    private final JwtProvider jwtProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = jwtProvider.resolveBearerToken(request);

        if (StringUtils.hasText(token) && jwtProvider.isValid(token)
                && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + token))) {
            Long memberIdx = jwtProvider.getMemberIdx(token);
            String role = jwtProvider.getRole(token);
            var auth = new UsernamePasswordAuthenticationToken(
                    memberIdx, token, List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
