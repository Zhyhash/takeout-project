package org.example.takeout.api;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "delivery.geo.reconciliation.enabled=false")
class RiderLocationApiTest extends AbstractMockMvcApiTest {

    private static final long RIDER_ID = 301L;
    private static final double LONGITUDE = 121.4737;
    private static final double LATITUDE = 31.2304;

    @MockitoBean
    private RiderLocationService riderLocationService;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @Autowired
    RiderLocationApiTest(JWTUtils jwtUtils, WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
    }

    @Test
    void riderCanUpdateOwnLocationWithoutProvidingRiderId() throws Exception {
        givenActiveRider();

        mockMvc.perform(put("/rider/location")
                        .header("Authorization", riderBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"longitude":121.4737,"latitude":31.2304,"riderId":302}
                                """))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(successMessage())
                .andExpect(jsonPath("$.data").value("success"));

        verify(riderLocationService).updateLocation(RIDER_ID, LONGITUDE, LATITUDE);
        verify(riderLocationService, never()).updateLocation(302L, LONGITUDE, LATITUDE);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void riderCanReadOnlyOwnLocation() throws Exception {
        givenActiveRider();
        when(riderLocationService.getRiderLocation(RIDER_ID))
                .thenReturn(Optional.of(new org.springframework.data.geo.Point(LONGITUDE, LATITUDE)));

        mockMvc.perform(get("/rider/location").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.longitude").value(LONGITUDE))
                .andExpect(jsonPath("$.data.latitude").value(LATITUDE));

        verify(riderLocationService).getRiderLocation(RIDER_ID);
        verify(riderLocationService, never()).getRiderLocation(302L);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void readingMissingLocationReturnsNullData() throws Exception {
        givenActiveRider();
        when(riderLocationService.getRiderLocation(RIDER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/rider/location").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        verify(riderLocationService).getRiderLocation(RIDER_ID);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void riderCanDeleteOwnLocationRepeatedly() throws Exception {
        givenActiveRider();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/rider/location").header("Authorization", riderBearer()))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(SUCCESS))
                    .andExpect(jsonPath("$.data").value("success"));
        }

        verify(riderLocationService, times(2)).removeRiderLocation(RIDER_ID);
        verify(riderLocationService, never()).removeRiderLocation(302L);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void locationEndpointsRequireRiderAuthentication() throws Exception {
        mockMvc.perform(get("/rider/location"))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
        mockMvc.perform(put("/rider/location")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLocationJson()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
        mockMvc.perform(delete("/rider/location")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));

        verifyNoInteractions(riderLocationService);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void disabledRiderCannotReadWriteOrDeleteLocation() throws Exception {
        when(riderService.requireActiveRiderId())
                .thenThrow(new AuthException("骑手账号已禁用或不存在，请重新登录"));

        mockMvc.perform(get("/rider/location").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
        mockMvc.perform(put("/rider/location")
                        .header("Authorization", riderBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLocationJson()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
        mockMvc.perform(delete("/rider/location").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));

        verifyNoInteractions(riderLocationService);
        assertNull(RiderContextHolder.getRiderId());
    }

    @Test
    void updateRejectsNullMissingAndMalformedBodies() throws Exception {
        givenActiveRider();
        String[] invalidBodies = {
                "null",
                "{}",
                "{\"longitude\":null,\"latitude\":31.2304}",
                "{\"longitude\":\"not-a-number\",\"latitude\":31.2304}",
                "{invalid"
        };

        for (String invalidBody : invalidBodies) {
            mockMvc.perform(put("/rider/location")
                            .header("Authorization", riderBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(PARAM_ERROR));
        }

        verifyNoInteractions(riderLocationService);
    }

    @Test
    void updateRejectsCoordinatesOutsideRedisGeoRange() throws Exception {
        givenActiveRider();
        String[] invalidCoordinates = {
                "{\"longitude\":180.000001,\"latitude\":31.2304}",
                "{\"longitude\":121.4737,\"latitude\":85.05112879}",
                "{\"longitude\":121.4737,\"latitude\":-85.05112879}"
        };

        for (String body : invalidCoordinates) {
            mockMvc.perform(put("/rider/location")
                            .header("Authorization", riderBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(PARAM_ERROR));
        }

        verifyNoInteractions(riderLocationService);
    }

    @Test
    void updateAcceptsInclusiveRedisGeoCoordinateBoundaries() throws Exception {
        givenActiveRider();
        String[][] coordinates = {
                {"180", "85.05112878"},
                {"-180", "-85.05112878"}
        };

        for (String[] coordinate : coordinates) {
            mockMvc.perform(put("/rider/location")
                            .header("Authorization", riderBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"longitude\":" + coordinate[0]
                                    + ",\"latitude\":" + coordinate[1] + "}"))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(SUCCESS));
        }

        verify(riderLocationService).updateLocation(RIDER_ID, 180.0, 85.05112878);
        verify(riderLocationService).updateLocation(RIDER_ID, -180.0, -85.05112878);
    }

    @Test
    void geoFailureReturnsFixedSafeMessage() throws Exception {
        givenActiveRider();
        when(riderLocationService.getRiderLocation(RIDER_ID))
                .thenThrow(new RedisGeoOperationException("redis-host-password-and-stacktrace"));

        String response = mockMvc.perform(get("/rider/location")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(ResultCodeEnum.SERVER_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("位置服务暂时不可用，请稍后重试"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("redis-host-password-and-stacktrace")
                .doesNotContain("RedisGeoOperationException")
                .doesNotContain("stacktrace");
        verify(riderLocationService).getRiderLocation(RIDER_ID);
    }

    private void givenActiveRider() {
        when(riderService.requireActiveRiderId()).thenReturn(RIDER_ID);
    }

    private String validLocationJson() {
        return "{\"longitude\":" + LONGITUDE + ",\"latitude\":" + LATITUDE + "}";
    }
}
