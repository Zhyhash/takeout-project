package org.example.takeout.Common.Interceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.takeout.Common.Auth.AuthPathMatcher;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JWTUtils jwtUtils;

    public JwtInterceptor(JWTUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (AuthPathMatcher.isPublicPath(request)) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException("token不存在或无效，请登录");
        }
        if (authorization.length() == 7) {
            throw new AuthException("token不能为空");
        }

        String token = authorization.substring(7);
        Claims claims;
        try {
            claims = jwtUtils.parseToken(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new AuthException("token不存在或无效，请登录");
        }

        String role = claims.get("role", String.class);
        Long id = extractId(claims.get("id"));
        String path = AuthPathMatcher.normalizePath(request);

        assertRoleAllowed(path, role);

        if (AuthRole.MERCHANT.equals(role)) {
            MerchantContextHolder.setMerchantId(id);
        } else if (AuthRole.USER.equals(role)) {
            UserContextHolder.setUserId(id);
        } else {
            throw new AuthException("token角色无效");
        }
        return true;
    }

    private static void assertRoleAllowed(String path, String role) {
        if (AuthPathMatcher.requiresMerchant(path) && !AuthRole.MERCHANT.equals(role)) {
            throw new AuthException("无权访问该资源，请使用商家账号登录");
        }
        if (AuthPathMatcher.requiresUser(path) && !AuthRole.USER.equals(role)) {
            throw new AuthException("无权访问该资源，请使用用户账号登录");
        }
    }

    private static Long extractId(Object idObj) {
        if (idObj instanceof Integer integer) {
            return integer.longValue();
        }
        if (idObj instanceof Long longId) {
            return longId;
        }
        if (idObj instanceof String str && !str.isBlank()) {
            return Long.parseLong(str);
        }
        throw new AuthException("token载荷无效");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception ex) {
        UserContextHolder.clear();
        MerchantContextHolder.clear();
    }
}
