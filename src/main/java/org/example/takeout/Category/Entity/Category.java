package org.example.takeout.Category.Entity;

import lombok.Data;

@Data
public class Category {
    private Long id;
    private Long merchantId;
    private String categoryName;
    private Integer status;
    private Integer isDefault;
    // NOTE V2: 如果分类需要暴露给外部系统或做导入导出，考虑增加业务code字段
}
