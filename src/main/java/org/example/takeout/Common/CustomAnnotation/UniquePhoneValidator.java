package org.example.takeout.Common.CustomAnnotation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.example.takeout.User.Entity.User;
import org.example.takeout.User.Mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UniquePhoneValidator implements ConstraintValidator<UniquePhone, String> {
    @Autowired
    private UserMapper userMapper;
    @Override
    public boolean isValid(String s, ConstraintValidatorContext constraintValidatorContext) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, s));
        return user == null;

    }
}
