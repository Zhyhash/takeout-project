package org.example.takeout.DeliveryTask.Controller;

import jakarta.validation.constraints.Min;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/rider/delivery-tasks")
public class DeliveryTaskController {
    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @PatchMapping("/{taskId}/claim")
    public Result<?> claimTask(@PathVariable @Min(1) Long taskId) {
        deliveryTaskService.claimTask(taskId);
        return Result.success("抢单成功");
    }

    @PatchMapping("/{taskId}/complete")
    public Result<?> completeDelivery(@PathVariable @Min(1) Long taskId) {
        deliveryTaskService.completeDelivery(taskId);
        return Result.success("确认送达成功");
    }
}
