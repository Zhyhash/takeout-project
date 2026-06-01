package org.example.tokeout.Category.VO;

import lombok.Data;

@Data
public class CategoryVO {
    private Long id;
    private String name;     // 分类名，前端拿来做 label
    private Integer sort;    // 排序号，业务需要就传，不需要就省
    // 注意：不暴露 id, merchantId, status 给前端
}