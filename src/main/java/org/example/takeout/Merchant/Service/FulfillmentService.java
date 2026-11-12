package org.example.takeout.Merchant.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Order.Service.OrderCommandService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 商家接单与出餐的履约编排服务。
 * 负责调用订单状态命令，在出餐完成时创建或校验待接取的配送任务，
 * 并在事务提交后按 MySQL 当前状态对账可接任务 GEO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final OrderCommandService orderCommandService;
    private final MerchantMapper merchantMapper;
    private final DeliveryTaskService deliveryTaskService;
    private final DeliveryTaskGeoService deliveryTaskGeoService;

    public void acceptOrder(@NonNull Long orderId) {

        Long merchantId = MerchantContextHolder.getMerchantId();

        orderCommandService.acceptOrderByMerchant(orderId, merchantId);
    }

    @Transactional(rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public void completePreparation(Long orderId){
        Long merchantId = MerchantContextHolder.getMerchantId();

        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商户不存在");
        }

        MarkReadyResult result =
                orderCommandService.markReadyByMerchant(orderId, merchantId);
        if (!result.changed()) {
            deliveryTaskService.assertWaitingDeliveryTask(orderId);
            return;
        }

        Long waitingTaskId = deliveryTaskService.createWaitingTask(result.order(), merchant);
        registerAvailableTaskGeoAfterCommit(waitingTaskId, merchant);
    }

    /**
     * 事务提交后按任务的 MySQL 状态对账 GEO。
     * 任务是否可接以数据库状态为准，GEO 只是附近查询的索引，
     * 因此写入失败只记录日志，不回滚已经提交的出餐结果。
     */
    private void registerAvailableTaskGeoAfterCommit(Long taskId, Merchant merchant) {
        if (merchant.getLongitude() == null || merchant.getLatitude() == null) {
            log.warn("商家 {} 未配置经纬度，配送任务 {} 未写入可接任务 GEO",
                    merchant.getId(), taskId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    deliveryTaskGeoService.reconcileAvailableTaskGeo(taskId);
                } catch (RuntimeException e) {
                    log.error("配送任务 {} GEO 提交后对账失败，等待定时任务兜底",
                            taskId, e);
                }
            }
        });
    }

    public void rejectOrder(@NonNull Long orderId) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        orderCommandService.rejectOrderByMerchant(orderId,merchantId);
    }

}
