package org.example.takeout.Rider.DTO;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateRiderLocationDTO {

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180", message = "经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间")
    @DecimalMax(value = "180", message = "经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间")
    private BigDecimal longitude;

    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-85.05112878", message = "经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间")
    @DecimalMax(value = "85.05112878", message = "经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间")
    private BigDecimal latitude;
}
