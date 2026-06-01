package org.example.tokeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.tokeout.Common.Exception.BusinessException;
import org.example.tokeout.Common.Utils.Context.MerchantContextHolder;
import org.example.tokeout.Common.Utils.MyScurity.BCrypt;
import org.example.tokeout.Common.Utils.MyScurity.JWTUtils;
import org.example.tokeout.Merchant.DTO.MerchantLoginDTO;
import org.example.tokeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.tokeout.Merchant.Entity.Merchant;
import org.example.tokeout.Merchant.Mapper.MerchantConverter;
import org.example.tokeout.Merchant.Mapper.MerchantMapper;
import org.example.tokeout.Merchant.VO.MerchantUpdateVO;
import org.example.tokeout.Merchant.VO.loginVO;
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

    /**
     * 商家登录
     * @param dto 登录请求DTO
     * @return 登录结果VO（包含ID和Token）
     */
    public loginVO login(MerchantLoginDTO dto) {
        // 1. 根据用户名查询商家
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUsername, dto.getMerchantName()));
        
        if (merchant == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 2. 验证密码
        if (!BCrypt.matches(dto.getPassword(), merchant.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 3. 生成登录结果
        loginVO loginVO = new loginVO();
        loginVO.setId(merchant.getId());
        loginVO.setToken(JWTUtils.generateMerchantToken(merchant.getId()));

        return loginVO;
    }

    /**
     * 更新商家信息
     */
    public MerchantUpdateVO updateMerchant(MerchantUpdateDTO merchantUpdateDTO) {
        Merchant oldMerchant = merchantMapper.selectById(MerchantContextHolder.getMerchantId());
        if (oldMerchant == null) {
            throw new BusinessException("商户不存在");
        }
        Merchant merchant = merchantConverter.toMerchant(merchantUpdateDTO, oldMerchant);
        merchantMapper.updateById(merchant);

        MerchantUpdateVO merchantUpdateVO = new MerchantUpdateVO();
        merchantConverter.toMerchantUpdateVO(merchant, merchantUpdateVO);
        return merchantUpdateVO;
    }
}
