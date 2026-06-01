package org.example.tokeout.Common.Interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.tokeout.Common.Exception.AuthException;
import org.example.tokeout.Common.Utils.MyScurity.JWTUtils;
import org.example.tokeout.Common.Utils.Context.UserContextHolder;
import org.example.tokeout.Common.Utils.Context.MerchantContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

public class JwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException("token不存在或无效，请登录");
        }

        // 截取掉 "Bearer " (长度是7)
        String token = authorization.substring(7);

        // 1. 尝试解析普通用户 Token
        Long userId = JWTUtils.parseToken(token);
        if (userId != null) {
            UserContextHolder.setUserId(userId);
            return true; // 解析成功，直接放行
        }

        // 2. 尝试解析商家 Token
        Long merchantId = JWTUtils.parseMerchantToken(token);
        if (merchantId != null) {
            MerchantContextHolder.setMerchantId(merchantId);
            return true; // 解析成功，直接放行
        }

        // 3. 两种 Token 都解析失败，说明 Token 非法或被篡改
        throw new AuthException("token不存在或无效，请登录");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        // 请求结束后，同时清理两个上下文，防止线程复用导致数据串号
        UserContextHolder.clear();
        MerchantContextHolder.clear();
    }
}