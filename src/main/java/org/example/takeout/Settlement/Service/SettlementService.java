package org.example.takeout.Settlement.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Settlement.Entity.IncomeRecord;
import org.example.takeout.Settlement.Enums.BeneficiaryTypeEnum;
import org.example.takeout.Settlement.Enums.IncomeRecordTypeEnum;
import org.example.takeout.Settlement.Mapper.IncomeRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 订单签收与双方内部收益结算，共用一个本地数据库事务。 */
@Service
@RequiredArgsConstructor
public class SettlementService {
    private final OrderMapper orderMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final IncomeRecordMapper incomeRecordMapper;
    private final MerchantMapper merchantMapper;
    private final RiderMapper riderMapper;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void finishOrder(Long orderId, Long userId) {
        Order order = requireDeliveredOrder(orderId, userId);
        DeliveryTask task = requireCompletedTask(orderId);

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int changed = orderMapper.updateOrderStatusToFinished(orderId, userId,
                OrderStatusEnum.DELIVERED.getCode(), OrderStatusEnum.FINISHED.getCode(), now);
        // 只有本次条件更新成功才允许入账；失败后不再读取订单判断状态。
        if (changed != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可确认");
        }

        BigDecimal merchantIncome = calculateMerchantIncome(order);
        BigDecimal riderReward = task.getDeliveryReward();
        insertIncome(orderId, BeneficiaryTypeEnum.MERCHANT, order.getMerchantId(),
                IncomeRecordTypeEnum.ORDER_INCOME, merchantIncome, now);
        insertIncome(orderId, BeneficiaryTypeEnum.RIDER, task.getRiderId(),
                IncomeRecordTypeEnum.DELIVERY_REWARD, riderReward, now);

        // 原子 UPDATE 同时累加余额和累计收入，并校验账户存在和余额上限，固定商家、骑手顺序获取写锁。
        if (merchantMapper.creditSettledIncome(order.getMerchantId(), merchantIncome) != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家余额入账失败");
        }
        if (riderMapper.creditSettledIncome(task.getRiderId(), riderReward) != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "骑手余额入账失败");
        }
    }

    private Order requireDeliveredOrder(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可确认");
        }
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId).eq(Order::getUserId, userId));
        // 重复确认直接拒绝；确认收货接口不承担历史账务审计职责。
        if (order == null || !OrderStatusEnum.DELIVERED.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可确认");
        }
        if (order.getMerchantId() == null || order.getMerchantId() <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单商家无效，无法结算");
        }
        return order;
    }

    private DeliveryTask requireCompletedTask(Long orderId) {
        DeliveryTask task = deliveryTaskMapper.selectOne(Wrappers.<DeliveryTask>lambdaQuery()
                .eq(DeliveryTask::getOrderId, orderId));
        if (task == null
                || !DeliveryTaskEnums.COMPLETED.getCode().equals(task.getStatus())
                || task.getRiderId() == null || task.getRiderId() <= 0
                || task.getAcceptedTime() == null || task.getDeliveredTime() == null
                || task.getDeliveredTime().isBefore(task.getAcceptedTime())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单配送任务未合法完成，无法结算");
        }
        return task;
    }

    /**
     * 订单金额在创建时由 total_amount = original_amount - discount_amount + delivery_fee
     * 一次算出且此后不可修改，各项非负由下单校验和流水表的 amount >= 0 约束兜底。
     * 结算前保留一次口径比对，防止人工改数或异常写路径导致按错误金额入账。
     */
    private BigDecimal calculateMerchantIncome(Order order) {
        BigDecimal goodsIncome = order.getOriginalAmount().subtract(order.getDiscountAmount());
        if (order.getTotalAmount().compareTo(goodsIncome.add(order.getDeliveryFee())) != 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单金额口径不一致，无法结算");
        }
        return goodsIncome;
    }

    private void insertIncome(Long orderId, BeneficiaryTypeEnum beneficiaryType, Long beneficiaryId,
                              IncomeRecordTypeEnum type, BigDecimal amount, LocalDateTime now) {
        IncomeRecord record = new IncomeRecord();
        record.setOrderId(orderId);
        record.setBeneficiaryType(beneficiaryType.getCode());
        record.setBeneficiaryId(beneficiaryId);
        record.setType(type.getCode());
        record.setAmount(amount);
        record.setCreateTime(now);
        // MyBatis-Plus 单条插入失败只会以数据库异常抛出并回滚事务，无需检查影响行数。
        incomeRecordMapper.insert(record);
    }
}
