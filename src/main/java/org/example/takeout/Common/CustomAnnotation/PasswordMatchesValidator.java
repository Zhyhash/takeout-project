package org.example.takeout.Common.CustomAnnotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

@Component
public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, PasswordMatchable> {

    @Override
    public boolean isValid(PasswordMatchable dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }
        if (dto.getPassword() == null || dto.getConfirmPassword() == null) {
            return false;
        }
        return dto.getPassword().equals(dto.getConfirmPassword());
    }
}