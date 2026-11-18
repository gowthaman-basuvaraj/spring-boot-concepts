package com.example.orders.api.validation;

import java.util.HashSet;
import java.util.Set;

import com.example.orders.api.CreateOrderRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link NoDuplicateSkus}. Typed to the DTO, which is what makes cross-field access
 * possible.
 *
 * <p>Note the defensive nulls: a class-level validator runs <b>even when field-level constraints
 * have already failed</b>. Bean Validation does not short-circuit, so {@code lines()} may be null
 * or contain nulls while {@code @NotEmpty} is separately reporting the same object. A cross-field
 * validator that assumes its fields are populated throws {@code NullPointerException} inside the
 * validation pass and turns a tidy 400 into a 500.
 */
public class NoDuplicateSkusValidator
        implements ConstraintValidator<NoDuplicateSkus, CreateOrderRequest> {

    @Override
    public boolean isValid(CreateOrderRequest request, ConstraintValidatorContext context) {
        if (request == null || request.lines() == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (CreateOrderRequest.Line line : request.lines()) {
            if (line == null || line.sku() == null) {
                continue; // field-level constraints are reporting this already
            }
            if (!seen.add(line.sku().toUpperCase())) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                                "SKU " + line.sku() + " appears more than once; merge the lines")
                        // Attaching the violation to a property turns a global error into a field
                        // error, which is friendlier for a form-driven client.
                        .addPropertyNode("lines")
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}
