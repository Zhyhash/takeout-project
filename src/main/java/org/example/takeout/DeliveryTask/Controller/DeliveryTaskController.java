package org.example.takeout.DeliveryTask.Controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/rider/delivery-tasks")
public class DeliveryTaskController {
    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @GetMapping("/available")
    public Result<?> getAvailableTasks(
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(deliveryTaskService.getAvailableRiderTaskPage(pageNum, pageSize));
    }

    @GetMapping("/current")
    public Result<List<RiderTaskListVO>> getCurrentTasks() {
        return Result.success(deliveryTaskService.getRiderTaskList());
    }

    @GetMapping("/{taskId}")
    public Result<RiderDeliveryDetailVO> getTaskDetail(@PathVariable @Min(1) Long taskId) {
        return Result.success(deliveryTaskService.getRiderDeliveryDetail(taskId));
    }

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
