package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskConverter;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 配送任务服务。
 * 负责待接配送任务的创建与校验、骑手任务查询、抢单及确认送达，并协调订单配送状态的一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTaskService {
    private static final int FALLBACK_TASK_LIMIT = 500;

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final OrderCommandService  orderCommandService;
    private final OrderService orderService;
    private final DeliveryTaskConverter deliveryTaskConverter;
    private final RiderService  riderService;
    private final DeliveryTaskGeoService deliveryTaskGeoService;
    private final RiderLocationService riderLocationService;

    @Transactional(rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
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
                orderService.assertDelivering(deliveryTask.getOrderId());
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "手慢了，订单已被其余骑手抢到了");
        }

        //修改订单状态
        orderCommandService.updateOrderStatusToDelivering(deliveryTask.getOrderId());
        removeAvailableTaskGeoAfterCommit(taskId);
    }

    private void removeAvailableTaskGeoAfterCommit(Long taskId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    deliveryTaskGeoService.removeAvailableTask(taskId);
                } catch (RuntimeException e) {
                    log.error("配送任务 {} 移除可接任务 GEO 失败", taskId, e);
                    try {
                        deliveryTaskGeoService.reconcileAvailableTaskGeo(taskId);
                    } catch (RuntimeException ex) {
                        log.error("配送任务 {} GEO 即时补偿失败，等待定时任务兜底",
                                taskId, ex);
                    }
                }
            }
        });
    }

    @Transactional(rollbackFor = Exception.class,
        isolation =  Isolation.READ_COMMITTED)
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

            orderService.assertOrderReachedDeliveryCompletion(deliveryTask.getOrderId());
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


    /**
     * 骑手抢单前查询可接取的任务。
     * 以骑手最后上报的位置为圆心，GEO 返回的最多 100 个最近候选按距离升序排在前面，
     * 其余任务按任务 ID 升序；不再分页。GEO 命中的任务携带 pickupDistanceKm（千米），
     * 未命中或降级列表中该字段为 null。骑手位置或任务GEO查询失败时按任务 ID 倒序
     * 查询最新的 500 条 MySQL 待接任务；骑手位置尚未上报仍提示先上传位置。
     */
    public List<RiderTaskListVO> getAvailableRiderTask(double preferredRadiusKm){
        if (!Double.isFinite(preferredRadiusKm) || preferredRadiusKm <= 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "查询半径必须大于0");
        }

        Long riderId = riderService.requireActiveRiderId();
        Optional<Point> riderLocation;
        try {
            riderLocation = riderLocationService.getRiderLocation(riderId);
        } catch (RedisGeoOperationException e) {
            log.warn("骑手位置 GEO 查询失败，按任务 ID 返回普通任务列表", e);
            return getFallbackAvailableTasks();
        }
        if (riderLocation.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "请先上传骑手位置");
        }
        Point point = riderLocation.get();

        Map<Long, Double> distanceMap = new HashMap<>();

        try {
            List<DeliveryTaskDistanceVO> nearbyTasks = deliveryTaskGeoService.
                    findNearbyTasks(point.getX(), point.getY(), preferredRadiusKm);

            if (nearbyTasks != null) {
                for (DeliveryTaskDistanceVO task : nearbyTasks) {
                    if (task == null || !StringUtils.hasText(task.getTaskId())) {
                        log.warn("忽略无效的配送任务 GEO 成员：{}", task);
                        continue;
                    }

                    long taskId;
                    try {
                        taskId = Long.parseLong(task.getTaskId());
                    } catch (NumberFormatException e) {
                        log.warn("忽略非数字配送任务 GEO 成员：{}", task.getTaskId());
                        continue;
                    }
                    double distanceKm = task.getDistance();
                    if (taskId <= 0 || !Double.isFinite(distanceKm) || distanceKm < 0) {
                        log.warn("忽略无效配送任务 GEO 数据：taskId={}, distanceKm={}",
                                task.getTaskId(), distanceKm);
                        continue;
                    }
                    distanceMap.put(taskId, distanceKm);
                }
            } else {
                log.warn("配送任务 GEO 查询未返回结果，按任务 ID 返回普通任务列表");
            }
        } catch (RedisGeoOperationException e) {
            log.warn("配送任务 GEO 查询失败，降级为普通任务列表", e);
        }

        if (distanceMap.isEmpty()) {
            return getFallbackAvailableTasks();
        }

        List<DeliveryTask> deliveryTasks =
                deliveryTaskMapper.selectList(
                        Wrappers.<DeliveryTask>lambdaQuery()
                                .eq(DeliveryTask::getStatus,
                                        DeliveryTaskEnums.WAIT_ASSIGN.getCode())
                                .isNull(DeliveryTask::getRiderId)
                                .orderByAsc(DeliveryTask::getId)
                );

        deliveryTasks.sort((a, b) -> {

            boolean aNearby = distanceMap.containsKey(a.getId());
            boolean bNearby = distanceMap.containsKey(b.getId());

            // 第一层：附近订单优先
            if (aNearby != bNearby) {
                return aNearby ? -1 : 1;
            }

            // 第二层：都是附近订单，比较距离
            if (aNearby) {
                double aDistance = distanceMap.get(a.getId());
                double bDistance = distanceMap.get(b.getId());

                int result = Double.compare(aDistance, bDistance);

                if (result != 0) {
                    return result;
                }
            }

            return Long.compare(a.getId(), b.getId());
        });
        return deliveryTasks.stream()
                .peek(this::warnIfTaskInfoMissing)
                .map(task -> {
                    RiderTaskListVO vo =
                            deliveryTaskConverter.toRiderTaskListVO(task);

                    vo.setPickupDistanceKm(distanceMap.get(vo.getTaskId()));

                    return vo;
                })
                .collect(Collectors.toList());
    }

    private List<RiderTaskListVO> getFallbackAvailableTasks() {
        List<DeliveryTask> deliveryTasks = deliveryTaskMapper.selectList(
                Wrappers.<DeliveryTask>lambdaQuery()
                        .eq(DeliveryTask::getStatus, DeliveryTaskEnums.WAIT_ASSIGN.getCode())
                        .isNull(DeliveryTask::getRiderId)
                        .orderByDesc(DeliveryTask::getId)
                        .last("LIMIT " + FALLBACK_TASK_LIMIT)
        );
        return deliveryTasks.stream()
                .peek(this::warnIfTaskInfoMissing)
                .map(deliveryTaskConverter::toRiderTaskListVO)
                .collect(Collectors.toList());
    }


    /**
     * 创建待接取配送任务，返回任务主键。
     * 取货坐标随任务一起快照；商家未配置坐标时快照为空，任务不会写入可接任务 GEO。
     */
    public Long createWaitingTask(Order order, Merchant merchant) {
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(order.getId());
        task.setMerchantName(order.getMerchantName());
        task.setReceiverName(order.getReceiverName());
        task.setReceiverPhone(order.getReceiverPhone());
        task.setReceiverAddress(order.getReceiverAddress());
        task.setMerchantAddress(merchant.getAddress());
        task.setMerchantPhone(merchant.getPhone());
        // 取货坐标快照：GEO 写入与后续排查都以任务上的坐标为准，
        // 商家之后修改坐标不影响已经创建的任务。
        task.setPickupLongitude(merchant.getLongitude());
        task.setPickupLatitude(merchant.getLatitude());
        task.setDeliveryReward(order.getDeliveryFee());
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        int insertedRows = deliveryTaskMapper.insert(task);
        if (insertedRows != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "配送任务创建失败");
        }
        return task.getId();
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
