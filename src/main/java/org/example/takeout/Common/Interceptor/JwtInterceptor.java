package org.example.takeout.Common.Interceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Auth.AuthPathMatcher;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.User.Service.UserService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JWTUtils jwtUtils;
    private final UserService userService;
    private final MerchantService merchantService;
    private final RiderService riderService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
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
            String matchedPattern = (String) request.getAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
            );

            if (matchedPattern == null) {
                throw new AuthException("无法识别请求资源");
            }

            assertRoleAllowed(matchedPattern, role);

            if (AuthRole.MERCHANT.equals(role)) {
                MerchantContextHolder.setMerchantId(id);
                merchantService.requireActiveMerchantId();
            } else if (AuthRole.USER.equals(role)) {
                UserContextHolder.setUserId(id);
                userService.requireActiveUserId();
            } else if (AuthRole.RIDER.equals(role)) {
                RiderContextHolder.setRiderId(id);
                riderService.requireActiveRiderId();
            } else {
                throw new AuthException("token角色无效");
            }
            return true;
        } catch (RuntimeException e) {
            UserContextHolder.clear();
            MerchantContextHolder.clear();
            RiderContextHolder.clear();
            throw e;
        }
    }


    private static void assertRoleAllowed(String matchedPattern, String role) {
        if (AuthPathMatcher.requiresMerchant(matchedPattern) && !AuthRole.MERCHANT.equals(role)) {
            throw new AuthException("无权访问该资源，请使用商家账号登录");
        }
        if (AuthPathMatcher.requiresUser(matchedPattern) && !AuthRole.USER.equals(role)) {
            throw new AuthException("无权访问该资源，请使用用户账号登录");
        }
        if (AuthPathMatcher.requiresRider(matchedPattern) && !AuthRole.RIDER.equals(role)) {
            throw new AuthException("无权访问该资源，请使用骑手账号登录");
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
        throw new AuthException("token过期或无效");
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) {
        UserContextHolder.clear();
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }
}
