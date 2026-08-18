package org.example.takeout.Common.Interceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.User.Service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtInterceptorTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }

    @Test
    void riderTokenPopulatesAndClearsRiderContext() {
        JWTUtils jwtUtils = mock(JWTUtils.class);
        UserService userService = mock(UserService.class);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils, userService);
        when(jwtUtils.parseToken("rider-token")).thenReturn(claims(301L, AuthRole.RIDER));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer rider-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(301L, RiderContextHolder.getRiderId());
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void riderPathRejectsNonRiderTokenAndClearsContext() {
        JWTUtils jwtUtils = mock(JWTUtils.class);
        UserService userService = mock(UserService.class);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils, userService);
        when(jwtUtils.parseToken("user-token")).thenReturn(claims(101L, AuthRole.USER));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer user-token");

        assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void userTokenRequiresActiveDatabaseAccount() {
        JWTUtils jwtUtils = mock(JWTUtils.class);
        UserService userService = mock(UserService.class);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils, userService);
        when(jwtUtils.parseToken("user-token")).thenReturn(claims(101L, AuthRole.USER));
        when(userService.requireActiveUserId()).thenReturn(101L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order");
        request.addHeader("Authorization", "Bearer user-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(101L, UserContextHolder.getUserId());
        verify(userService).requireActiveUserId();
    }

    @Test
    void disabledUserWithExistingTokenIsRejectedAndContextIsCleared() {
        JWTUtils jwtUtils = mock(JWTUtils.class);
        UserService userService = mock(UserService.class);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils, userService);
        when(jwtUtils.parseToken("disabled-user-token")).thenReturn(claims(102L, AuthRole.USER));
        doThrow(new AuthException("用户账号已禁用或不存在，请重新登录"))
                .when(userService).requireActiveUserId();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cart/items");
        request.addHeader("Authorization", "Bearer disabled-user-token");

        assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(UserContextHolder.getUserId());
    }

    private Claims claims(Long id, String role) {
        Claims claims = Jwts.claims();
        claims.put("id", id);
        claims.put("role", role);
        return claims;
    }
}
