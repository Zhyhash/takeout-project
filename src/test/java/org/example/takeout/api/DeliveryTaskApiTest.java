package org.example.takeout.api;

import com.github.pagehelper.PageInfo;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryTaskApiTest extends AbstractMockMvcApiTest {

    @Test
    void availableTasksReturnsPage() throws Exception {
        RiderTaskListVO task = taskListVO(501L, 0, "等待骑手取餐");
        when(deliveryTaskService.getAvailableRiderTaskPage(1, 10))
                .thenReturn(new PageInfo<>(List.of(task)));

        mockMvc.perform(get("/rider/delivery-tasks/available")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.list[0].taskId").value(501L))
                .andExpect(jsonPath("$.data.list[0].deliveryReward").value(5))
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].statusDesc").value("等待骑手取餐"));

        verify(deliveryTaskService).getAvailableRiderTaskPage(1, 10);
    }

    @Test
    void currentTasksReturnsOnlyRiderActiveTasks() throws Exception {
        when(deliveryTaskService.getRiderTaskList())
                .thenReturn(List.of(taskListVO(501L, 1, "骑手正在配送")));

        mockMvc.perform(get("/rider/delivery-tasks/current")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data[0].taskId").value(501L))
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].statusDesc").value("骑手正在配送"));

        verify(deliveryTaskService).getRiderTaskList();
    }

    @Test
    void taskDetailReturnsRiderTask() throws Exception {
        RiderDeliveryDetailVO detail = new RiderDeliveryDetailVO();
        detail.setOrderId(601L);
        detail.setDeliveryReward(BigDecimal.valueOf(5));
        detail.setStatus(2);
        detail.setStatusDesc("骑手已经完成配送");
        when(deliveryTaskService.getRiderDeliveryDetail(501L)).thenReturn(detail);

        mockMvc.perform(get("/rider/delivery-tasks/501")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.orderId").value(601L))
                .andExpect(jsonPath("$.data.deliveryReward").value(5))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusDesc").value("骑手已经完成配送"));

        verify(deliveryTaskService).getRiderDeliveryDetail(501L);
    }

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

    @Test
    void taskQueryRejectsUserToken() throws Exception {
        mockMvc.perform(get("/rider/delivery-tasks/current")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void availableTaskQueryRejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/rider/delivery-tasks/available")
                        .param("pageNum", "0")
                        .param("pageSize", "101")
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    private RiderTaskListVO taskListVO(Long taskId, Integer status, String statusDesc) {
        RiderTaskListVO task = new RiderTaskListVO();
        task.setTaskId(taskId);
        task.setMerchantName("Test Shop");
        task.setDeliveryReward(BigDecimal.valueOf(5));
        task.setStatus(status);
        task.setStatusDesc(statusDesc);
        return task;
    }
}
