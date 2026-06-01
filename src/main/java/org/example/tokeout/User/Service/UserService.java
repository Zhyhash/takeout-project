package org.example.tokeout.User.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.tokeout.Common.Exception.BusinessException;
import org.example.tokeout.Common.Utils.MyScurity.BCrypt;
import org.example.tokeout.Common.Utils.MyScurity.JWTUtils;
import org.example.tokeout.User.DTO.LoginDTO;
import org.example.tokeout.User.DTO.RegisterDTO;
import org.example.tokeout.User.Entity.User;
import org.example.tokeout.User.Mapper.UserMapper;
import org.example.tokeout.User.VO.LoginVO;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.example.tokeout.Common.Utils.Tool.Random.random;

@Service
public class UserService {
    @Autowired
    private UserMapper userMapper;

    @Transactional
    public void register(RegisterDTO dto){
        // 验证密码是否一致
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的密码不一致");
        }

        // 查询用户名是否已存在
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, dto.getUsername())
        );
        
        if(user != null){
            throw new BusinessException("用户名已经存在");
        }

        // 创建新用户
        user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setStatus(1);
        user.setNickname("用户_" + random.nextInt(10000));
        userMapper.insert(user);
    }

    public LoginVO login(@NonNull LoginDTO loginDTO){
        //查询用户是否存在
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, loginDTO.getUsername()));
        if(user == null){
            //安全性保证
            throw new BusinessException("用户名或密码错误");
        }
        boolean matches = BCrypt.matches(loginDTO.getPassword(), user.getPassword());
        if(!matches){
            throw new BusinessException("用户名或密码错误");
        }
        LoginVO loginVO = new LoginVO();
        loginVO.setId(user.getId());
        loginVO.setNickname(user.getNickname());
        loginVO.setToken(JWTUtils.generateMerchantToken(user.getId()));
        return loginVO;
    }
}
