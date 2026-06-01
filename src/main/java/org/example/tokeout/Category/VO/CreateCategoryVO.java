package org.example.tokeout.Category.VO;

import lombok.Data;

@Data
public class CreateCategoryVO {
    private Long id;
    private String categoryName;
    private String statusDesc; // 贴心字段：状态的中文描述
}
