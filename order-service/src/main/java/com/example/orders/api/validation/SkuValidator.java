package com.example.orders.api.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * The validator behind {@link Sku}.
 *
 * <p>Points that matter:
 * <ul>
 *   <li><b>{@code null} must be valid.</b> A constraint validator's job is one rule; "is it
 *       present?" is {@code @NotNull}'s job. Rejecting null here would make {@code @Sku} silently
 *       mandatory and there would be no way to express an optional SKU.</li>
 *   <li><b>It is a Spring bean</b>, so it can inject collaborators - a repository, a feature flag.
 *       Be careful: a validator that queries the database runs on every request, and inside a
 *       {@code @Valid} loop that is an N+1 nobody is looking for.</li>
 *   <li><b>{@code initialize}</b> receives the annotation instance, which is how per-use-site
 *       attributes ({@code lenientCase}) reach the validator.</li>
 *   <li><b>A custom message</b> needs {@code disableDefaultConstraintViolation()} first, or you get
 *       both messages.</li>
 * </ul>
 */
public class SkuValidator implements ConstraintValidator<Sku, String> {

    private static final Pattern STRICT = Pattern.compile("SKU-[A-Z0-9]{2,32}");
    private static final Pattern LENIENT = Pattern.compile("(?i)SKU-[A-Z0-9]{2,32}");

    private Pattern pattern = STRICT;

    @Override
    public void initialize(Sku annotation) {
        this.pattern = annotation.lenientCase() ? LENIENT : STRICT;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // let @NotNull / @NotBlank decide whether absence is allowed
        }
        if (pattern.matcher(value).matches()) {
            return true;
        }
        // A message that quotes the offending value is worth far more in a support ticket than
        // "must be a SKU".
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "'" + value.replace("{", "").replace("}", "")
                                + "' is not a valid SKU (expected SKU-XXXX)")
                .addConstraintViolation();
        return false;
    }
}
