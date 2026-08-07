package org.example.takeout.Merchant.DTO;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
// NOTE: 商家修改自己的店铺名称、头像、简介和营业时间
public class MerchantUpdateDTO {
    @Size(max = 255, message = "商家名称长度不能超过255个字符")
    private String merchantName;

    @Size(max = 255, message = "地址长度不能超过255个字符")
    private String address;

    @Size(max = 20, message = "手机号码长度不能超过20个字符")
    private String phone;

    @Size(max = 255, message = "店铺简介长度不能超过255个字符")
    private String description;

    @Size(max = 255, message = "图片URL长度不能超过255个字符")
    private String pictureURL;

    private LocalTime openingTime;
    private LocalTime closingTime;
}
