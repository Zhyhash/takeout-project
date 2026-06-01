package org.example.tokeout.Merchant.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MerchantDetailVO {
    private Long merchantId;
    private String merchantName;
    private String picture;
    private String description;
    private String address;
    private LocalDateTime businessHours;
    private Integer status;
    private List<CategoryVO> categories;
}
