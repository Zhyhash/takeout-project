package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.StripePaymentEventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 管理本地支付短事务：关联 PaymentIntent、记录支付结果和事件去重；不发起 Stripe 网络请求。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripePaymentTransactionService {
    private final OrderStripePaymentMapper orderPaymentMapper;
    private final StripePaymentEventMapper paymentEventMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /**
     * 为可支付订单创建或复用本地支付记录，并在行锁保护下校验已保存的金额和币种。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @param amountMinor 待支付金额，单位为分
     * @return 持有稳定幂等键的本地支付记录
     * @throws BusinessException 订单不属于该用户、已过期或金额与订单不一致时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment getOrCreateOrderPaymentRecord(Long orderId, Long userId, long amountMinor) {
        Order order = requireOrderOwnedByUser(orderId, userId);
        validateOrderCanBePaid(order);
        if (StripePaymentService.convertCnyYuanToFen(order.getTotalAmount()) != amountMinor) {
            throw paymentValidationError("订单金额发生变化，请刷新订单");
        }
        OrderStripePayment candidate = new OrderStripePayment();
        candidate.setOrderId(orderId);
        candidate.setAmountMinor(amountMinor);
        candidate.setCurrency("cny");
        candidate.setIdempotencyKey(UUID.randomUUID().toString());
        orderPaymentMapper.insertPaymentIfAbsent(candidate);
        OrderStripePayment payment = orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);
        if (!Objects.equals(payment.getAmountMinor(), amountMinor) || !"cny".equals(payment.getCurrency())) {
            throw paymentValidationError("订单与已有支付金额不一致");
        }
        return payment;
    }

    /**
     * 校验 Stripe 快照后将 PaymentIntent 关联到本地支付记录，并在需要时保存支付状态；此操作不推进订单状态。
     *
     * @param orderId 订单 ID
     * @param snapshot Stripe 返回的支付意图快照
     * @return 关联后的本地支付记录
     * @throws BusinessException 支付记录缺失、快照与订单不符或订单已关联其他支付意图时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment bindPaymentIntentToOrder(Long orderId, StripePaymentSnapshot snapshot) {
        OrderStripePayment payment = orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);
        if (payment == null) throw paymentValidationError("订单支付记录不存在");
        validatePaymentSnapshotMatchesOrder(payment, snapshot);
        if (payment.getPaymentIntentId() != null
                && !Objects.equals(payment.getPaymentIntentId(), snapshot.id())) {
            throw paymentValidationError("订单已关联其他支付请求");
        }
        if (!"succeeded".equals(payment.getStripeStatus()) && !"canceled".equals(payment.getStripeStatus())) {
            orderPaymentMapper.updatePaymentIntentAndStatus(orderId, snapshot.id(), snapshot.status());
        }
        return orderPaymentMapper.selectPaymentByOrderId(orderId);
    }

    /**
     * 保存主动查询获得的支付状态；无本地关联记录时返回 null，成功支付会在同一事务中尝试确认订单。
     * 已取消或已过期的订单不会因迟到的成功结果而复活，支付状态同步与订单状态推进处于同一事务。
     *
     * @param snapshot Stripe 查询返回的支付意图快照
     * @return 更新后的本地支付记录；未关联到本地记录时为 null
     * @throws BusinessException 快照与本地支付记录不匹配时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment syncPaymentStatusFromSnapshot(StripePaymentSnapshot snapshot) {
        return updatePaymentStatusAndConfirmOrderIfSucceeded(snapshot, null, null);
    }

    /**
     * 保存已验签的 Webhook 事件和支付状态；无本地关联记录时返回 null，重复事件不会重复推进状态。
     * 成功支付会在同一事务中尝试确认订单；已取消或已过期的订单不会复活，事件去重、支付状态与订单推进一并提交或回滚。
     *
     * @param event 已验签并解析的 Stripe 支付事件
     * @return 更新后的本地支付记录；未关联到本地记录时为 null
     * @throws BusinessException 事件中的支付快照与本地支付记录不匹配时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment recordWebhookPaymentEvent(StripePaymentEvent event) {
        return updatePaymentStatusAndConfirmOrderIfSucceeded(event.payment(), event.id(), event.type());
    }

    private OrderStripePayment updatePaymentStatusAndConfirmOrderIfSucceeded(
            StripePaymentSnapshot snapshot, String eventId, String eventType) {
        OrderStripePayment payment = orderPaymentMapper.selectPaymentByPaymentIntentIdForUpdate(snapshot.id());
        // A valid signature alone does not associate an arbitrary Stripe payment with an order.
        if (payment == null) return null;
        validatePaymentSnapshotMatchesOrder(payment, snapshot);
        if (eventId != null) {
            if (paymentEventMapper.countEventsByEventId(eventId) != 0)
                return payment;
            paymentEventMapper.insertPaymentEvent(eventId, snapshot.id(), eventType);
        }
        if (!"succeeded".equals(payment.getStripeStatus())
                && !("canceled".equals(payment.getStripeStatus()) && !"succeeded".equals(snapshot.status()))) {
            orderPaymentMapper.updatePaymentIntentAndStatus(payment.getOrderId(), snapshot.id(), snapshot.status());
        }
        if ("succeeded".equals(snapshot.status())) {
            Order order = orderMapper.selectById(payment.getOrderId());
            // Reuse the existing order CAS and afterCommit notification. A canceled/expired order stays unchanged.
            orderService.confirmStripePayment(order.getId(), order.getUserId());
        }
        return orderPaymentMapper.selectPaymentByOrderId(payment.getOrderId());
    }

    /**
     * 校验用户对订单的访问权后读取其支付记录。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @return 本地支付记录；订单尚无支付记录时为 null
     * @throws BusinessException 订单不存在或不属于该用户时抛出
     */
    public OrderStripePayment findOrderPaymentForUser(Long orderId, Long userId) {
        requireOrderOwnedByUser(orderId, userId);
        return orderPaymentMapper.selectPaymentByOrderId(orderId);
    }

    /**
     * 普通查询并校验订单归属，不加行锁。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @return 属于该用户的订单
     * @throws BusinessException 订单不存在或不属于该用户时抛出
     */
    public Order requireOrderOwnedByUser(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw paymentValidationError("订单不存在或无权操作");
        }
        return order;
    }

    /**
     * 校验订单仍处于待支付状态，且创建时间严格晚于当前时间三十分钟前。
     *
     * @param order 待校验的订单
     * @throws BusinessException 订单已过期、缺少创建时间或状态不可支付时抛出
     */
    public static void validateOrderCanBePaid(Order order) {
        if (!OrderStatusEnum.WAIT_PAY.getCode().equals(order.getStatus())
                || order.getCreateTime() == null
                || !order.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(30))) {
            throw paymentValidationError("订单不存在、已过期或当前状态不可支付");
        }
    }

    private static void validatePaymentSnapshotMatchesOrder(
            OrderStripePayment payment, StripePaymentSnapshot snapshot) {
        if (snapshot == null || snapshot.livemode() || snapshot.id() == null
                || !snapshot.id().startsWith("pi_") || !"cny".equals(snapshot.currency())
                || !Objects.equals(payment.getAmountMinor(), snapshot.amount())
                || !Objects.equals(payment.getOrderId().toString(), snapshot.orderReference())
                || (payment.getPaymentIntentId() != null
                    && !Objects.equals(payment.getPaymentIntentId(), snapshot.id()))
                || ("succeeded".equals(snapshot.status())
                    && !Objects.equals(snapshot.amountReceived(), payment.getAmountMinor()))) {
            throw paymentValidationError("Stripe 测试支付结果与订单不一致");
        }
    }

    private static BusinessException paymentValidationError(String message) {
        return new BusinessException(ResultCodeEnum.BUSINESS_ERROR, message);
    }
}
