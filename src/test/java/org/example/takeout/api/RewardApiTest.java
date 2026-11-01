package org.example.takeout.api;

import org.example.takeout.Settlement.Service.RewardQueryService;
import org.example.takeout.Settlement.VO.RewardSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RewardApiTest extends AbstractMockMvcApiTest {
    @MockitoBean
    private RewardQueryService rewardQueryService;

    @Autowired
    RewardApiTest(org.example.takeout.Common.Utils.MyScurity.JWTUtils jwtUtils,
                  WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
    }

    @Test
    void riderCanOnlyReadOwnRewardsAndClientAccountIdIsIgnored() throws Exception {
        when(rewardQueryService.getRiderRewards(1, 10)).thenReturn(new RewardSummaryVO());

        mockMvc.perform(get("/rider/rewards")
                        .param("riderId", "999")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").isNotEmpty());

        verify(rewardQueryService).getRiderRewards(1, 10);
        verify(rewardQueryService, never()).getMerchantRewards(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void merchantCanOnlyReadOwnRewardsAndUserCannotReadThem() throws Exception {
        when(rewardQueryService.getMerchantRewards(1, 10)).thenReturn(new RewardSummaryVO());

        mockMvc.perform(get("/merchant/rewards")
                        .param("merchantId", "999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
        verify(rewardQueryService).getMerchantRewards(1, 10);

        clearInvocations(rewardQueryService);
        mockMvc.perform(get("/merchant/rewards").header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UNAUTHORIZED));
        verifyNoInteractions(rewardQueryService);
    }

    @Test
    void riderCannotReadMerchantRewards() throws Exception {
        mockMvc.perform(get("/merchant/rewards").header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UNAUTHORIZED));

        verify(rewardQueryService, never()).getMerchantRewards(1, 10);
    }
}
