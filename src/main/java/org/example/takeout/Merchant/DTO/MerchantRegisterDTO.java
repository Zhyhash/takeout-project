package org.example.takeout.Merchant.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.takeout.Common.CustomAnnotation.PasswordMatchable;
import org.example.takeout.Common.CustomAnnotation.PasswordMatches;
import org.example.takeout.Common.CustomAnnotation.UniquePhone;

@Data
@PasswordMatches
public class MerchantRegisterDTO implements PasswordMatchable {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 1, max = 50, message = "用户名长度必须在1到50个字符之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度必须在8到20位之间")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    @Size(min = 8, max = 20, message = "确认密码长度必须在8到20位之间")
    private String confirmPassword;

    @NotBlank(message = "商家名称不能为空")
    @Size(min = 1, max = 255, message = "商家名称长度必须在1到255个字符之间")
    private String merchantName;

    @Size(max = 255, message = "地址长度不能超过255个字符")
    private String address;

    @NotBlank(message = "手机号不能为空")
    @Size(max = 20, message = "手机号码长度不能超过20个字符")
    @UniquePhone(targetTable = "merchant", message = "该商家手机号已被注册")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$", message = "手机格式不正确，请重新输入")
    private String phone;

    @Size(max = 255, message = "图片URL长度不能超过255个字符")
    private String pictureURL;

    @Size(max = 255, message = "店铺简介长度不能超过255个字符")
    private String description;
}
