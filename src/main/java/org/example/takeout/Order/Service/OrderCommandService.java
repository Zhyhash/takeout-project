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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<StripeRefundService> stripeRefundServiceProvider;
    @Qualifier("applicationTaskExecutor")
    private final TaskExecutor taskExecutor;
    
    @Value("${payment.stripe.enabled:false}")
    private boolean stripePaymentsEnabled;

    public OrderCommandService(OrderMapper orderMapper, OrderItemService orderItemService,
                               ObjectProvider<StripeRefundService> stripeRefundServiceProvider,
                               @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.orderMapper = orderMapper;
        this.orderItemService = orderItemService;
        this.stripeRefundServiceProvider = stripeRefundServiceProvider;
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
            // 状态仍是 PAID 却更新不到，只可能是接单 SQL 里的 10 分钟时限拦下了这次操作
            if (OrderStatusEnum.PAID.getCode().equals(order.getStatus())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "订单已超过商家接单时限，将被系统自动取消并退款");
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于已支付状态，当前状态码：" + order.getStatus());
        }
    }


    @Transactional(rollbackFor = Exception.class)
    public void rejectOrderByMerchant(@NonNull Long orderId, Long merchantId) {
        if (!stripePaymentsEnabled) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "Stripe 退款未启用，无法拒单");
        }

        StripeRefundService stripeRefundService = stripeRefundServiceProvider.getObject();
        RefundPreparation refundPreparation =
                stripeRefundService.prepareRefund(orderId, merchantId);
        settlePreparedRefund(stripeRefundService, orderId, refundPreparation);
    }

    /**
     * 商家超时未接单的系统取消入口。
     * <p>与商家拒单共用同一段「归还库存 + 事务提交后再请求 Stripe」的执行逻辑，
     * 差别只在触发条件（支付时间已超时）和没有商家身份可校验。</p>
     *
     * @return 本次是否真正把订单推进到退款流程；重复执行返回 false，不会再次退款
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrderByMerchantTimeout(@NonNull Long orderId) {
        if (!stripePaymentsEnabled) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "Stripe 退款未启用，无法执行超时取消");
        }

        StripeRefundService stripeRefundService = stripeRefundServiceProvider.getObject();
        RefundPreparation refundPreparation =
                stripeRefundService.prepareTimeoutRefund(orderId);
        return settlePreparedRefund(stripeRefundService, orderId, refundPreparation);
    }

    /** 退款准备成功后：归还库存并把 Stripe 调用挂到提交之后，两轮退款入口共用。 */
    private boolean settlePreparedRefund(StripeRefundService stripeRefundService,
                                         Long orderId,
                                         RefundPreparation refundPreparation) {
        if (!refundPreparation.newlyCreated()) {
            return false;
        }
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
        return true;
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
