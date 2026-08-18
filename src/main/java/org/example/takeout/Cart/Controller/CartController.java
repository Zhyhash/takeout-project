package org.example.takeout.Cart.Controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.DeleteDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Cart.Service.CartService;
import org.example.takeout.Common.Result.Result;
import org.hibernate.validator.constraints.UniqueElements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@Validated
@RequestMapping("/cart/items")//直接上购物车商品资源，后续都不需要额外路径了
public class CartController {
    @Autowired
    private CartService cartService;
    //增加
    @PostMapping
    public Result<?> add(@RequestBody @Valid AddCartDTO addCartDTO) {
        return Result.success(cartService.add(addCartDTO));
    }
    @GetMapping
    public Result<?> list() {
        return Result.success(cartService.list());
    }
    @PatchMapping
    public Result<?> updateQuantity(@RequestBody @Valid UpdateCartDTO updateCartDTO) {
        return Result.success(cartService.update(updateCartDTO));
    }

    @DeleteMapping
    public Result<?> delete(
            @RequestParam
            @Size(min = 1, message = "购物车记录ID不能为空")
            @UniqueElements(message = "购物车记录ID不能重复")
            List<@Positive(message = "购物车记录ID必须为正数") Long> ids) {
        cartService.delete(new DeleteDTO(ids));
        return Result.success("success");
    }
    @DeleteMapping("/all")
    public Result<?> deleteAll() {
        cartService.clear();
        return Result.success("success");
    }
}
