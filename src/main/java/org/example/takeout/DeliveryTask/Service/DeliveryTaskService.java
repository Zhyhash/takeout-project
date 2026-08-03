package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class DeliveryTaskService {
    @Autowired
    private DeliveryTaskMapper deliveryTaskMapper;
    @Autowired
    private OrderMapper orderMapper;

    @Transactional(rollbackFor = Exception.class)
    public void claimTask(Long taskId){
        Long riderId = RiderContextHolder.getRiderId();

        //抢配送任务
        LambdaUpdateWrapper<DeliveryTask> deliveryTaskLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        deliveryTaskLambdaUpdateWrapper.set(DeliveryTask::getRiderId, riderId).
                set(DeliveryTask::getStatus, DeliveryTaskEnums.DELIVERING.getCode()).
                set(DeliveryTask::getAcceptedTime, LocalDateTime.now()).
                eq(DeliveryTask::getStatus,DeliveryTaskEnums.WAIT_ASSIGN.getCode()).
                eq(DeliveryTask::getId, taskId).isNull(DeliveryTask::getRiderId);
        int update = deliveryTaskMapper.update(null,deliveryTaskLambdaUpdateWrapper);

        DeliveryTask deliveryTask = deliveryTaskMapper.selectById(taskId);
        if (update != 1) {

            if (deliveryTask == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "派送任务不存在");
            }

            if (DeliveryTaskEnums.DELIVERING.getCode().equals(deliveryTask.getStatus())
                    && Objects.equals(deliveryTask.getRiderId(), riderId)) {
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "手慢了，订单已被其余骑手抢到了");
        }

        //修改订单状态
        int i = orderMapper.updateOrderStatusToDelivering(deliveryTask.getOrderId(),
                OrderStatusEnum.READY.getCode(), OrderStatusEnum.DELIVERING.getCode());
        if (i != 1) {
            Order order = orderMapper.selectById(deliveryTask.getOrderId());
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }

            if (OrderStatusEnum.DELIVERING.getCode().equals(order.getStatus())) {
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单当前状态为：" + order.getStatus() + "，无法配送");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeDelivery(Long taskId){
        Long riderId = RiderContextHolder.getRiderId();

        //骑手确认送达
        LambdaUpdateWrapper<DeliveryTask> deliveryTaskLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        deliveryTaskLambdaUpdateWrapper.set(DeliveryTask::getStatus, DeliveryTaskEnums.COMPLETED.getCode()).
                set(DeliveryTask::getDeliveredTime, LocalDateTime.now()).
                eq(DeliveryTask::getId, taskId).
                eq(DeliveryTask::getRiderId, riderId).
                eq(DeliveryTask::getStatus, DeliveryTaskEnums.DELIVERING.getCode());
        int update = deliveryTaskMapper.update(null, deliveryTaskLambdaUpdateWrapper);

        DeliveryTask deliveryTask = deliveryTaskMapper.selectById(taskId);
        if (update != 1) {
            if (deliveryTask == null || !Objects.equals(deliveryTask.getRiderId(), riderId)) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "配送任务不存在或不属于当前骑手");
            }

            if (!DeliveryTaskEnums.COMPLETED.getCode().equals(deliveryTask.getStatus())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "配送任务当前状态为：" + deliveryTask.getStatus() + "，无法确认送达");
            }
        }

        //修改订单状态
        int i = orderMapper.updateOrderStatusToDelivered(deliveryTask.getOrderId(),
                OrderStatusEnum.DELIVERING.getCode(), OrderStatusEnum.DELIVERED.getCode());
        if (i != 1) {
            Order order = orderMapper.selectById(deliveryTask.getOrderId());
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
            }

            if (OrderStatusEnum.DELIVERED.getCode().equals(order.getStatus())) {
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单当前状态为：" + order.getStatus() + "，无法确认送达");
        }
    }

}
