package org.example.takeout.Merchant.VO;

import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class MerchantDetailVO {
    private Long id;
    private String merchantName;
    private String pictureURL;
    private String description;
    private String address;
    private LocalTime openingTime;
    private LocalTime closingTime;
    private Integer status;
    private List<MerchantDetailCategoryVO> categories;
}
