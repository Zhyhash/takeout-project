package org.example.takeout.User.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginDTO {
    @Size(max = 50,message = "用户名长度不能超过50")
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Size(min = 8,max = 20,message = "密码长度不能低于8/超过20")
    @NotBlank(message = "密码不能为空")
    private String password;
}
