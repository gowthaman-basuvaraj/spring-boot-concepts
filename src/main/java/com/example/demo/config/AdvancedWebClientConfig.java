package com.example.demo.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;

@Configuration
public class AdvancedWebClientConfig {

    @Bean
    public WebClient advancedWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(5))
                                .addHandlerLast(new WriteTimeoutHandler(5)));

        return WebClient.builder()
                .baseUrl("https://api.example.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter((request, next) -> {
                    // Add logging filter
                    System.out.println("Making request: " + request.method() + " " + request.url());
                    return next.exchange(request);
                })
                .filter((request, next) -> {
                    // Add retry filter
                    return next.exchange(request)
                            .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1)));
                })
                .defaultHeader(HttpHeaders.USER_AGENT, "SpringBootApp")
                .build();
    }
}
