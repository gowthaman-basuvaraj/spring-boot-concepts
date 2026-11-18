package com.example.platform.http;

import java.time.Duration;

import com.example.platform.autoconfigure.PlatformProperties;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Applies platform defaults to EVERY {@code RestClient.Builder} the application injects.
 *
 * <p>This is the key customization lesson for outbound HTTP: do not configure timeouts at each
 * call site. Boot auto-configures a prototype-scoped {@code RestClient.Builder} and applies all
 * {@link RestClientCustomizer} beans to it, so one bean fixes the whole application.
 *
 * <p>Timeouts are non-negotiable in production. The JDK {@code HttpClient} default read timeout is
 * <em>infinite</em>: one hung downstream can pin every servlet thread and take the service down.
 */
public class PlatformRestClientCustomizer implements RestClientCustomizer {

    private final PlatformProperties properties;

    public PlatformRestClientCustomizer(PlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(RestClient.Builder builder) {
        PlatformProperties.Http http = properties.http();
        builder.requestFactory(requestFactory(http.connectTimeout(), http.readTimeout()))
                .requestInterceptor(new OutboundRequestInterceptor(
                        properties.correlation().headerName(), http.logRequests()))
                .defaultHeader("User-Agent", properties.applicationName() + "/" + properties.instanceId());
        if (http.apiKey() != null && http.apiKey().hasValue()) {
            builder.defaultHeader("X-Api-Key", http.apiKey().reveal());
        }
    }

    /**
     * Builds the request factory from whatever client library is on the classpath
     * (Apache HttpClient 5 &gt; Jetty &gt; JDK HttpClient), applying our timeouts.
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
