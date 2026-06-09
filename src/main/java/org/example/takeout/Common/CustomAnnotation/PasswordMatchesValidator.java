package org.example.takeout.Common.CustomAnnotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.example.takeout.User.DTO.RegisterDTO;
import org.springframework.stereotype.Component;

@Component
public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, RegisterDTO> {


    @Override
    public boolean isValid(RegisterDTO registerDTO, ConstraintValidatorContext constraintValidatorContext) {
        if (registerDTO==null) {
            return true;
        }
        if (registerDTO.getPassword()==null||registerDTO.getPassword().isEmpty()) {
            return true;
        }
        return registerDTO.getPassword().equals(registerDTO.getConfirmPassword());
    }
}
