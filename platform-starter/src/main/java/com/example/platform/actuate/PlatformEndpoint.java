package com.example.platform.actuate;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.platform.audit.AuditCounters;
import com.example.platform.autoconfigure.PlatformProperties;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * A custom actuator endpoint, exposed at {@code /actuator/platform}.
 *
 * <p>Operation mapping:
 * <ul>
 *   <li>{@code @ReadOperation} -&gt; GET</li>
 *   <li>{@code @WriteOperation} -&gt; POST (parameters come from the JSON body)</li>
 *   <li>{@code @DeleteOperation} -&gt; DELETE</li>
 *   <li>{@code @Selector} -&gt; a path variable, e.g. {@code /actuator/platform/audit}</li>
 * </ul>
 *
 * <p>Remember to expose it: {@code management.endpoints.web.exposure.include}. Endpoints are
 * secured by Spring Security if present - write operations on an unsecured actuator port are a
 * finding in any serious review.
 */
@Endpoint(id = "platform")
public class PlatformEndpoint {

    private final PlatformProperties properties;
    private final AuditCounters counters;

    public PlatformEndpoint(PlatformProperties properties, AuditCounters counters) {
        this.properties = properties;
        this.counters = counters;
    }

    @ReadOperation
    public Map<String, Object> platform() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("applicationName", properties.applicationName());
        view.put("instanceId", properties.instanceId());
        view.put("correlation", Map.of(
                "enabled", properties.correlation().enabled(),
                "header", properties.correlation().headerName()));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("enabled", properties.audit().enabled());
        audit.put("maskedFields", properties.audit().maskedFields());
        audit.putAll(counters.snapshot());
        view.put("audit", audit);
        view.put("http", Map.of(
                "connectTimeout", properties.http().connectTimeout().toString(),
                "readTimeout", properties.http().readTimeout().toString(),
                // toString() of MaskedValue is safe to publish.
                "apiKey", String.valueOf(properties.http().apiKey())));
        return view;
    }

    /** GET /actuator/platform/{section} */
    @ReadOperation
    public Map<String, Object> section(@Selector String section) {
        Object value = platform().get(section);
        return value == null ? Map.of("error", "unknown section: " + section) : Map.of(section, value);
    }

    /** POST /actuator/platform with body {"note":"..."} - shows a write operation. */
    @WriteOperation
    public Map<String, Object> note(String note) {
        return Map.of("received", String.valueOf(note), "instanceId", properties.instanceId());
    }

    /** DELETE /actuator/platform - resets the counters. */
    @DeleteOperation
    public Map<String, Object> reset() {
        counters.reset();
        return Map.of("status", "counters reset");
    }
}
