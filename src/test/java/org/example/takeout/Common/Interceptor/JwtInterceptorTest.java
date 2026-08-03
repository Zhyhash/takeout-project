package org.example.takeout.Common.Interceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils);
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
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils);
        when(jwtUtils.parseToken("user-token")).thenReturn(claims(101L, AuthRole.USER));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer user-token");

        assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    private Claims claims(Long id, String role) {
        Claims claims = Jwts.claims();
        claims.put("id", id);
        claims.put("role", role);
        return claims;
    }
}
