package org.example.takeout.User.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.DTO.RegisterDTO;
import org.example.takeout.User.Entity.User;
import org.example.takeout.User.Mapper.UserMapper;
import org.example.takeout.User.StatusEnum.UserStatusEnum;
import org.example.takeout.User.VO.LoginVO;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.example.takeout.Common.Utils.Tool.Random.random;

@Service
public class UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JWTUtils jwtUtils;

    @Transactional(rollbackFor =  Exception.class)

    public void register(RegisterDTO dto){

        // 查询用户名是否已存在
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, dto.getUsername())
        );
        
        if(user != null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名已经存在");
        }

        // 创建新用户
        user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setStatus(UserStatusEnum.NORMAL.getCode());
        user.setNickname("用户_" + random.nextInt(10000));
        userMapper.insert(user);
    }

    public LoginVO login(@NonNull LoginDTO loginDTO){
        //查询用户是否存在
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, loginDTO.getUsername()));
        if(user == null){
            //安全性保证
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }
        boolean matches = BCrypt.matches(loginDTO.getPassword(), user.getPassword());
        if(!matches){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户名或密码错误");
        }
        if (!UserStatusEnum.NORMAL.getCode().equals(user.getStatus())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"用户账号已禁用或不可用");
        }
        LoginVO loginVO = new LoginVO();
        loginVO.setId(user.getId());
        loginVO.setNickname(user.getNickname());
        loginVO.setToken(jwtUtils.createToken(user.getId(), AuthRole.USER));
        return loginVO;
    }

    /**
     * 返回当前请求中的有效用户 ID，并复核数据库中的实时账号状态。
     * 用于阻断账号被禁用、注销或删除后继续使用旧 Token。
     */
    public Long requireActiveUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new AuthException("用户身份无效，请重新登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null || !UserStatusEnum.NORMAL.getCode().equals(user.getStatus())) {
            throw new AuthException("用户账号已禁用或不存在，请重新登录");
        }
        return userId;
    }
}
