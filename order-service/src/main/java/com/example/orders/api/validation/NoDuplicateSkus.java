package com.example.orders.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A <b>class-level</b> (cross-field) constraint: no SKU may appear twice in one order.
 *
 * <p>This is the shape to reach for whenever a rule spans more than one field - the canonical
 * examples being "password and confirmation must match", "end date must be after start date", and
 * "exactly one of these two fields must be set". A field-level constraint cannot see its siblings,
 * so people reach for a manual check in the service and the rule stops being part of the contract.
 *
 * <p>The one wrinkle: a class-level violation has an empty property path, so it arrives as a
 * <em>global</em> error rather than a field error. {@code ApiExceptionHandler} already reads
 * {@code getGlobalErrors()} alongside {@code getFieldErrors()} for exactly this reason - a handler
 * that only reads field errors will return a 400 with an empty {@code errors} map and leave the
 * client guessing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoDuplicateSkusValidator.class)
@Documented
public @interface NoDuplicateSkus {

    String message() default "an order must not contain the same SKU twice";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
