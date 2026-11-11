package org.example.takeout.Order.DTO;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class CreateOrderDTO {

    @NotBlank(message = "下单请求标识不能为空")
    @Size(max = 64, message = "下单请求标识最大为64")
    private String requestId;

    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 20, message = "收货人姓名长度不能超过20个字符")
    private String receiverName;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$")
    private String receiverPhone;
    @NotBlank(message = "收货地址不能为空")
    @Size(max = 255, message = "收货地址长度不能超过255个字符")
    private String receiverAddress;
    @Size(max = 200,message = "备注最大为200")
    private String remark;

    /**
     * 收货地点纬度，代表固定收货位置而非用户实时定位。
     */
    @NotNull(message = "收货地点纬度不能为空")
    @DecimalMin(value = "-90", message = "纬度范围必须在-90到90之间")
    @DecimalMax(value = "90", message = "纬度范围必须在-90到90之间")
    private BigDecimal latitude;

    /**
     * 收货地点经度，代表固定收货位置而非用户实时定位。
     */
    @NotNull(message = "收货地点经度不能为空")
    @DecimalMin(value = "-180", message = "经度范围必须在-180到180之间")
    @DecimalMax(value = "180", message = "经度范围必须在-180到180之间")
    private BigDecimal longitude;
}
