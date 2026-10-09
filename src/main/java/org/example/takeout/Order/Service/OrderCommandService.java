package org.example.takeout.Order.Service;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderCommandService {
    private final OrderMapper orderMapper;

    //商家模块
    public void acceptOrderByMerchant(@NonNull Long orderId, Long merchantId){
        int rows = orderMapper.updateOrderStatusToPreparing(
                orderId,
                merchantId,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.PREPARING.getCode()
        );

        if(rows != 1){
            Order order = orderMapper.selectById(orderId);
            if (order == null || !merchantId.equals(order.getMerchantId())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "订单不存在或无权操作该订单");
            }

            if (OrderStatusEnum.PREPARING.getCode().equals(order.getStatus())) {
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于已支付状态，当前状态码：" + order.getStatus());
        }
    }

    public MarkReadyResult markReadyByMerchant(@NonNull Long orderId, Long merchantId){
        int rows = orderMapper.updateOrderStatusToReady(
                orderId,
                merchantId,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode()
        );
        Order order = orderMapper.selectById(orderId);
        if (rows == 1) {
            return new MarkReadyResult( true,order);
        }

        if (order == null || !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在或无权操作该订单"
            );
        }

        if (OrderStatusEnum.READY.getCode().equals(order.getStatus())) {
            return new MarkReadyResult(false,order);
        }

        throw new BusinessException(
                ResultCodeEnum.BUSINESS_ERROR,
                "订单未处于制作中状态，当前状态码：" + order.getStatus()
        );
    }

    //配送任务模块
    public void updateOrderStatusToDelivering(Long orderId){
        int i = orderMapper.updateOrderStatusToDelivering(orderId,
                OrderStatusEnum.READY.getCode(), OrderStatusEnum.DELIVERING.getCode());
        if (i != 1) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于待配送状态，当前状态码：" + order.getStatus());
        }
    }

    public void updateOrderStatusToDelivered(Long orderId){
        int i = orderMapper.updateOrderStatusToDelivered(orderId,
                OrderStatusEnum.DELIVERING.getCode(), OrderStatusEnum.DELIVERED.getCode());
        if (i != 1) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于配送中状态，当前状态码：" + order.getStatus());
        }
    }

    public void assertDelivering(Long orderId) {
        assertOrderStatus(
                orderId,
                OrderStatusEnum.DELIVERING.getCode(),
                "配送中"
        );
    }

    private void assertOrderStatus(Long orderId, Integer expectedStatus, String expectedStatusDescription) {
        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在"
            );
        }

        if (!expectedStatus.equals(order.getStatus())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于" + expectedStatusDescription + "状态，当前状态码：" + order.getStatus()
            );
        }
    }


    public void assertOrderReachedDeliveryCompletion(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
        }
        if (!OrderStatusEnum.DELIVERED.getCode().equals(order.getStatus())
                && !OrderStatusEnum.FINISHED.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于已送达或已完成状态，当前状态码：" + order.getStatus());
        }
    }
}
