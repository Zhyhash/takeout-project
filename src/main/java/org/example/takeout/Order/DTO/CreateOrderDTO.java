package org.example.takeout.Order.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;


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
}
