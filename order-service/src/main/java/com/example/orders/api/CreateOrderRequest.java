package com.example.orders.api;

import java.math.BigDecimal;
import java.util.List;

import com.example.orders.api.validation.NoDuplicateSkus;
import com.example.orders.api.validation.Sku;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Inbound request DTO.
 *
 * <p>Why a DTO and not the entity? Because exposing an entity means: clients can set your id and
 * version, every mapping change is a breaking API change, Jackson walks your lazy associations, and
 * validation annotations end up in your persistence model. Separating them costs a mapper and buys
 * you the ability to evolve both sides independently.
 *
 * <p>Validation reminders:
 * <ul>
 *   <li>{@code @Valid} on the nested list is REQUIRED - validation is not recursive by default,
 *       and {@code @Valid List<Line>} validates each element.</li>
 *   <li>The controller parameter needs {@code @Valid} too, otherwise none of this runs.</li>
 *   <li>{@code @NotNull} vs {@code @NotEmpty} vs {@code @NotBlank}: null / size>0 / trimmed
 *       length>0. Using the wrong one accepts {@code "   "} as a customer email.</li>
 * </ul>
 */
@NoDuplicateSkus
public record CreateOrderRequest(

        @NotBlank @Email @Size(max = 255) String customerEmail,

        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency,

        @NotEmpty @Size(max = 100) @Valid List<Line> lines) {

    public record Line(
            @NotBlank @Size(max = 64) @Sku String sku,
            @Positive @Max(1000) int quantity,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal unitPrice) {
    }
}
