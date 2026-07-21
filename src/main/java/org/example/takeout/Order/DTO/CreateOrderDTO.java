package org.example.takeout.Order.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class CreateOrderDTO {

    @NotBlank(message = "用户名不能为空")
    private String receiverName;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$")
    private String receiverPhone;
    @NotBlank(message = "取餐地址不能为空")
    private String receiverAddress;
    @Size(max = 200,message = "备注最大为200")
    private String remark;
}
