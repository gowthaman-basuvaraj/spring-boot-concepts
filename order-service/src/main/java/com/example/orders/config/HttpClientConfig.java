package com.example.orders.config;

import com.example.orders.payments.PaymentHttpApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Wires the declarative {@link PaymentHttpApi} over a {@code RestClient}.
 *
 * <p>Note that the {@code RestClient.Builder} injected here is the auto-configured, prototype-scoped
 * one, so it already has the platform customizations (timeouts, correlation propagation, outbound
 * logging) applied. Building clients from {@code RestClient.create()} instead would bypass all of
 * them - a very common and very expensive mistake.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    PaymentHttpApi paymentHttpApi(RestClient.Builder builder, AppProperties properties) {
        RestClient restClient = builder.baseUrl(properties.payments().baseUrl()).build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PaymentHttpApi.class);
    }
}
