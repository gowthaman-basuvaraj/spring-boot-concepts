package com.example.orders.config;

import java.time.Duration;

import com.example.orders.clients.CatalogHttpInterface;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Builds the HTTP clients that are not auto-configured for you.
 *
 * <p>The point of this class: <b>every client style needs its timeouts configured in a different
 * place</b>, and that is the single most common production gap in a Spring application. Here they
 * are, side by side.
 */
@Configuration(proxyBeanMethods = false)
public class ClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientsConfig.class);

    /**
     * {@code RestTemplate} is NOT auto-configured as a bean - only {@code RestTemplateBuilder} is,
     * precisely so you are forced to decide its settings once. {@code new RestTemplate()} has no
     * timeouts at all.
     */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * {@code WebClient.Builder} IS auto-configured, but with no timeouts: for the reactive stack
     * they live on the connector, not the builder. A missing response timeout here is unbounded.
     */
    @Bean
    WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(5)));
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logOutbound())
                .build();
    }

    /**
     * The WebClient equivalent of a {@code ClientHttpRequestInterceptor}. Note it must return the
     * {@code Mono} - doing work eagerly here runs it at assembly time, not per request, which is
     * the classic reactive mistake.
     */
    private ExchangeFilterFunction logOutbound() {
        return (request, next) -> next.exchange(request)
                .doOnNext(response -> log.debug("outbound[webclient] {} {} -> {}",
                        request.method(), request.url(), response.statusCode()))
                .doOnError(ex -> log.warn("outbound[webclient] {} {} -> {}",
                        request.method(), request.url(), ex.toString()));
    }

    /**
     * Spring's declarative client, generated over the platform-configured {@code RestClient} - so
     * it inherits the timeouts, the correlation-id propagation and the outbound logging that the
     * Feign client has to re-implement for itself.
     */
    @Bean
    CatalogHttpInterface catalogHttpInterface(org.springframework.web.client.RestClient.Builder builder,
            @Value("${app.catalog.base-url}") String baseUrl) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.baseUrl(baseUrl).build()))
                .build()
                .createClient(CatalogHttpInterface.class);
    }

    /**
     * Give Feign the correlation id that the platform starter adds to every other outbound call.
     * A {@code RequestInterceptor} is Feign's own extension point; it knows nothing about
     * {@code RestClientCustomizer}.
     */
    @Bean
    feign.RequestInterceptor feignCorrelationIdInterceptor() {
        return template -> {
            String correlationId = com.example.platform.correlation.CorrelationContext.get();
            if (correlationId != null) {
                template.header("X-Correlation-Id", correlationId);
            }
        };
    }
}
