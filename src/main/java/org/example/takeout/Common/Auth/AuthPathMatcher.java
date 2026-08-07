package org.example.takeout.Common.Auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

/**
 * 声明哪些路径无需登录、哪些路径仅用户/仅商家可访问。
 */
public final class AuthPathMatcher {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private AuthPathMatcher() {
    }

    /** 完全公开，不校验 Token */
    public static boolean isPublicPath(HttpServletRequest request) {
        String method = request.getMethod();
        String path = normalizePath(request);

        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        if (PATH_MATCHER.match("/user/login", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if (PATH_MATCHER.match("/user/register", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if (PATH_MATCHER.match("/merchant/login", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if(PATH_MATCHER.match("/merchant/register", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if (PATH_MATCHER.match("/rider/login", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if (PATH_MATCHER.match("/rider/register", path) && HttpMethod.POST.matches(method)) {
            return true;
        }
        if (PATH_MATCHER.match("/api/customer/shops", path)
                || PATH_MATCHER.match("/api/customer/shops/**", path)) {
            return true;
        }

        // 用户端浏览商家列表/详情（无需登录）
        if (HttpMethod.GET.matches(method)) {
            if (PATH_MATCHER.match("/user", path)) {
                return true;
            }
            if (PATH_MATCHER.match("/user/*", path) && !path.endsWith("/login") && !path.endsWith("/register")) {
                return true;
            }
        }

        // 文档（若启用 knife4j）
        if (path.startsWith("/doc.html") || path.startsWith("/webjars/")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger")) {
            return true;
        }

        return false;
    }

    public static boolean requiresUser(String path) {
        return PATH_MATCHER.match("/cart/**", path) || PATH_MATCHER.match("/order/**", path);
    }

    public static boolean requiresMerchant(String path) {
        if (PATH_MATCHER.match("/category/**", path)) {
            return true;
        }
        if (PATH_MATCHER.match("/merchant/info", path)) {
            return true;
        }
        if (PATH_MATCHER.match("/merchant/restore/**", path)) {
            return true;
        }
        if (PATH_MATCHER.match("/merchant/orders/**", path)) {
            return true;
        }
        return false;
    }

    public static boolean requiresRider(String path) {
        return PATH_MATCHER.match("/rider/**", path);
    }

    public static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty()) {
            return "/";
        }
        return uri;
    }
}
