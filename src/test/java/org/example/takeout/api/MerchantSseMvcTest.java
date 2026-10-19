package org.example.takeout.api;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.GlobalExceptionHandle;
import org.example.takeout.Common.Interceptor.JwtInterceptor;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.Controller.MerchantControllerSold;
import org.example.takeout.Merchant.Service.MerchantOrderViewService;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.User.Service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MerchantSseMvcTest {

    private final JWTUtils jwtUtils = mock(JWTUtils.class);
    private final MerchantService merchantService = mock(MerchantService.class);
    private final UserService userService = mock(UserService.class);
    private final RiderService riderService = mock(RiderService.class);
    private final MerchantOrderViewService orderViewService = mock(MerchantOrderViewService.class);
    private final MerchantSseService sseService = spy(new MerchantSseService());
    private final List<MvcResult> openStreams = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtils, userService, merchantService, riderService);
        MerchantControllerSold controller = new MerchantControllerSold(merchantService, orderViewService, sseService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandle())
                .build();
    }

    @AfterEach
    void closeStreamsAndClearContexts() {
        for (MvcResult stream : openStreams) {
            stream.getRequest().getAsyncContext().complete();
        }
        MerchantContextHolder.clear();
        UserContextHolder.clear();
        RiderContextHolder.clear();
    }

    @Test
    void merchantSubscriptionStartsRealSseAndClearsContextOnInitialAsyncDispatch() throws Exception {
        when(jwtUtils.parseToken("merchant-token")).thenReturn(claims(201L, AuthRole.MERCHANT));
        when(merchantService.requireActiveMerchantId()).thenAnswer(invocation -> {
            assertEquals(201L, MerchantContextHolder.getMerchantId());
            return 201L;
        });

        MvcResult stream = subscribe("merchant-token");

        // The stream is still open: afterCompletion has not run. The initial request thread must already be clean.
        assertTrue(stream.getRequest().isAsyncStarted());
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(UserContextHolder.getUserId());
        assertNull(RiderContextHolder.getRiderId());
        assertEquals("event:CONNECTED\ndata:connected\n\n", stream.getResponse().getContentAsString());
        verify(merchantService).requireActiveMerchantId();
        verify(sseService).connect(201L);
        verifyNoInteractions(userService, riderService, orderViewService);

        sseService.notifyNewOrder(201L);
        sseService.heartbeat();

        assertEquals("event:CONNECTED\ndata:connected\n\nevent:NEW_ORDER\ndata:refresh\n\n:heartbeat\n\n",
                stream.getResponse().getContentAsString());
    }

    @Test
    void simultaneousMerchantSubscriptionsReceiveOnlyTheirOwnNewOrders() throws Exception {
        when(jwtUtils.parseToken("first-merchant")).thenReturn(claims(201L, AuthRole.MERCHANT));
        when(jwtUtils.parseToken("second-merchant")).thenReturn(claims(202L, AuthRole.MERCHANT));
        when(merchantService.requireActiveMerchantId()).thenAnswer(invocation -> MerchantContextHolder.getMerchantId());
        MvcResult first = subscribe("first-merchant");
        MvcResult second = subscribe("second-merchant");

        sseService.notifyNewOrder(201L);

        assertEquals("event:CONNECTED\ndata:connected\n\nevent:NEW_ORDER\ndata:refresh\n\n",
                first.getResponse().getContentAsString());
        assertEquals("event:CONNECTED\ndata:connected\n\n", second.getResponse().getContentAsString());
        assertNull(MerchantContextHolder.getMerchantId());
    }

    @Test
    void missingTokenIsRejectedBeforeStartingSse() throws Exception {
        mockMvc.perform(get("/merchant/events")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(jwtUtils, merchantService, userService, riderService, sseService);
        assertNull(MerchantContextHolder.getMerchantId());
    }

    @Test
    void invalidTokenIsRejectedBeforeStartingSse() throws Exception {
        when(jwtUtils.parseToken("invalid-token")).thenThrow(new MalformedJwtException("invalid"));

        mockMvc.perform(get("/merchant/events").header("Authorization", "Bearer invalid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(merchantService, userService, riderService, sseService);
        assertNull(MerchantContextHolder.getMerchantId());
    }

    @ParameterizedTest
    @ValueSource(strings = {AuthRole.USER, AuthRole.RIDER})
    void nonMerchantRolesCannotSubscribe(String role) throws Exception {
        when(jwtUtils.parseToken("wrong-role")).thenReturn(claims(101L, role));

        mockMvc.perform(get("/merchant/events").header("Authorization", "Bearer wrong-role")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(merchantService, userService, riderService, sseService);
        assertNull(MerchantContextHolder.getMerchantId());
        assertNull(UserContextHolder.getUserId());
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void deletedMerchantCannotSubscribeWithAnExistingToken() throws Exception {
        when(jwtUtils.parseToken("deleted-merchant")).thenReturn(claims(201L, AuthRole.MERCHANT));
        when(merchantService.requireActiveMerchantId()).thenThrow(new AuthException("商家账号不存在"));

        mockMvc.perform(get("/merchant/events").header("Authorization", "Bearer deleted-merchant")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value(401));

        verify(merchantService).requireActiveMerchantId();
        verifyNoInteractions(userService, riderService, sseService);
        assertNull(MerchantContextHolder.getMerchantId());
    }

    private MvcResult subscribe(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/merchant/events")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        openStreams.add(result);
        return result;
    }

    private static Claims claims(Long id, String role) {
        Claims claims = Jwts.claims();
        claims.put("id", id);
        claims.put("role", role);
        return claims;
    }
}
