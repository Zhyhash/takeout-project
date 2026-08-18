package org.example.takeout.Merchant.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Enums.MerchantOrderListType;
import org.example.takeout.Merchant.Service.MerchantOrderQueryService;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 商家登录与信息管理控制器
 * 职责：接收HTTP请求，调用Service层处理业务，返回响应结果
 */
@RestController
@Validated
@RequestMapping("/merchant")
public class MerchantControllerSold {
    
    @Autowired
    private MerchantService merchantService;
    @Autowired
    private MerchantOrderQueryService merchantOrderQueryService;
    @PostMapping("/register")
    public Result<?> register(@Valid  @RequestBody MerchantRegisterDTO dto){
        merchantService.register(dto);
        return Result.success("success");
    }

    /**
     * 商家登录
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody @Valid MerchantLoginDTO dto) {
        loginVO loginVO = merchantService.login(dto);
        return Result.success(loginVO);
    }

    /**
     * 更新商家信息
     */
    @PutMapping("/info")
    public Result<?> updateMerchantInfo(@RequestBody @Valid MerchantUpdateDTO dto) {
        MerchantUpdateVO merchantUpdateVO = merchantService.updateMerchant(dto);
        return Result.success(merchantUpdateVO);
    }

    /**
     * 更新商家营业状态
     */
    @PatchMapping("/info")
    public Result<?> updateMerchantStatus(@RequestParam @Min(0) @Max(1) Integer status) {
        merchantService.updateStatus(status);
        return Result.success("success");
    }

    /**
     * 分页查看当前商家的待接取或已接取订单。
     */
    @GetMapping("/orders")
    public Result<PageInfo<MerchantOrderListVO>> listOrders(
            @RequestParam(defaultValue = "PENDING") MerchantOrderListType type,
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(merchantOrderQueryService.listOrders(type, pageNum, pageSize));
    }

    /**
     * 查看当前商家名下的单个订单详情。
     */
    @GetMapping("/orders/{orderId}")
    public Result<MerchantOrderDetailVO> getOrderDetail(@PathVariable @Min(1) Long orderId) {
        return Result.success(merchantOrderQueryService.getOrderDetail(orderId));
    }

    @PatchMapping("/orders/{orderId}/accept")
    public Result<?> acceptOrder(@PathVariable @Min(1) Long orderId) {
        merchantService.acceptOrder(orderId);
        return Result.success("success");
    }

    @PatchMapping("/orders/{orderId}/ready")
    public Result<?> completePreparation(@PathVariable @Min(1) Long orderId) {
        merchantService.completePreparation(orderId);
        return Result.success("success");
    }
}
