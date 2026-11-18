package com.example.platform.correlation;

import java.io.IOException;

import com.example.platform.autoconfigure.PlatformProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads (or mints) a correlation id and publishes it to the MDC for the duration of the request.
 *
 * <p>Why {@link OncePerRequestFilter}? A plain {@code Filter} runs again on every
 * {@code RequestDispatcher} forward/include and on async dispatches, so cleanup logic ends up
 * running at the wrong time. {@code OncePerRequestFilter} guards against that.
 *
 * <p>Why {@code finally { clear() }}? Servlet containers pool threads. Forgetting to clear leaks
 * the previous request's id into the next one - a classic production head-scratcher.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /** Run early, but after Boot's own request-logging/metrics filters. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final PlatformProperties.Correlation config;

    public CorrelationIdFilter(PlatformProperties.Correlation config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(config.headerName());
        String id = (incoming == null || incoming.isBlank()) ? CorrelationContext.newId() : incoming.trim();
        CorrelationContext.set(id);
        request.setAttribute(CorrelationContext.MDC_KEY, id);
        if (config.echoResponseHeader()) {
            response.setHeader(config.headerName(), id);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    /** Skip the noise: actuator probes do not need correlation ids. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
