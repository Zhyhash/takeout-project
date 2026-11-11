package org.example.takeout.api;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "delivery.geo.reconciliation.enabled=false")
class RiderWebAssetsApiTest extends AbstractMockMvcApiTest {

    private static final List<String> RIDER_WEB_ASSETS = List.of(
            "/rider-web/index.html",
            "/rider-web/styles.css",
            "/rider-web/app.mjs",
            "/rider-web/api.mjs",
            "/rider-web/location-tracker.mjs",
            "/rider-web/distance-display.mjs"
    );

    @MockitoBean
    private RiderLocationService riderLocationService;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @Autowired
    RiderWebAssetsApiTest(JWTUtils jwtUtils, WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
    }

    @Test
    void anonymousCanReadOnlyTheKnownRiderWebAssetsWithGetOrHead() throws Exception {
        for (String path : RIDER_WEB_ASSETS) {
            MvcResult getResult = mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn();
            assertFalse(getResult.getResponse().getContentAsString().isBlank(), path);

            MvcResult headResult = mockMvc.perform(head(path))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(0, headResult.getResponse().getContentAsByteArray().length, path);

            if (path.endsWith(".mjs")) {
                assertJavaScriptContentType(getResult, path);
                assertJavaScriptContentType(headResult, path);
            }
        }
    }

    @Test
    void otherMethodsDoNotMakeRiderWebAssetsPublic() throws Exception {
        for (String path : RIDER_WEB_ASSETS) {
            for (HttpMethod method : List.of(
                    HttpMethod.POST,
                    HttpMethod.PUT,
                    HttpMethod.PATCH,
                    HttpMethod.DELETE,
                    HttpMethod.OPTIONS)) {
                mockMvc.perform(request(method, path))
                        .andExpect(status().isOk())
                        .andExpect(resultCode(UNAUTHORIZED));
            }
        }
    }

    @Test
    void unknownRiderWebAssetIsNotPublic() throws Exception {
        mockMvc.perform(get("/rider-web/not-listed.mjs"))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void riderApiEndpointsRemainRiderOnly() throws Exception {
        List<String> riderApiPaths = List.of(
                "/rider/location",
                "/rider/delivery-tasks/available"
        );

        for (String path : riderApiPaths) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(UNAUTHORIZED));
            mockMvc.perform(get(path).header("Authorization", userBearer()))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(UNAUTHORIZED));
        }

        when(riderService.requireActiveRiderId()).thenReturn(301L);
        when(riderLocationService.getRiderLocation(301L)).thenReturn(Optional.empty());
        when(deliveryTaskService.getAvailableRiderTask(5.0)).thenReturn(List.of());

        mockMvc.perform(get("/rider/location").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
        mockMvc.perform(get("/rider/delivery-tasks/available").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    private static void assertJavaScriptContentType(MvcResult result, String path) {
        String contentType = result.getResponse().getContentType();
        assertTrue(contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("javascript"),
                () -> path + " should be served with a JavaScript content type, got " + contentType);
    }
}
