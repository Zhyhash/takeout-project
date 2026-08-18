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
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Order.Service.OrderCommandService;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
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
    private OrderCommandService orderCommandService;
    @Autowired
    private DeliveryTaskService deliveryTaskService;

    /**
     * 商家登录
     * @param dto 登录请求DTO
     * @return 登录结果VO（包含ID和Token）
     */
    public loginVO login(@NonNull MerchantLoginDTO dto) {
        // 1. 根据用户名查询商家
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUsername, dto.getUsername()));
        
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
        Long merchantId = MerchantContextHolder.getMerchantId();
        Merchant oldMerchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().
                eq(Merchant::getId, merchantId).
                eq(Merchant::getStatus, MerchantStatusEnum.BUSINESS_CLOSED.getCode()));
        if (oldMerchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商户不存在或者当前状态不允许修改信息");
        }
        if (merchantUpdateDTO.getPhone() != null) {
            Long duplicatePhoneCount = merchantMapper.selectCount(Wrappers.<Merchant>lambdaQuery()
                    .eq(Merchant::getPhone, merchantUpdateDTO.getPhone())
                    .ne(Merchant::getId, merchantId));
            if (duplicatePhoneCount != null && duplicatePhoneCount > 0) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "手机号已被其他商家使用");
            }
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


    public void acceptOrder(@NonNull Long orderId) {

        Long merchantId = MerchantContextHolder.getMerchantId();

        orderCommandService.acceptOrderByMerchant(orderId, merchantId);
    }

    @Transactional(rollbackFor = Exception.class)
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







}
