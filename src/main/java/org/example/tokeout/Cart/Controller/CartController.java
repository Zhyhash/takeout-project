package org.example.tokeout.Cart.Controller;

import org.example.tokeout.Cart.DTO.AddCartDTO;
import org.example.tokeout.Cart.DTO.DeleteDTO;
import org.example.tokeout.Cart.DTO.SubCartDTO;
import org.example.tokeout.Cart.DTO.UpdateCartDTO;
import org.example.tokeout.Cart.Service.CartService;
import org.example.tokeout.Common.Result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/cart/items")//直接上购物车商品资源，后续都不需要额外路径了
public class CartController {
    @Autowired
    private CartService cartService;
    //增加
    @PostMapping
    public Result<?> add(@RequestBody AddCartDTO addCartDTO) {
        return Result.success(cartService.add(addCartDTO));
    }
    @GetMapping
    public Result<?> list() {
        return Result.success(cartService.list());
    }
    @PatchMapping
    public Result<?> updateQuantity(@RequestBody UpdateCartDTO updateCartDTO) {
        return Result.success(cartService.update(updateCartDTO));
    }

    @DeleteMapping
    public Result<?> delete(@RequestParam List<Long> ids) {
        cartService.delete(new DeleteDTO(ids));
        return Result.success("success");
    }
}
