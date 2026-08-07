package org.example.takeout.Rider.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RiderLoginDTO {

    @NotBlank(message = "骑手名称不能为空")
    @Size(max = 50, message = "骑手名称长度不能超过50")
    private String name;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度必须在8到20位之间")
    private String password;
}
