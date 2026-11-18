package com.example.orders.api;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.orders.domain.InsufficientStockException;
import com.example.orders.domain.OrderNotFoundException;
import com.example.orders.payments.PaymentExceptions.PermanentPaymentException;
import com.example.platform.correlation.CorrelationContext;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralised error handling, producing RFC 7807 {@code application/problem+json}.
 *
 * <h2>Why extend {@link ResponseEntityExceptionHandler}?</h2>
 * It already maps around twenty Spring MVC exceptions (unreadable body, missing parameter,
 * unsupported media type, no handler found, ...) to sensible statuses and ProblemDetail bodies.
 * Extending it and overriding only what you care about is strictly better than writing an advice
 * from scratch and rediscovering each of those cases in production.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Map exception TYPE to status in exactly one place. Scattered {@code @ResponseStatus}
 *       annotations and {@code ResponseStatusException} throws always drift apart.</li>
 *   <li>Never leak stack traces or SQL. Log the detail, return a correlation id, and let support
 *       join the two.</li>
 *   <li>Choose 409 vs 422 vs 400 deliberately: 400 = malformed, 422 = well-formed but invalid,
 *       409 = conflicts with current state (optimistic lock, duplicate, illegal transition).</li>
 *   <li>Add {@code Retry-After} whenever you return 429 or 503 - clients and sensible retry
 *       policies honour it.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://errors.example.com/orders/";

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage(), "order-not-found", ex);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail handleStock(InsufficientStockException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Insufficient stock",
                ex.getMessage(), "insufficient-stock", ex);
        // Problem details are extensible: machine-readable fields beat parsing the message.
        problem.setProperty("sku", ex.getSku());
        problem.setProperty("requested", ex.getRequested());
        problem.setProperty("available", ex.getAvailable());
        return problem;
    }

    /**
     * A database constraint said no - most often a unique index.
     *
     * <p>Mapping this to 409 rather than letting it reach the catch-all matters: a duplicate
     * {@code order_number} is a client-visible conflict they can act on, not an internal error.
     * Do NOT echo {@code ex.getMessage()} here - it contains the constraint name, the table and
     * often the offending values, which is a schema leak.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data.", "data-conflict", ex);
    }

    /** The {@code @Version} column doing its job. 409 tells the client to re-read and retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The resource was modified by another request. Re-read it and try again.",
                "concurrent-modification", ex);
    }

    /** Lock wait timeout: transient, so advertise a retry. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handlePessimisticLock(PessimisticLockingFailureException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Resource busy",
                "Could not acquire a lock in time. Please retry.", "resource-busy", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    /** Circuit breaker is open: 503 plus Retry-After, never a 500. */
    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ProblemDetail> handleBreakerOpen(CallNotPermittedException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable",
                "A downstream dependency is unavailable. Please retry shortly.", "dependency-unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    @ExceptionHandler(PermanentPaymentException.class)
    ProblemDetail handlePaymentRejected(PermanentPaymentException ex) {
        return problem(HttpStatus.BAD_GATEWAY, "Payment rejected", ex.getMessage(), "payment-rejected", ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", ex.getMessage(), "invalid-state", ex);
    }

    /** Violations on method parameters, as opposed to a request body. */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "One or more parameters are invalid.", "validation-failed", ex);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> errors.put(v.getPropertyPath().toString(), v.getMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Body validation. Overriding the inherited handler keeps ProblemDetail while adding a
     * field-to-message map - the single most useful thing an API can hand a frontend.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request body failed validation.", "validation-failed", ex);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.put(error.getObjectName(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Spring 6.1+ performs method validation on controller parameters WITHOUT
     * {@code @Validated} on the class, and reports it as this exception rather than as
     * {@code ConstraintViolationException}. Both paths exist, and which one you get depends on
     * something as invisible as whether the class carries {@code @Validated}:
     *
     * <table border="1">
     *   <caption>Which exception a bad path variable produces</caption>
     *   <tr><th>Controller</th><th>Exception</th></tr>
     *   <tr><td>{@code @Validated} on the class</td><td>{@code ConstraintViolationException}</td></tr>
     *   <tr><td>no {@code @Validated}</td><td>{@code HandlerMethodValidationException}</td></tr>
     * </table>
     *
     * Handle both, or removing one annotation silently changes your error contract.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "One or more parameters are invalid.", "validation-failed", ex);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getAllValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.put(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage())));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Last resort. Log at ERROR with the stack trace, return something opaque but traceable.
     * A handler for {@code Exception} also swallows programming errors, so the correlation id in
     * the response is what keeps the 500 diagnosable.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred. Quote the correlation id when contacting support.",
                "internal-error", ex);
    }

    /**
     * Log-level discipline, in one place.
     *
     * <p>The rule: <b>4xx is the client's problem, 5xx is yours.</b> A 404 logged at ERROR with a
     * stack trace trains everyone to ignore ERROR, and a 500 logged at DEBUG means you find out
     * from a customer. Equally important is what this method does NOT do - it does not log and
     * rethrow. Every exception here is terminal: it is being turned into a response, so it is
     * logged exactly once. "Log and rethrow" is how one failure becomes five stack traces that
     * look like five failures.
     */
    private void logByStatus(HttpStatus status, Exception ex) {
        if (status.is5xxServerError()) {
            log.error("{} -> {}", ex.getClass().getSimpleName(), status, ex);
        } else if (log.isDebugEnabled()) {
            log.debug("{} -> {}: {}", ex.getClass().getSimpleName(), status, ex.getMessage());
        }
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
            Exception ex) {
        logByStatus(status, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("correlationId", CorrelationContext.get());
        return problem;
    }
}
