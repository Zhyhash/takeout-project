package org.example.tokeout.User.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data

public class RegisterDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 1,max = 50,message = "用户名长度不能低于1/超过50")
    private String username;

    @Size(min = 8,max = 20,message = "密码长度不能低于8/超过20")
    @NotBlank(message = "密码不能为空")
    private String password;

    //TODO:这里是将处理逻辑放到service里面处理，但是记得更改成自定义注解
    @Size(min = 8,max = 20,message = "确认密码长度不能低于8/超过20")
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$",message ="手机格式不正确，请重新输入" )
    private String phone;
}

