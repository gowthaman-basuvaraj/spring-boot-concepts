package com.example.orders.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A custom field constraint.
 *
 * <p>The four required pieces, none of which you can omit:
 * <ol>
 *   <li>{@code @Constraint(validatedBy = ...)} - points at the {@code ConstraintValidator};</li>
 *   <li>{@code message()}, {@code groups()}, {@code payload()} - the Bean Validation spec requires
 *       all three, and omitting any one fails at startup with
 *       {@code ConstraintDefinitionException};</li>
 *   <li>{@code RUNTIME} retention - it is read reflectively;</li>
 *   <li>targets that include {@code ElementType.PARAMETER} if you want to use it on method
 *       parameters, and {@code TYPE_USE} for generics like {@code List<@Sku String>}.</li>
 * </ol>
 *
 * <p>{@code message} may be a literal or a {@code {bundle.key}} resolved from
 * {@code ValidationMessages.properties}, with {@code {min}}-style interpolation of the
 * annotation's own attributes.
 *
 * <p>Why not just {@code @Pattern}? Because a named constraint is self-documenting at every use
 * site, the rule lives in one place when it changes, and the validator can do things a regex
 * cannot - normalise case, check a checksum, consult a registry.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE_USE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SkuValidator.class)
@Documented
public @interface Sku {

    String message() default "must be a SKU of the form SKU-XXXX";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Allow the lower-case form, normalising it rather than rejecting it. */
    boolean lenientCase() default false;
}
