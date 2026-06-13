package org.example.takeout.Common.CustomAnnotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import org.hibernate.validator.internal.constraintvalidators.hv.UniqueElementsValidator;

import java.lang.annotation.*;

@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {UniquePhoneValidator.class})
public @interface UniquePhone {
    String message()default "手机号已经被绑定";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    String targetTable() default "user";
}
