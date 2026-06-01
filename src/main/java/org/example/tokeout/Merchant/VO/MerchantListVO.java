package org.example.tokeout.Merchant.VO;

import lombok.Data;
//NOTE:用户首页店铺卡片
@Data
public class MerchantListVO {
    private Long id;
    private String merchantName;
    private String pictureUrl;
    private String address;
    private String description;
    private Integer status;
}
