package org.example.takeout.Merchant.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Merchant.Service.MerchantQueryService;
import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
// 1. 路由重构：明确这是【用户/顾客端】听属的【店铺/商家】查询接口
@RequestMapping("/api/customer/shops")
public class CustomerShopController {

    @Autowired
    private MerchantQueryService merchantQueryService;

    /**
     * 用户端-浏览/搜索商家列表
     */
    @GetMapping
    @Validated
    public Result<PageInfo<MerchantListVO>> listShops(
            // 将接收参数名从模糊的 merchant 改为更容易理解的 name 或 keyword
            @RequestParam(value = "name", required = false) String shopName,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Max(100) int size,
            // 用户端查询通常默认只能查“营业中”的商家，如果确实需要状态筛选再留着
            @RequestParam(required = false) Integer status
    ) {
        PageInfo<MerchantListVO> shopListPage = merchantQueryService.listMerchants(pageNum, size, shopName, status);
        return Result.success(shopListPage);
    }

    /**
     * 用户端-点击进店（获取商家详情及其分组商品列表）
     */
    // 2. 这里的入参变量名和路径变量保持一致，且重载方法名作了区分（避免重名方法阅读混淆）
    @GetMapping("/{shopId}")
    public Result<MerchantDetailVO> getShopDetail(@PathVariable Long shopId){
        MerchantDetailVO shopDetail = merchantQueryService.getMerchantDetailWithGroupedProducts(shopId);
        return Result.success(shopDetail);
    }
}
