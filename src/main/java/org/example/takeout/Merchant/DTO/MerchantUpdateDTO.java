package org.example.takeout.Merchant.DTO;

import lombok.Data;

import java.time.LocalTime;

@Data
//NOTE:商家修改自己的店铺名称、头像、简介和营业时间
public class MerchantUpdateDTO {
    private String merchantName;
    private String address;
    private String phone;
    private String email;
    private String description;
    private String pictureURL;

    private LocalTime openingTime;
    private LocalTime closingTime;
}
