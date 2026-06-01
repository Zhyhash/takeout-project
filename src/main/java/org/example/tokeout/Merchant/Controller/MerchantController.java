package org.example.tokeout.Merchant.Controller;

import com.github.pagehelper.PageInfo;
import org.example.tokeout.Common.Result.Result;
import org.example.tokeout.Merchant.Service.MerchantQueryService;
import org.example.tokeout.Merchant.VO.MerchantDetailVO;
import org.example.tokeout.Merchant.VO.MerchantListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class MerchantController {
    @Autowired
    private MerchantQueryService merchantQueryService;
    @GetMapping
    public Result<?> listMerchants(
            // 接收查询条件（比如商户名），设为非必填
            @RequestParam(required = false) String merchant,
            // 分页参数设置默认值
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status
    ) {
        // 正常调用 service 层并返回结果
        PageInfo<MerchantListVO> merchantListVOPageInfo = merchantQueryService.listMerchants(pageNum, size, merchant, status);
        return Result.success(merchantListVOPageInfo);
    }

    @GetMapping("/{merchantId}")
    public Result<?> listMerchants(@PathVariable Long merchantId){
        MerchantDetailVO merchantDetailWithGroupedProducts = merchantQueryService.getMerchantDetailWithGroupedProducts(merchantId);
        return Result.success(merchantDetailWithGroupedProducts);
    }
}
