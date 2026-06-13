package org.example.takeout.Common.CustomAnnotation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.Data;
import org.example.takeout.User.Entity.User;
import org.example.takeout.User.Mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Data
public class UniquePhoneValidator implements ConstraintValidator<UniquePhone, String> {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String targetTable;

    @Override
    public void initialize(UniquePhone constraintAnnotation) {
        this.targetTable = constraintAnnotation.targetTable();
    }

    @Override
    public boolean isValid(String phone, ConstraintValidatorContext constraintValidatorContext) {
        if (phone == null || phone.isBlank()) {
            return true;
        }
        // 动态组装简单的 SQL，检查对应表里手机号是否存在
        // 注意：为防 SQL 注入，表名不能用占位符，但表名是我们内部在注解里写死的，所以安全；phone 用占位符 ?
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE phone = ?", targetTable);

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, phone);
        return count != null && count == 0;
    }
}
