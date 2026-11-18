package com.example.platform.autoconfigure;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.platform.convert.MaskedValue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for the whole platform namespace.
 *
 * <p>Senior notes:
 * <ul>
 *   <li>Constructor binding (a single non-default constructor, no setters) makes the object
 *       immutable. {@code @ConstructorBinding} is only needed when a class has several
 *       constructors - with one constructor Boot 3 infers it.</li>
 *   <li>{@code @Validated} turns binding errors into a fail-fast
 *       {@code BindValidationException} at startup instead of an NPE at 3am.</li>
 *   <li>{@code Duration} properties accept {@code 250ms}, {@code 5s}, {@code 2m};
 *       {@code @DurationUnit} supplies the unit when the value is a bare number.</li>
 *   <li>{@link MaskedValue} shows how a custom property type is bound - see
 *       {@code StringToMaskedValueConverter}.</li>
 *   <li>Nested records keep the YAML shape obvious and give the configuration processor
 *       everything it needs for IDE completion.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(

        /** Logical name reported by the custom actuator endpoint and used as a metric tag. */
        @NotBlank String applicationName,

        /** Injected by PlatformEnvironmentPostProcessor before the context exists. */
        String instanceId,

        @Valid Correlation correlation,
        @Valid Audit audit,
        @Valid Http http) {

    public PlatformProperties {
        // Records normalise and default inside the canonical constructor.
        //
        // IMPORTANT: defaulting only the whole nested object is NOT enough. With constructor
        // binding, setting ONE sub-property (platform.audit.enabled=false) constructs the nested
        // record with 0/null for every other component - so a @Min(1) field fails validation and
        // the context will not start. Each nested record therefore defaults its OWN components
        // too; this outer block only covers "the section is absent entirely".
        correlation = correlation != null ? correlation : new Correlation(true, null, true);
        audit = audit != null ? audit : new Audit(true, null, 0);
        http = http != null ? http : new Http(null, null, true, null);
    }

    public record Correlation(
            boolean enabled,
            @NotBlank String headerName,
            /** Echo the id back on the response so clients can quote it in support tickets. */
            boolean echoResponseHeader) {

        public Correlation {
            headerName = (headerName == null || headerName.isBlank()) ? "X-Correlation-Id" : headerName;
        }
    }

    public record Audit(
            boolean enabled,
            /** Argument names whose values are replaced with *** in audit records. */
            List<String> maskedFields,
            /** Audit records slower than this are logged at WARN. */
            @Min(1) @Max(60_000) long slowCallThresholdMillis) {

        public Audit {
            maskedFields = maskedFields != null ? maskedFields
                    : List.of("password", "token", "secret", "cardNumber");
            slowCallThresholdMillis = slowCallThresholdMillis <= 0 ? 500 : slowCallThresholdMillis;
        }
    }

    public record Http(
            @DurationUnit(ChronoUnit.MILLIS) Duration connectTimeout,
            @DurationUnit(ChronoUnit.MILLIS) Duration readTimeout,
            /** Log method, URI, status and elapsed time for every outbound call. */
            boolean logRequests,
            /** Custom bound type: toString() never leaks the secret into logs. */
            MaskedValue apiKey) {

        public Http {
            connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(5);
            apiKey = apiKey != null ? apiKey : new MaskedValue("");
        }
    }
}
