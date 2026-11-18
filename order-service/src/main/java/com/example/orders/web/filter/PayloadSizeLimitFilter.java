package com.example.orders.web.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies with 413 before anything expensive happens.
 *
 * <p>Why a filter and not validation? Because by the time a {@code @Valid @RequestBody} is
 * rejected, Jackson has already parsed the whole payload and allocated the object graph - which is
 * exactly what an attacker sending a 200MB JSON array wants. A filter checks
 * {@code Content-Length} and refuses before the body is read.
 *
 * <p>This is defence in depth, not the only control: also set
 * {@code server.tomcat.max-swallow-size}, {@code server.max-http-request-header-size}, and
 * {@code spring.servlet.multipart.max-request-size} for uploads. A chunked request has no
 * {@code Content-Length} at all, so a hard limit still belongs in the container or the ingress.
 *
 * <p>Note the hand-written ProblemDetail: a filter runs OUTSIDE {@code DispatcherServlet}, so
 * {@code @RestControllerAdvice} never sees what happens here. Anything a filter rejects must write
 * its own response, and keeping that response in the same shape as the rest of the API is on you.
 */
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public PayloadSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://errors.example.com/orders/payload-too-large",\
                    "title":"Payload too large","status":413,\
                    "detail":"Request body of %d bytes exceeds the %d byte limit."}"""
                    .formatted(declared, maxBytes));
            return; // do NOT call the chain
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // GET/DELETE have no body worth checking.
        return !request.getMethod().equals("POST")
                && !request.getMethod().equals("PUT")
                && !request.getMethod().equals("PATCH");
    }
}
