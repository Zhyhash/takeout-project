package org.example.takeout.Common.CustomAnnotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordMatchesValidator.class)
public @interface PasswordMatches {
    String message() default "两次输入的密码不一致";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
