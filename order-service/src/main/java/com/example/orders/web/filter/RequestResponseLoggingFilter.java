package com.example.orders.web.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs one line per request, optionally with bodies - and shows why body logging is harder than it
 * looks.
 *
 * <h2>The problem</h2>
 * A servlet request body is a <b>one-shot stream</b>. Read it in a filter and the controller gets
 * an empty body; read it after the chain and there is nothing left. You cannot simply
 * {@code request.getInputStream().readAllBytes()} and carry on.
 *
 * <h2>The fix, and its cost</h2>
 * {@link ContentCachingRequestWrapper} buffers what the application reads, so you can retrieve it
 * <em>after</em> the chain has run. {@link ContentCachingResponseWrapper} does the same outbound,
 * but it also means <b>you must call {@code copyBodyToResponse()}</b> or the client receives an
 * empty response - the single most common bug in home-made logging filters.
 *
 * <p>The cost is real: every body is held in memory. Hence the size cap, and hence bodies being off
 * by default. Never log bodies in production without knowing they contain no PII, card numbers or
 * tokens; a log aggregator is not an approved data store.
 *
 * <h2>Alternatives</h2>
 * Boot ships {@code CommonsRequestLoggingFilter} for the simple cases, and
 * {@code spring.mvc.log-request-details=true} adds parameters to the DEBUG log. Prefer those unless
 * you need something they cannot express.
 */
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    private final boolean logBodies;
    private final int maxBodyChars;

    public RequestResponseLoggingFilter(boolean logBodies, int maxBodyChars) {
        this.logBodies = logBodies;
        this.maxBodyChars = maxBodyChars;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!logBodies) {
            long start = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                log.info("{} {}{} -> {} in {}ms", request.getMethod(), request.getRequestURI(),
                        query(request), response.getStatus(), millisSince(start));
            }
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            log.info("{} {}{} -> {} in {}ms req={} res={}",
                    request.getMethod(), request.getRequestURI(), query(request),
                    wrappedResponse.getStatus(), millisSince(start),
                    body(wrappedRequest.getContentAsByteArray()),
                    body(wrappedResponse.getContentAsByteArray()));
            // MANDATORY. Without this the client gets a 200 with an empty body.
            wrappedResponse.copyBodyToResponse();
        }
    }

    /** Health probes are high-frequency and uninteresting; keep them out of the log. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health") || uri.startsWith("/h2-console");
    }

    private String body(byte[] raw) {
        if (raw.length == 0) {
            return "<empty>";
        }
        String text = new String(raw, StandardCharsets.UTF_8).replaceAll("\s+", " ");
        return text.length() <= maxBodyChars ? text : text.substring(0, maxBodyChars) + "...<truncated>";
    }

    private static String query(HttpServletRequest request) {
        return request.getQueryString() == null ? "" : "?" + request.getQueryString();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
