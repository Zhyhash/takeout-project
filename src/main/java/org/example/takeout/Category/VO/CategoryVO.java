package org.example.takeout.Category.VO;

import lombok.Data;

@Data
public class CategoryVO {
    private Long id;
    private String categoryName;     // 分类名，前端拿来做 label
}