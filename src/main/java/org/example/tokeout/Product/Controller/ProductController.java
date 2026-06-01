package org.example.tokeout.Product.Controller;

import jakarta.validation.constraints.Min;
import org.example.tokeout.Common.Result.Result;
import org.example.tokeout.Product.Service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/merchant")
public class ProductController {
    @Autowired
    private ProductService productService;
    @PostMapping("/restore/{id}")
    public Result<Void> restoreProduct(@PathVariable("id") @Min(0) Long id) {
        // 这里的 id 怎么校验？
        productService.restoreProduct(id);
        return Result.success(null);
    }
}
