package org.example.takeout.Product.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/category/products")
public class MerchantProductController {
    @Autowired
    private ProductService productService;

    @PostMapping
    public Result<?> create(@RequestBody @Valid CreateProductDTO createProductDTO) {
        MerchantProductVO productVO = productService.createProduct(createProductDTO);
        return Result.success(productVO);
    }

    @GetMapping
    @Validated
    public Result<?> list(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Max(100)int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId) {
        PageInfo<MerchantProductVO> productPage = productService.listProducts(pageNum, pageSize, status, categoryId);
        return Result.success(productPage);
    }

    @PatchMapping("/{id}/on-shelf")
    public Result<?> onShelf(@PathVariable("id") @Min(0) Long id) {
        MerchantProductVO productVO = productService.onShelf(id);
        return Result.success(productVO);
    }

    @PatchMapping("/{id}/off-shelf")
    public Result<?> offShelf(@PathVariable("id") @Min(0) Long id) {
        MerchantProductVO productVO = productService.offShelf(id);
        return Result.success(productVO);
    }
}
