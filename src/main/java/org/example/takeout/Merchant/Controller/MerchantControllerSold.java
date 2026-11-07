package org.example.takeout.Merchant.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Enums.MerchantOrderListType;
import org.example.takeout.Merchant.Service.FulfillmentService;
import org.example.takeout.Merchant.Service.MerchantAccountService;
import org.example.takeout.Merchant.Service.MerchantOrderViewService;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 商家登录与信息管理控制器
 * 职责：接收HTTP请求，调用Service层处理业务，返回响应结果
 */
@RestController
@Validated
@RequestMapping("/merchant")
@RequiredArgsConstructor
public class MerchantControllerSold {
    
    private final MerchantAccountService merchantAccountService;
    private final FulfillmentService fulfillmentService;
    private final MerchantOrderViewService merchantOrderViewService;
    private final MerchantSseService merchantSseService;

    @PostMapping("/register")
    public Result<?> register(@Valid  @RequestBody MerchantRegisterDTO dto){
        merchantAccountService.register(dto);
        return Result.success("success");
    }

    /**
     * 商家登录
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody @Valid MerchantLoginDTO dto) {
        loginVO loginVO = merchantAccountService.login(dto);
        return Result.success(loginVO);
    }

    /**
     * 更新商家信息
     */
    @PutMapping("/info")
    public Result<?> updateMerchantInfo(@RequestBody @Valid MerchantUpdateDTO dto) {
        MerchantUpdateVO merchantUpdateVO = merchantAccountService.updateMerchant(dto);
        return Result.success(merchantUpdateVO);
    }

    /**
     * 更新商家营业状态
     */
    @PatchMapping("/info")
    public Result<?> updateMerchantStatus(@RequestParam @Min(0) @Max(1) Integer status) {
        merchantAccountService.updateStatus(status);
        return Result.success("success");
    }

    /**
     * 按列表类型分页查看当前商家的待接单、处理中或商家流程已完成的订单。
     */
    @GetMapping("/orders")
    public Result<PageInfo<MerchantOrderListVO>> listOrders(
            @RequestParam(defaultValue = "PENDING") MerchantOrderListType type,
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(merchantOrderViewService.listOrders(type, pageNum, pageSize));
    }

    /**
     * 查询当前商家尚未查看的待接单订单 ID，用于首次打开页面或 SSE 重连后的补偿同步。
     */
    @GetMapping("/orders/unseen")
    public Result<List<Long>> listUnseenOrders() {
        return Result.success(merchantOrderViewService.listUnseenOrders());
    }

    @PatchMapping("/orders/seen")
    public Result<?> markOrdersSeen(
            @RequestBody List<Long> orderIds) {

        merchantOrderViewService.markOrdersSeen(orderIds);
        return Result.success("success");
    }

    /**
     * 查看当前商家名下的单个订单详情。
     */
    @GetMapping("/orders/{orderId}")
    public Result<MerchantOrderDetailVO> getOrderDetail(@PathVariable @Min(1) Long orderId) {
        return Result.success(merchantOrderViewService.getOrderDetail(orderId));
    }

    @PatchMapping("/orders/{orderId}/accept")
    public Result<?> acceptOrder(@PathVariable @Min(1) Long orderId) {
        fulfillmentService.acceptOrder(orderId);
        return Result.success("success");
    }

    /**
     * 商家拒单：本地模拟订单取消并归还库存；真实支付退款尚未支持时拒绝操作。
     */
    @PatchMapping("/orders/{orderId}/reject")
    public Result<?> rejectOrder(@PathVariable @Min(1) Long orderId) {
        fulfillmentService.rejectOrder(orderId);
        return Result.success("success");
    }

    @PatchMapping("/orders/{orderId}/ready")
    public Result<?> completePreparation(@PathVariable @Min(1) Long orderId) {
        fulfillmentService.completePreparation(orderId);
        return Result.success("success");
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long merchantId = MerchantContextHolder.getMerchantId();
        return merchantSseService.connect(merchantId);
    }
}
