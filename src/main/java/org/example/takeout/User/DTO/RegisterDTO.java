package org.example.takeout.User.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.takeout.Common.CustomAnnotation.PasswordMatchable;
import org.example.takeout.Common.CustomAnnotation.PasswordMatches;
import org.example.takeout.Common.CustomAnnotation.UniquePhone;

@Data
@PasswordMatches//检验密码是否正确
public class RegisterDTO implements PasswordMatchable {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 1,max = 50,message = "用户名长度不能低于1/超过50")
    private String username;

    @Size(min = 8,max = 20,message = "密码长度不能低于8/超过20")
    @NotBlank(message = "密码不能为空")
    private String password;

    @Size(min = 8,max = 20,message = "确认密码长度不能低于8/超过20")
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "手机号不能为空")
    @UniquePhone(targetTable = "user", message = "该商家手机号已被注册")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$",message ="手机格式不正确，请重新输入" )
    private String phone;
}

