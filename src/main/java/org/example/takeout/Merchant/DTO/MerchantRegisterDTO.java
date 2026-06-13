package org.example.takeout.Merchant.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.example.takeout.Common.CustomAnnotation.PasswordMatchable;
import org.example.takeout.Common.CustomAnnotation.PasswordMatches;
import org.example.takeout.Common.CustomAnnotation.UniquePhone;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;

@Data
@PasswordMatches

public class MerchantRegisterDTO implements PasswordMatchable {
    @NotNull(message = "用户名不能为空")
    private String username;
    @NotNull(message = "密码不能为空")
    private String password;
    @NotNull(message = "确认密码不能为空")
    private String confirmPassword;
    @NotNull
    private String merchantName;

    private String merchantAddress;
    @NotNull
    @UniquePhone(targetTable = "merchant", message = "该商家手机号已被注册")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$",message ="手机格式不正确，请重新输入" )
    private String phone;

    private String pictureURL;

    private String description;

    private Integer status= MerchantStatusEnum.BUSINESS_CLOSED.getCode();
}
