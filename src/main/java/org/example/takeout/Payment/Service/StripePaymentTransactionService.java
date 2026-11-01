package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Mapper.StripePaymentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Short local transactions. No Stripe network calls belong in this service. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripePaymentTransactionService {
    private final StripePaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment prepare(Long orderId, Long userId, long amountMinor) {
        Order order = requireUserOrder(orderId, userId);
        requirePayable(order);
        if (StripePaymentService.toCnyMinor(order.getTotalAmount()) != amountMinor) {
            throw invalid("订单金额发生变化，请刷新订单");
        }
        OrderStripePayment candidate = new OrderStripePayment();
        candidate.setOrderId(orderId);
        candidate.setAmountMinor(amountMinor);
        candidate.setCurrency("cny");
        candidate.setIdempotencyKey(UUID.randomUUID().toString());
        paymentMapper.insertIfAbsent(candidate);
        OrderStripePayment payment = paymentMapper.findByOrderIdForUpdate(orderId);
        if (!Objects.equals(payment.getAmountMinor(), amountMinor) || !"cny".equals(payment.getCurrency())) {
            throw invalid("订单与已有支付金额不一致");
        }
        return payment;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment attach(Long orderId, StripePaymentSnapshot snapshot) {
        OrderStripePayment payment = paymentMapper.findByOrderIdForUpdate(orderId);
        if (payment == null) throw invalid("订单支付记录不存在");
        validate(payment, snapshot);
        if (payment.getPaymentIntentId() != null
                && !Objects.equals(payment.getPaymentIntentId(), snapshot.id())) {
            throw invalid("订单已关联其他支付请求");
        }
        if (!"succeeded".equals(payment.getStripeStatus()) && !"canceled".equals(payment.getStripeStatus())) {
            paymentMapper.updateSnapshot(orderId, snapshot.id(), snapshot.status());
        }
        return paymentMapper.findByOrderId(orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment apply(StripePaymentSnapshot snapshot, String eventId, String eventType) {
        OrderStripePayment payment = paymentMapper.findByIntentIdForUpdate(snapshot.id());
        // A valid signature alone does not associate an arbitrary Stripe payment with an order.
        if (payment == null) return null;
        validate(payment, snapshot);
        if (eventId != null) {
            if (paymentMapper.eventExists(eventId) != 0) return payment;
            paymentMapper.insertEvent(eventId, snapshot.id(), eventType);
        }
        if (!"succeeded".equals(payment.getStripeStatus())
                && !("canceled".equals(payment.getStripeStatus()) && !"succeeded".equals(snapshot.status()))) {
            paymentMapper.updateSnapshot(payment.getOrderId(), snapshot.id(), snapshot.status());
        }
        if ("succeeded".equals(snapshot.status())) {
            Order order = orderMapper.selectById(payment.getOrderId());
            // Reuse the existing order CAS and afterCommit notification. A canceled/expired order stays unchanged.
            orderService.confirmStripePayment(order.getId(), order.getUserId());
        }
        return paymentMapper.findByOrderId(payment.getOrderId());
    }

    public OrderStripePayment findForUser(Long orderId, Long userId) {
        requireUserOrder(orderId, userId);
        return paymentMapper.findByOrderId(orderId);
    }

    public Order requireUserOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw invalid("订单不存在或无权操作");
        }
        return order;
    }

    public static void requirePayable(Order order) {
        if (!OrderStatusEnum.WAIT_PAY.getCode().equals(order.getStatus())
                || order.getCreateTime() == null
                || !order.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(30))) {
            throw invalid("订单不存在、已过期或当前状态不可支付");
        }
    }

    private static void validate(OrderStripePayment payment, StripePaymentSnapshot snapshot) {
        if (snapshot == null || snapshot.livemode() || snapshot.id() == null
                || !snapshot.id().startsWith("pi_") || !"cny".equals(snapshot.currency())
                || !Objects.equals(payment.getAmountMinor(), snapshot.amount())
                || !Objects.equals(payment.getOrderId().toString(), snapshot.orderReference())
                || (payment.getPaymentIntentId() != null
                    && !Objects.equals(payment.getPaymentIntentId(), snapshot.id()))
                || ("succeeded".equals(snapshot.status()) && !Objects.equals(snapshot.amountReceived(), payment.getAmountMinor()))) {
            throw invalid("Stripe 测试支付结果与订单不一致");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCodeEnum.BUSINESS_ERROR, message);
    }
}
