package org.example.tokeout.Merchant.VO;

import lombok.Data;

import java.time.LocalTime;
@Data
public class MerchantUpdateVO {
    private String merchantName;
    private String address;
    private String phone;
    private String email;
    private String description;
    private String pictureURL;
    private Integer status;

    private LocalTime openingTime;
    private LocalTime closingTime;
}
