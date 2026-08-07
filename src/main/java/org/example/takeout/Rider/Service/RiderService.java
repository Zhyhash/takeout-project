package org.example.takeout.Rider.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.Rider.DTO.RiderRegisterDTO;
import org.example.takeout.Rider.Entity.Rider;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Rider.VO.RiderLoginVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderService {

    private final RiderMapper riderMapper;
    private final JWTUtils jwtUtils;

    public RiderService(RiderMapper riderMapper, JWTUtils jwtUtils) {
        this.riderMapper = riderMapper;
        this.jwtUtils = jwtUtils;
    }

    @Transactional(rollbackFor = Exception.class)
    public void register(RiderRegisterDTO dto) {
        Rider existingRider = riderMapper.selectOne(
                Wrappers.<Rider>lambdaQuery().eq(Rider::getName, dto.getName())
        );
        if (existingRider != null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "骑手名称已存在");
        }

        Rider rider = new Rider();
        rider.setName(dto.getName());
        rider.setPhone(dto.getPhone());
        rider.setPassword(BCrypt.encode(dto.getPassword()));
        rider.setStatus(RiderStatusEnum.NORMAL.getCode());
        rider.setIsDelete(DeleteConstant.NOT_DELETED);
        riderMapper.insert(rider);
    }

    public RiderLoginVO login(RiderLoginDTO dto) {
        Rider rider = riderMapper.selectOne(
                Wrappers.<Rider>lambdaQuery().eq(Rider::getName, dto.getName())
        );
        if (rider == null || !BCrypt.matches(dto.getPassword(), rider.getPassword())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "骑手名称或密码错误");
        }
        if (!RiderStatusEnum.NORMAL.getCode().equals(rider.getStatus())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "骑手账号已禁用");
        }

        RiderLoginVO loginVO = new RiderLoginVO();
        loginVO.setId(rider.getId());
        loginVO.setName(rider.getName());
        loginVO.setToken(jwtUtils.createToken(rider.getId(), AuthRole.RIDER));
        return loginVO;
    }
}
