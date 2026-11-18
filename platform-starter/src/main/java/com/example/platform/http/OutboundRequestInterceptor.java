package com.example.platform.http;

import java.io.IOException;

import com.example.platform.correlation.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propagates the correlation id to downstream services and logs one line per outbound call.
 *
 * <p>Interceptors are the right extension point for {@code RestClient}/{@code RestTemplate}
 * cross-cutting concerns (auth headers, tracing, logging, request ids).
 *
 * <p>WARNING: do not read {@code response.getBody()} in an interceptor unless the request factory
 * buffers the stream - the body can only be consumed once. Wrap with
 * {@code BufferingClientHttpRequestFactory} if you truly need body logging, and never log bodies
 * that may contain PII.
 */
public class OutboundRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundRequestInterceptor.class);

    private final String correlationHeader;
    private final boolean logRequests;

    public OutboundRequestInterceptor(String correlationHeader, boolean logRequests) {
        this.correlationHeader = correlationHeader;
        this.logRequests = logRequests;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String correlationId = CorrelationContext.get();
        if (correlationId != null && !request.getHeaders().containsKey(correlationHeader)) {
            request.getHeaders().add(correlationHeader, correlationId);
        }
        if (!logRequests) {
            return execution.execute(request, body);
        }
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.info("outbound {} {} -> {} in {}ms", request.getMethod(), request.getURI(),
                    response.getStatusCode().value(), elapsedMillis(start));
            return response;
        } catch (IOException | RuntimeException ex) {
            log.warn("outbound {} {} -> {} after {}ms", request.getMethod(), request.getURI(),
                    ex.getClass().getSimpleName(), elapsedMillis(start));
            throw ex;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
