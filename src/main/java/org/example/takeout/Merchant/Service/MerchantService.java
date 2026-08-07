package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Domain.DeliveryFeeCalculator;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家服务类
 * 负责商家的登录、信息更新等业务逻辑
 */
@Service
public class MerchantService {
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private MerchantConverter merchantConverter;
    @Autowired
    private JWTUtils jwtUtils;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private DeliveryTaskMapper deliveryTaskMapper;
    @Autowired
    private DeliveryFeeCalculator  deliveryFeeCalculator;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 商家登录
     * @param dto 登录请求DTO
     * @return 登录结果VO（包含ID和Token）
     */
    public loginVO login(@NonNull MerchantLoginDTO dto) {
        // 1. 根据用户名查询商家
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUsername, dto.getUserName()));
        
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }

        // 2. 验证密码
        if (!BCrypt.matches(dto.getPassword(), merchant.getPassword())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }

        // 3. 生成登录结果
        loginVO loginVO = new loginVO();
        loginVO.setId(merchant.getId());
        loginVO.setToken(jwtUtils.createToken(merchant.getId(), AuthRole.MERCHANT));

        return loginVO;
    }

    //NOTE:商家注册
    @Transactional(rollbackFor = Exception.class)
    public void register(@NonNull MerchantRegisterDTO dto) {
        Merchant tempMerchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().
                eq(Merchant::getUsername, dto.getUsername()));
        if (tempMerchant != null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"已经存在改用户名");
        }
        Merchant merchant= merchantConverter.toMerchant(dto);
        merchant.setPassword(BCrypt.encode(dto.getPassword()));
        merchant.setStatus(MerchantStatusEnum.BUSINESS_CLOSED.getCode());
        merchantMapper.insert(merchant);
        createDefaultCategory(merchant.getId());
    }
    private void createDefaultCategory(Long merchantId) {
        Category defaultCategory = new Category();
        defaultCategory.setMerchantId(merchantId);
        defaultCategory.setCategoryName("默认分类");
        defaultCategory.setIsDefault(CategoryDefaultEnum.DEFAULT.getCode());
        defaultCategory.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        categoryMapper.insert(defaultCategory);
    }

    /**
     * 更新商家信息
     */
    public MerchantUpdateVO updateMerchant(MerchantUpdateDTO merchantUpdateDTO) {
        Merchant oldMerchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().
                eq(Merchant::getId,MerchantContextHolder.getMerchantId()).
                eq(Merchant::getStatus, MerchantStatusEnum.BUSINESS_CLOSED.getCode()));
        if (oldMerchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商户不存在或者当前状态不允许修改信息");
        }
        //NOTE:维持原判。商家的名字不做去重处理
        Merchant merchant = merchantConverter.toMerchant(merchantUpdateDTO, oldMerchant);
        int i = merchantMapper.updateById(merchant);
        if (i != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商家信息更新失败");
        }

        return merchantConverter.toMerchantUpdateVO(merchant);
    }

    //NOTE：手动更新营业状态
    public void updateStatus(Integer manualStatus) {
        if (manualStatus == null) {
            return;
        }
        Merchant merchant = merchantMapper.
                selectById(MerchantContextHolder.getMerchantId());
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商户不能为空");
        }
        merchant.setStatus(manualStatus);
        int i = merchantMapper.updateById(merchant);
        if (i != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商家营业状态更新失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void acceptOrder(@NonNull Long orderId) {

        Long merchantId = MerchantContextHolder.getMerchantId();

        int rows = orderMapper.updateOrderStatusToPreparing(
                orderId,
                merchantId,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.PREPARING.getCode()
        );

        if(rows != 1){
            Order order = orderMapper.selectById(orderId);
            if (order == null || !merchantId.equals(order.getMerchantId())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或不属于当前商家");
            }

            if (OrderStatusEnum.PREPARING.getCode().equals(order.getStatus())) {
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "接单失败，当前订单状态为：" + order.getStatus());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void completePreparation(Long orderId){
        Long merchantId = MerchantContextHolder.getMerchantId();

        int rows = orderMapper.updateOrderStatusToReady(
                orderId,
                merchantId,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode()
        );
        Order order = orderMapper.selectById(orderId);
        if(rows != 1){

            if (order == null || !merchantId.equals(order.getMerchantId())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或不属于当前商家");
            }

            if (OrderStatusEnum.READY.getCode().equals(order.getStatus())) {
                assertWaitingDeliveryTask(orderId);
                return;
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单当前状态为：" + order.getStatus() + "，无法出餐");
        }


        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商户不存在");
        }

        DeliveryTask task = new DeliveryTask();
        task.setOrderId(orderId);
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



    private void assertWaitingDeliveryTask(Long orderId) {
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

}
