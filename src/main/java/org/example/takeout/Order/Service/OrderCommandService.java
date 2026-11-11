package org.example.takeout.Order.Service;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 订单履约状态命令服务。
 * 负责商家接单、拒单、备餐完成及配送中、已送达状态的流转和状态校验。
 */
@Service
@Slf4j
public class OrderCommandService {
    private final OrderMapper orderMapper;
    private final OrderItemService orderItemService;
    private final StripeRefundService stripeRefundService;
    @Qualifier("applicationTaskExecutor")
    private final TaskExecutor taskExecutor;
    
    @Value("${payment.stripe.enabled:false}")
    private boolean stripePaymentsEnabled;

    public OrderCommandService(OrderMapper orderMapper, OrderItemService orderItemService, StripeRefundService stripeRefundService, @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.orderMapper = orderMapper;
        this.orderItemService = orderItemService;
        this.stripeRefundService = stripeRefundService;
        this.taskExecutor = taskExecutor;
    }

    // 以下订单接单和备餐状态修改接口提供给商家领域使用。
    public void acceptOrderByMerchant(@NonNull Long orderId, Long merchantId) {
        int rows = orderMapper.updateOrderStatusToPreparing(
                orderId,
                merchantId,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.PREPARING.getCode()
        );

        if (rows != 1) {
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

    /**
     * 真实支付尚未实现退款，拒绝有 Stripe 支付记录或启用 Stripe 时的已付订单取消。
     * 未启用 Stripe 的既有本地模拟订单保留拒单及库存归还行为，不宣称发生退款。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectOrderByMerchant(@NonNull Long orderId, Long merchantId) {
        RefundPreparation refundPreparation =
                stripeRefundService.prepareRefund(orderId, merchantId);
        if (refundPreparation.newlyCreated()) {
            restoreOrderStock(orderId);

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                taskExecutor.execute(() -> {
                                    try {
                                        stripeRefundService.refundRejectedOrder(orderId);
                                    } catch (Exception e) {
                                        log.error("Stripe 退款执行失败，orderId={}", orderId, e);
                                    }
                                });
                            } catch (TaskRejectedException e) {
                                log.error("退款任务提交失败，orderId={}", orderId, e);
                            }
                        }
                    }
            );
        }
    }

    private void restoreOrderStock(Long orderId) {
        List<OrderItem> orderItems = orderItemService.findOrderItemsByOrderId(orderId);
        if (orderItems == null || orderItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单明细为空，无法拒单");
        }
        orderItemService.increaseStocksOrderedByProductId(orderItems);
    }

    public MarkReadyResult markReadyByMerchant(@NonNull Long orderId, Long merchantId) {
        int rows = orderMapper.updateOrderStatusToReady(
                orderId,
                merchantId,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode()
        );
        Order order = orderMapper.selectById(orderId);
        if (rows == 1) {
            return new MarkReadyResult(true, order);
        }

        if (order == null || !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或无权操作该订单");
        }

        if (OrderStatusEnum.READY.getCode().equals(order.getStatus())) {
            return new MarkReadyResult(false, order);
        }

        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                "订单未处于制作中状态，当前状态码：" + order.getStatus());
    }

    // 以下订单配送状态修改接口提供给配送任务领域使用。
    public void updateOrderStatusToDelivering(Long orderId) {
        int rows = orderMapper.updateOrderStatusToDelivering(orderId,
                OrderStatusEnum.READY.getCode(), OrderStatusEnum.DELIVERING.getCode());
        if (rows != 1) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于待配送状态，当前状态码：" + order.getStatus());
        }
    }

    public void updateOrderStatusToDelivered(Long orderId) {
        int rows = orderMapper.updateOrderStatusToDelivered(orderId,
                OrderStatusEnum.DELIVERING.getCode(), OrderStatusEnum.DELIVERED.getCode());
        if (rows != 1) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于配送中状态，当前状态码：" + order.getStatus());
        }
    }


}
