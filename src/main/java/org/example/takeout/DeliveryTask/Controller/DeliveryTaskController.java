package org.example.takeout.DeliveryTask.Controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/rider/delivery-tasks")
@RequiredArgsConstructor
public class DeliveryTaskController {

    private static final String DEFAULT_RADIUS_KM = "5.0";

    private final DeliveryTaskService deliveryTaskService;

    /**
     * 查询可接取配送任务：以骑手当前坐标为圆心，附近任务按距离优先排序。
     * 半径缺省 5 公里；骑手位置或附近任务的 Redis GEO 查询异常时，服务层降级为按新任务 ID 查询普通列表。
     * 骑手尚未上报位置时仍返回业务错误，要求骑手先上传位置。
     */
    @GetMapping("/available")
    public Result<List<RiderTaskListVO>> getAvailableTasks(
            @RequestParam(defaultValue = DEFAULT_RADIUS_KM)
            @Positive(message = "查询半径必须大于0") double radiusKm) {
        // @Positive accepts positive infinity, while GEO radius must be finite.
        if (!Double.isFinite(radiusKm)) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "查询半径必须大于0");
        }
        return Result.success(deliveryTaskService.getAvailableRiderTask(radiusKm));
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
