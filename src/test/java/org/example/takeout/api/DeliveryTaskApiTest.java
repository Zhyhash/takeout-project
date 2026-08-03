package org.example.takeout.api;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryTaskApiTest extends AbstractMockMvcApiTest {

    @Test
    void claimTaskReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/rider/delivery-tasks/501/claim")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("抢单成功"));

        verify(deliveryTaskService).claimTask(501L);
    }

    @Test
    void completeDeliveryReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/rider/delivery-tasks/501/complete")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("确认送达成功"));

        verify(deliveryTaskService).completeDelivery(501L);
    }

    @Test
    void deliveryTaskOperationRejectsUserToken() throws Exception {
        mockMvc.perform(patch("/rider/delivery-tasks/501/claim")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void deliveryTaskOperationRejectsInvalidTaskId() throws Exception {
        mockMvc.perform(patch("/rider/delivery-tasks/0/claim")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }
}
