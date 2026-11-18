package com.example.orders.api.validation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.example.orders.api.CreateOrderRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The custom constraints, tested WITHOUT a Spring context.
 *
 * <p>Bean Validation is a plain Java API: a {@code ValidatorFactory} needs no container, so these
 * run in milliseconds. Spin up {@code @SpringBootTest} for a validator only when it injects Spring
 * beans - and if yours does, ask first whether a request-time database lookup belongs in a
 * validator at all.
 */
@DisplayName("Custom constraints: @Sku (field) and @NoDuplicateSkus (class level)")
class CustomConstraintTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("a well-formed request passes")
    void validRequest() {
        assertThat(validator.validate(request(line("SKU-LAPTOP", 1)))).isEmpty();
    }

    @Test
    @DisplayName("@Sku rejects a malformed sku and names the offending value")
    void malformedSkuIsRejected() {
        Set<ConstraintViolation<CreateOrderRequest>> violations =
                validator.validate(request(line("laptop", 1)));

        assertThat(violations).hasSize(1);
        ConstraintViolation<CreateOrderRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("lines[0].sku");
        // The custom message quotes the value, which is worth more than "must be a SKU".
        assertThat(violation.getMessage()).contains("'laptop'").contains("SKU-XXXX");
    }

    @Test
    @DisplayName("@Sku treats null as valid - presence is @NotBlank's job, not its own")
    void nullSkuIsNotThisConstraintsProblem() {
        Set<ConstraintViolation<CreateOrderRequest>> violations =
                validator.validate(request(line(null, 1)));

        // Exactly one violation, from @NotBlank - NOT two. A validator that also rejected null
        // would make @Sku silently mandatory and double-report every missing value.
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getConstraintDescriptor().getAnnotation()
                .annotationType().getSimpleName()).isEqualTo("NotBlank");
    }

    @Test
    @DisplayName("@NoDuplicateSkus catches a cross-field rule no field constraint could see")
    void duplicateSkusAreRejected() {
        Set<ConstraintViolation<CreateOrderRequest>> violations =
                validator.validate(request(line("SKU-MOUSE", 1), line("SKU-MOUSE", 2)));

        assertThat(violations).hasSize(1);
        ConstraintViolation<CreateOrderRequest> violation = violations.iterator().next();
        assertThat(violation.getMessage()).contains("SKU-MOUSE").contains("more than once");
        // addPropertyNode("lines") turned what would have been a global error into a field error,
        // which is what a form-driven client can actually display.
        assertThat(violation.getPropertyPath()).hasToString("lines");
    }

    @Test
    @DisplayName("different skus are fine, and case is normalised before comparing")
    void distinctSkusPass() {
        assertThat(validator.validate(request(line("SKU-MOUSE", 1), line("SKU-DOCK", 1)))).isEmpty();
    }

    @Test
    @DisplayName("the class-level validator survives field-level failures (it runs anyway)")
    void classLevelValidatorToleratesBrokenFields() {
        // Bean Validation does not short-circuit: NoDuplicateSkusValidator sees this object while
        // @NotEmpty is separately failing it. A validator that assumed its fields were populated
        // would throw an NPE inside the validation pass and turn a 400 into a 500.
        Set<ConstraintViolation<CreateOrderRequest>> violations =
                validator.validate(new CreateOrderRequest("a@b.com", "EUR", List.of()));

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("lines");
    }

    private static CreateOrderRequest request(CreateOrderRequest.Line... lines) {
        return new CreateOrderRequest("buyer@example.com", "EUR", List.of(lines));
    }

    private static CreateOrderRequest.Line line(String sku, int quantity) {
        return new CreateOrderRequest.Line(sku, quantity, new BigDecimal("9.99"));
    }
}
