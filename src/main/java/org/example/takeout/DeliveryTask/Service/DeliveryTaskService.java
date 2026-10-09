package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Domain.DeliveryFeeCalculator;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskConverter;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Rider.Service.RiderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTaskService {
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final OrderCommandService  orderCommandService;
    private final DeliveryTaskConverter deliveryTaskConverter;
    private final DeliveryFeeCalculator deliveryFeeCalculator;
    private final RiderService  riderService;

    @Transactional(rollbackFor = Exception.class)
    public void claimTask(Long taskId){
        Long riderId = riderService.requireActiveRiderId();

        //抢配送任务
        LambdaUpdateWrapper<DeliveryTask> deliveryTaskLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        deliveryTaskLambdaUpdateWrapper.set(DeliveryTask::getRiderId, riderId).
                set(DeliveryTask::getStatus, DeliveryTaskEnums.DELIVERING.getCode()).
                set(DeliveryTask::getAcceptedTime, LocalDateTime.now()).
                eq(DeliveryTask::getStatus,DeliveryTaskEnums.WAIT_ASSIGN.getCode()).
                eq(DeliveryTask::getId, taskId).isNull(DeliveryTask::getRiderId);
        int update = deliveryTaskMapper.update(null,deliveryTaskLambdaUpdateWrapper);

        LambdaQueryWrapper<DeliveryTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DeliveryTask::getId, taskId).last("for update");
        DeliveryTask deliveryTask = deliveryTaskMapper.selectOne(wrapper);

        if (update != 1) {

            if (deliveryTask == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "派送任务不存在");
            }

            if (DeliveryTaskEnums.DELIVERING.getCode().equals(deliveryTask.getStatus())
                    && Objects.equals(deliveryTask.getRiderId(), riderId)) {
                orderCommandService.assertDelivering(deliveryTask.getOrderId());
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "手慢了，订单已被其余骑手抢到了");
        }

        //修改订单状态
        orderCommandService.updateOrderStatusToDelivering(deliveryTask.getOrderId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeDelivery(Long taskId){
        Long riderId = riderService.requireActiveRiderId();

        //骑手确认送达
        LambdaUpdateWrapper<DeliveryTask> deliveryTaskLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        deliveryTaskLambdaUpdateWrapper.set(DeliveryTask::getStatus, DeliveryTaskEnums.COMPLETED.getCode()).
                set(DeliveryTask::getDeliveredTime, LocalDateTime.now()).
                eq(DeliveryTask::getId, taskId).
                eq(DeliveryTask::getRiderId, riderId).
                eq(DeliveryTask::getStatus, DeliveryTaskEnums.DELIVERING.getCode());
        int update = deliveryTaskMapper.update(null, deliveryTaskLambdaUpdateWrapper);

        LambdaQueryWrapper<DeliveryTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DeliveryTask::getId, taskId).last("for update");
        DeliveryTask deliveryTask = deliveryTaskMapper.selectOne(wrapper);
        if (update != 1) {
            if (deliveryTask == null || !Objects.equals(deliveryTask.getRiderId(), riderId)) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "配送任务不存在或不属于当前骑手");
            }

            if (!DeliveryTaskEnums.COMPLETED.getCode().equals(deliveryTask.getStatus())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "配送任务当前状态为：" + deliveryTask.getStatus() + "，无法确认送达");
            }

            orderCommandService.assertOrderReachedDeliveryCompletion(deliveryTask.getOrderId());
            return;
        }

        //修改订单状态
        orderCommandService.updateOrderStatusToDelivered(deliveryTask.getOrderId());
    }

    //NOTE：骑手查询目前派送任务
    public List<RiderTaskListVO> getRiderTaskList() {
        Long riderId = riderService.requireActiveRiderId();
        List<DeliveryTask> deliveryTasks = deliveryTaskMapper.selectList(Wrappers.<DeliveryTask>lambdaQuery().
                eq(DeliveryTask::getRiderId, riderId).
                eq(DeliveryTask::getStatus, DeliveryTaskEnums.DELIVERING.getCode()));
        if (deliveryTasks.isEmpty()) {
            return Collections.emptyList();
        }

        return deliveryTasks.stream()
                .peek(this::warnIfTaskInfoMissing)
                .map(deliveryTaskConverter::toRiderTaskListVO)
                .collect(Collectors.toList());
    }

    //NOTE：骑手查询某一个任务的详情（已经接取了任务的详情）
    public RiderDeliveryDetailVO getRiderDeliveryDetail(Long taskId) {
        Long riderId = riderService.requireActiveRiderId();
        DeliveryTask deliveryTask = deliveryTaskMapper.selectOne(Wrappers.lambdaQuery(DeliveryTask.class).
                eq(DeliveryTask::getId, taskId).
                eq(DeliveryTask::getRiderId, riderId));
        if (deliveryTask == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "配送任务不存在");
        }
        return deliveryTaskConverter.toRiderDeliveryDetailVO(deliveryTask);
    }

    //NOTE：骑手抢订单的时候，查询可接取的任务表
    public PageInfo<RiderTaskListVO> getAvailableRiderTaskPage(Integer page, Integer pageSize){
        riderService.requireActiveRiderId();
        PageHelper.startPage(page, pageSize);
        List<DeliveryTask> deliveryTasks = deliveryTaskMapper.selectList(Wrappers.<DeliveryTask>lambdaQuery().
                eq(DeliveryTask::getStatus, DeliveryTaskEnums.WAIT_ASSIGN.getCode()).
                isNull(DeliveryTask::getRiderId));
        PageInfo<DeliveryTask> pageInfo = new PageInfo<>(deliveryTasks);


        return pageInfo.convert(deliveryTaskConverter::toRiderTaskListVO);
    }


    public void createWaitingTask(Order order, Merchant merchant) {
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(order.getId());
        task.setMerchantName(order.getMerchantName());
        task.setReceiverName(order.getReceiverName());
        task.setReceiverPhone(order.getReceiverPhone());
        task.setReceiverAddress(order.getReceiverAddress());
        task.setMerchantAddress(merchant.getAddress());
        task.setMerchantPhone(merchant.getPhone());
        task.setDeliveryReward(deliveryFeeCalculator.calculateDeliveryReward());
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        int insertedRows = deliveryTaskMapper.insert(task);
        if (insertedRows != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "配送任务创建失败");
        }
    }

    public void assertWaitingDeliveryTask(Long orderId) {
        DeliveryTask task = findDeliveryTaskByOrderId(orderId);
        if (task == null
                || !DeliveryTaskEnums.WAIT_ASSIGN.getCode().equals(task.getStatus())
                || task.getRiderId() != null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单已出餐但配送任务不存在或状态不一致");
        }
    }

    private DeliveryTask findDeliveryTaskByOrderId(Long orderId) {
        return deliveryTaskMapper.selectOne(Wrappers.<DeliveryTask>lambdaQuery()
                .eq(DeliveryTask::getOrderId, orderId));
    }


    private void warnIfTaskInfoMissing(DeliveryTask deliveryTask) {
        if (!StringUtils.hasText(deliveryTask.getMerchantName())) {
            log.warn("配送任务Id:{},商家名字为空", deliveryTask.getId());
        }
        if (!StringUtils.hasText(deliveryTask.getMerchantAddress())) {
            log.warn("配送任务Id:{},商家地址为空", deliveryTask.getId());
        }
        if (!StringUtils.hasText(deliveryTask.getReceiverAddress())) {
            log.warn("配送任务Id:{},用户接收为空", deliveryTask.getId());
        }
    }
}
