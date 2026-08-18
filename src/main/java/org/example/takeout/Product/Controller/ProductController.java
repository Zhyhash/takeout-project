package org.example.takeout.Product.Controller;

import jakarta.validation.constraints.Positive;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Product.Service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
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
    public Result<Void> restoreProduct(@PathVariable("id") @Positive Long id) {
        productService.restoreProduct(id);
        return Result.success(null);
    }
}
