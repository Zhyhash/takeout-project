package org.example.takeout.Common.Interceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.User.Service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtInterceptorTest {

    private final JWTUtils jwtUtils = mock(JWTUtils.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantService merchantService = mock(MerchantService.class);
    private final RiderService riderService = mock(RiderService.class);
    private final JwtInterceptor interceptor =
            new JwtInterceptor(jwtUtils, userService, merchantService, riderService);

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }

    @Test
    void riderTokenPopulatesAndClearsRiderContext() {
        when(jwtUtils.parseToken("rider-token")).thenReturn(claims(301L, AuthRole.RIDER));
        when(riderService.requireActiveRiderId()).thenAnswer(invocation -> {
            assertEquals(301L, RiderContextHolder.getRiderId());
            return 301L;
        });

        MockHttpServletRequest request = mappedRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer rider-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(301L, RiderContextHolder.getRiderId());
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        verify(riderService).requireActiveRiderId();
        verifyNoInteractions(userService, merchantService);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void riderPathRejectsNonRiderTokenAndClearsContext() {
        when(jwtUtils.parseToken("user-token")).thenReturn(claims(101L, AuthRole.USER));

        MockHttpServletRequest request = mappedRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer user-token");

        AuthException exception = assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("无权访问该资源，请使用骑手账号登录", exception.getMessage());
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
        verifyNoInteractions(userService, merchantService, riderService);
    }

    @Test
    void userTokenRequiresActiveDatabaseAccount() {
        when(jwtUtils.parseToken("user-token")).thenReturn(claims(101L, AuthRole.USER));
        when(userService.requireActiveUserId()).thenReturn(101L);

        MockHttpServletRequest request = mappedRequest("GET", "/order");
        request.addHeader("Authorization", "Bearer user-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(101L, UserContextHolder.getUserId());
        verify(userService).requireActiveUserId();
    }

    @Test
    void disabledUserWithExistingTokenIsRejectedAndContextIsCleared() {
        when(jwtUtils.parseToken("disabled-user-token")).thenReturn(claims(102L, AuthRole.USER));
        doThrow(new AuthException("用户账号已禁用或不存在，请重新登录"))
                .when(userService).requireActiveUserId();

        MockHttpServletRequest request = mappedRequest("GET", "/cart/items");
        request.addHeader("Authorization", "Bearer disabled-user-token");

        AuthException exception = assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("用户账号已禁用或不存在，请重新登录", exception.getMessage());
        verify(userService).requireActiveUserId();
        verifyNoInteractions(merchantService, riderService);
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void merchantTokenRequiresExistingAccountAndClearsContextAfterCompletion() {
        when(jwtUtils.parseToken("merchant-token")).thenReturn(claims(201L, AuthRole.MERCHANT));
        when(merchantService.requireActiveMerchantId()).thenAnswer(invocation -> {
            assertEquals(201L, MerchantContextHolder.getMerchantId());
            return 201L;
        });
        MockHttpServletRequest request = mappedRequest("GET", "/merchant/info");
        request.addHeader("Authorization", "Bearer merchant-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(201L, MerchantContextHolder.getMerchantId());
        assertNull(UserContextHolder.getUserId());
        assertNull(RiderContextHolder.getRiderId());
        verify(merchantService).requireActiveMerchantId();
        verifyNoInteractions(userService, riderService);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(MerchantContextHolder.getMerchantId());
    }

    @Test
    void deletedMerchantWithExistingTokenIsRejectedAndAllContextsAreCleared() {
        when(jwtUtils.parseToken("merchant-token")).thenReturn(claims(201L, AuthRole.MERCHANT));
        when(merchantService.requireActiveMerchantId()).thenThrow(new AuthException("商家账号不存在"));
        MockHttpServletRequest request = mappedRequest("GET", "/merchant/info");
        request.addHeader("Authorization", "Bearer merchant-token");

        AuthException exception = assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("商家账号不存在", exception.getMessage());
        verify(merchantService).requireActiveMerchantId();
        verifyNoInteractions(userService, riderService);
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void disabledRiderWithExistingTokenIsRejectedAndAllContextsAreCleared() {
        when(jwtUtils.parseToken("rider-token")).thenReturn(claims(301L, AuthRole.RIDER));
        when(riderService.requireActiveRiderId()).thenThrow(new AuthException("骑手账号已禁用"));
        MockHttpServletRequest request = mappedRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer rider-token");

        AuthException exception = assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("骑手账号已禁用", exception.getMessage());
        verify(riderService).requireActiveRiderId();
        verifyNoInteractions(userService, merchantService);
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void protectedRequestWithoutMatchedPatternIsRejectedBeforeAccountLookup() {
        when(jwtUtils.parseToken("rider-token")).thenReturn(claims(301L, AuthRole.RIDER));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rider/tasks");
        request.addHeader("Authorization", "Bearer rider-token");

        AuthException exception = assertThrows(AuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("无法识别请求资源", exception.getMessage());
        verifyNoInteractions(userService, merchantService, riderService);
        assertNull(UserContextHolder.getUserId());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void publicLoginPathsDoNotRequireExistingAccount() {
        for (String role : new String[]{AuthRole.USER, AuthRole.MERCHANT, AuthRole.RIDER}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/" + role + "/login");
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        }
        verifyNoInteractions(jwtUtils, userService, merchantService, riderService);
    }

    @Test
    void defaultProductImageCanBeReadWithoutTokenIncludingWithContextPath() {
        for (String contextPath : new String[]{"", "/takeout"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET", contextPath + "/images/default-product.svg");
            request.setContextPath(contextPath);

            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        }
        verifyNoInteractions(jwtUtils, userService, merchantService, riderService);
    }

    @Test
    void defaultProductImageExemptionDoesNotExposeOtherImagesOrWrites() {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                new MockHttpServletRequest("POST", "/images/default-product.svg"),
                new MockHttpServletRequest("GET", "/images/private.svg"),
                new MockHttpServletRequest("GET", "/images/default-product.svg/private")}) {
            assertThrows(AuthException.class,
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        }
        verifyNoInteractions(jwtUtils, userService, merchantService, riderService);
    }

    private MockHttpServletRequest mappedRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        // Direct interceptor tests provide the attribute that MVC supplies before preHandle.
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
        return request;
    }

    private Claims claims(Long id, String role) {
        Claims claims = Jwts.claims();
        claims.put("id", id);
        claims.put("role", role);
        return claims;
    }
}
