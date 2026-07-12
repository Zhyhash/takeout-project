package org.example.takeout.Merchant.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MerchantLoginDTO {
    @NotNull(message = "用户名不能为空")
    private String userName;
    @NotNull(message = "密码不能为空")
    private String password;
}
