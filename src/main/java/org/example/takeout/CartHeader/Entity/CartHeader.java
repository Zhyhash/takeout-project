package org.example.takeout.CartHeader.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@TableName("cart_header")
@Data
public class CartHeader {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private Long merchantId;


}
