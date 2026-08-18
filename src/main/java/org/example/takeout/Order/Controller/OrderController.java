package org.example.takeout.Order.Controller;

import com.github.pagehelper.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/order")
public class OrderController {
    @Autowired
    private OrderService orderService;

    /**
     * 创建订单
     * */
    @PostMapping
    public Result<?> create(@RequestBody @Valid CreateOrderDTO createOrderDTO){
        CreateOrderVO createOrderVO = orderService.createOrder(createOrderDTO);
        return Result.success(createOrderVO);
    }

    /**
     * 分页查询当前用户的订单列表
     * @param pageNum 页码（从1开始）
     * @param pageSize 每页数量
     * */
    @GetMapping
    public Result<?> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(100) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize){
        PageInfo<OrderVO> orderPageInfo = orderService.listOrders(pageNum, pageSize);
        return Result.success(orderPageInfo);
    }

    /**
     * 查询单个订单详情
     * @param id 订单ID
     * */
    @GetMapping("{id}")
    public Result<?> getById(@PathVariable @Positive Long id){
        OrderDetailVO orderDetailVO = orderService.searchOrderDetailById(id);
        return Result.success(orderDetailVO);
    }

    /**
     * 取消订单（仅支持未支付状态）
     * @param id 订单ID
     * */
    @PatchMapping("{id}/cancel")
    public Result<?> cancel(@PathVariable @Positive Long id){
        orderService.cancelOrder(id);
        return Result.success("取消成功");
    }

    /**
     * 支付订单
     * @param id 订单ID
     * */
    @PatchMapping("{id}/pay")
    public Result<?> pay(@PathVariable @Positive Long id){
        orderService.payOrder(id);
        return Result.success("支付成功");
    }

    /**
     * 确认收货
     * @param id 订单ID
     * */
    @PatchMapping("{id}/confirm")
    public Result<?> confirm(@PathVariable @Positive Long id){
        orderService.checkedOrder(id);
        return Result.success("确认收货成功");
    }
}
