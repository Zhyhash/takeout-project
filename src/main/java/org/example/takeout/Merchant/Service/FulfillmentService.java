package org.example.takeout.Merchant.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Order.Service.OrderCommandService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家接单与出餐的履约编排服务。
 * 负责调用订单状态命令，并在出餐完成时创建或校验待接取的配送任务。
 */
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final OrderCommandService orderCommandService;
    private final MerchantMapper merchantMapper;
    private final DeliveryTaskService deliveryTaskService;

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

        deliveryTaskService.createWaitingTask(result.order(), merchant);
    }

    public void rejectOrder(@NonNull Long orderId) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        orderCommandService.rejectOrderByMerchant(orderId,merchantId);
    }
}
