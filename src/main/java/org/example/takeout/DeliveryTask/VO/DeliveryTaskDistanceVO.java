package org.example.takeout.DeliveryTask.VO;

import lombok.Data;
import lombok.NonNull;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;

/**
 * 附近可接配送任务及其距离。
 * taskId 沿用 GEO 成员的字符串形式；距离统一保存为千米数值，供业务排序使用。
 */
@Data
public class DeliveryTaskDistanceVO {
    @NonNull
    private String taskId;
    private double distance;

    public DeliveryTaskDistanceVO(@NonNull String taskId, Distance distance) {
        this.taskId = taskId;
        this.distance = distance.in(Metrics.KILOMETERS).getValue();
    }
}
