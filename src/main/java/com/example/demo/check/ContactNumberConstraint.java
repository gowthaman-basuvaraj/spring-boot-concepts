package com.example.demo.check;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ContactNumberValidator.class)
@Documented
public @interface ContactNumberConstraint {
    String message() default "Invalid custom field value";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
