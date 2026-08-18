package org.example.takeout.Product.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @Positive Long categoryId) {
        PageInfo<MerchantProductVO> productPage = productService.listProducts(pageNum, pageSize, status, categoryId);
        return Result.success(productPage);
    }

    @GetMapping("/{id}")
    public Result<ProductVO> getDetail(@PathVariable("id") @Positive Long id) {
        return Result.success(productService.getProductDetail(id));
    }

    @PutMapping("/{id}")
    public Result<MerchantProductVO> update(
            @PathVariable("id") @Positive Long id,
            @RequestBody @Valid UpdateProductDTO updateProductDTO) {
        return Result.success(productService.updateProduct(id, updateProductDTO));
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable("id") @Positive Long id) {
        productService.deleteProduct(id);
        return Result.success("删除成功");
    }

    @PatchMapping("/{id}/on-shelf")
    public Result<?> onShelf(@PathVariable("id") @Positive Long id) {
        MerchantProductVO productVO = productService.onShelf(id);
        return Result.success(productVO);
    }

    @PatchMapping("/{id}/off-shelf")
    public Result<?> offShelf(@PathVariable("id") @Positive Long id) {
        MerchantProductVO productVO = productService.offShelf(id);
        return Result.success(productVO);
    }
}
