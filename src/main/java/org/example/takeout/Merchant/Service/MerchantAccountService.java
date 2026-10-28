package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Category.Service.CategoryCommandService;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.GeoCoordinates;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商家账号与店铺基础管理服务。
 * 负责商家注册、登录、身份校验、店铺资料更新及手动营业状态维护。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantAccountService {
    private final MerchantMapper merchantMapper;
    private final MerchantConverter merchantConverter;
    private final JWTUtils jwtUtils;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final CategoryCommandService categoryCommendService;

    /**
     * 商家登录
     * @param dto 登录请求DTO
     * @return 登录结果VO（包含ID和Token）
     */
    public loginVO login(@NonNull MerchantLoginDTO dto) {
        loginAttemptLimiter.checkAllowed(AuthRole.MERCHANT, dto.getUsername());

        // 1. 根据用户名查询商家
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUsername, dto.getUsername()));
        
        if (merchant == null) {
            loginAttemptLimiter.recordFailure(AuthRole.MERCHANT, dto.getUsername());
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }

        // 2. 验证密码
        if (!BCrypt.matches(dto.getPassword(), merchant.getPassword())) {
            loginAttemptLimiter.recordFailure(AuthRole.MERCHANT, dto.getUsername());
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }

        loginAttemptLimiter.clearFailures(AuthRole.MERCHANT, dto.getUsername());

        // 3. 生成登录结果
        loginVO loginVO = new loginVO();
        loginVO.setId(merchant.getId());
        loginVO.setToken(jwtUtils.createToken(merchant.getId(), AuthRole.MERCHANT));


        return loginVO;
    }

    /**
     * 返回当前请求中的有效商家 ID，并确认账号仍然存在。
     * 营业状态不代表账号状态，打烊商家仍可访问管理接口。
     */
    public Long requireActiveMerchantId() {
        Long merchantId = MerchantContextHolder.getMerchantId();
        if (merchantId == null) {
            throw new AuthException("商家身份无效，请重新登录");
        }

        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new AuthException("商家账号不存在，请重新登录");
        }
        return merchantId;
    }

    //NOTE:商家注册
    @Transactional(rollbackFor = Exception.class)
    public void register(@NonNull MerchantRegisterDTO dto) {
        List<Merchant> conflicts = merchantMapper.selectList(
                Wrappers.<Merchant>lambdaQuery()
                        .and(wrapper -> wrapper
                                .eq(Merchant::getUsername, dto.getUsername())
                                .or()
                                .eq(Merchant::getPhone, dto.getPhone())
                        )
        );

        for (Merchant conflict : conflicts) {
            if (dto.getUsername().equals(conflict.getUsername())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "用户名已经存在");
            }
            if (dto.getPhone().equals(conflict.getPhone())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "手机号已经存在");
            }
        }

        Merchant merchant= merchantConverter.toMerchant(dto);
        merchant.setPassword(BCrypt.encode(dto.getPassword()));
        merchant.setStatus(MerchantStatusEnum.BUSINESS_CLOSED.getCode());
        try {
            merchantMapper.insert(merchant);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "用户名或手机号已经存在"
            );
        }
        categoryCommendService.createDefaultCategory(merchant.getId());
    }


    /**
     * 更新商家信息
     */
    public MerchantUpdateVO updateMerchant(MerchantUpdateDTO merchantUpdateDTO) {
        if (!merchantUpdateDTO.isCoordinatePairValid()) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "经纬度必须同时提供");
        }
        if (merchantUpdateDTO.getLongitude() != null) {
            GeoCoordinates.validate(merchantUpdateDTO.getLongitude().doubleValue(),
                    merchantUpdateDTO.getLatitude().doubleValue());
        }
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

        Merchant merchant = merchantConverter.toMerchant(merchantUpdateDTO, oldMerchant);
        int affected;
        try {
            affected = merchantMapper.updateById(merchant);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "手机号已被其他商家使用"
            );
        }

        if (affected != 1) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "商家信息更新失败"
            );
        }

        return merchantConverter.toMerchantUpdateVO(merchant);
    }

    //NOTE：手动更新营业状态
    //TODO：若后续经营职责增多，再考虑迁入 MerchantOperationService；当前保留此方法。
    public void updateStatus(Integer manualStatus) {
        if (!MerchantStatusEnum.BUSINESS_OPEN.getCode().equals(manualStatus)
                && !MerchantStatusEnum.BUSINESS_CLOSED.getCode().equals(manualStatus)) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "商家营业状态不合法");
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





}
