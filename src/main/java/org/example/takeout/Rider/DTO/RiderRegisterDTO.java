package org.example.takeout.Rider.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.takeout.Common.CustomAnnotation.PasswordMatchable;
import org.example.takeout.Common.CustomAnnotation.PasswordMatches;
import org.example.takeout.Common.CustomAnnotation.UniquePhone;

@Data
@PasswordMatches
public class RiderRegisterDTO implements PasswordMatchable {

    @NotBlank(message = "骑手名称不能为空")
    @Size(max = 50, message = "骑手名称长度不能超过50")
    private String name;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度必须在8到20位之间")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    @Size(min = 8, max = 20, message = "确认密码长度必须在8到20位之间")
    private String confirmPassword;

    @NotBlank(message = "手机号不能为空")
    @UniquePhone(targetTable = "rider", message = "该骑手手机号已被注册")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$", message = "手机格式不正确，请重新输入")
    private String phone;
}
