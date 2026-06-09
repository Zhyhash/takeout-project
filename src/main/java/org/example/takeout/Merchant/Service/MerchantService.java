package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    /**
     * 商家登录
     * @param dto 登录请求DTO
     * @return 登录结果VO（包含ID和Token）
     */
    public loginVO login(@NonNull MerchantLoginDTO dto) {
        // 1. 根据用户名查询商家
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUsername, dto.getMerchantName()));
        
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

    /**
     * 更新商家信息
     */
    public MerchantUpdateVO updateMerchant(MerchantUpdateDTO merchantUpdateDTO) {
        Merchant oldMerchant = merchantMapper.selectById(MerchantContextHolder.getMerchantId());
        if (oldMerchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商户不存在");
        }
        Merchant merchant = merchantConverter.toMerchant(merchantUpdateDTO, oldMerchant);
        merchantMapper.updateById(merchant);

        MerchantUpdateVO merchantUpdateVO = new MerchantUpdateVO();
        merchantConverter.AfterMapper(merchant, merchantUpdateVO);
        return merchantUpdateVO;
    }
}
