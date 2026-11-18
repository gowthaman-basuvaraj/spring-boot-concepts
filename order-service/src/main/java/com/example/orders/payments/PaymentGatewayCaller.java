package com.example.orders.payments;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;

import com.example.orders.config.AppProperties;
import com.example.orders.payments.PaymentDtos.ChargeRequest;
import com.example.orders.payments.PaymentDtos.ChargeResponse;
import com.example.orders.payments.PaymentExceptions.PaymentDeclinedException;
import com.example.orders.payments.PaymentExceptions.PaymentException;
import com.example.orders.payments.PaymentExceptions.PermanentPaymentException;
import com.example.orders.payments.PaymentExceptions.TransientPaymentException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

/**
 * The retrying HTTP caller. One job: issue the request, classify the failure, and retry transient
 * failures with exponential backoff plus jitter.
 *
 * <h2>Why this is a separate bean from {@link PaymentGatewayClient}</h2>
 * {@code @Retryable} and resilience4j's {@code @CircuitBreaker} are both proxy-based. If both
 * annotations sat on methods of the same class and one called the other internally, the inner
 * annotation would be IGNORED - self-invocation never passes through the proxy. Two beans make the
 * nesting explicit and correct:
 *
 * <pre>
 *   PaymentGatewayClient.charge()       &lt;- @CircuitBreaker (outer)
 *     -&gt; PaymentGatewayCaller.charge()  &lt;- @Retryable      (inner)
 *          -&gt; RestClient                &lt;- connect/read timeouts
 * </pre>
 *
 * <h2>Why retry INSIDE the circuit breaker?</h2>
 * The breaker then sees one logical failure per business call, so {@code maxAttempts=3} does not
 * fill a 10-call sliding window with three failures from a single request. The opposite nesting
 * (retry outside the breaker) is also legitimate - it fails fast while the breaker is open - but
 * then you must size the breaker's window with the retry count in mind. Choose deliberately.
 *
 * <h2>Backoff</h2>
 * {@code delay=200ms, multiplier=2, maxDelay=2s} yields 200ms, 400ms, 800ms, ... capped at 2s.
 * {@code random=true} is not optional at scale: without jitter every instance that failed during
 * the same blip retries at the same millisecond and recreates the outage (thundering herd).
 */
@Component
public class PaymentGatewayCaller {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayCaller.class);

    private final RestClient restClient;
    private final AppProperties.Payments config;

    public PaymentGatewayCaller(RestClient.Builder builder, AppProperties properties) {
        // The injected builder already carries platform defaults (timeouts, correlation header
        // propagation, outbound logging) from PlatformRestClientCustomizer. Only per-client
        // concerns belong here.
        this.config = properties.payments();
        this.restClient = builder
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * {@code maxAttempts} counts the FIRST call, so 3 means "try, retry, retry".
     *
     * <p>{@code retryFor}/{@code noRetryFor} replace the old {@code include}/{@code exclude}.
     * Listing the transient type explicitly - rather than {@code Exception.class} - is the whole
     * point: a {@code PermanentPaymentException} must escape on the first attempt.
     *
     * <p>The {@code *Expression} variants read properties, which is how the policy becomes
     * configurable per environment without a code change.
     */
    @Retryable(
            retryFor = { TransientPaymentException.class },
            noRetryFor = { PermanentPaymentException.class },
            maxAttemptsExpression = "${app.payments.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${app.payments.initial-backoff-millis:200}",
                    multiplierExpression = "${app.payments.backoff-multiplier:2.0}",
                    maxDelayExpression = "${app.payments.max-backoff-millis:2000}",
                    random = true),
            listeners = "retryMetricsListener")
    public ChargeResponse charge(ChargeRequest request) {
        try {
            return restClient.post()
                    .uri("/charges")
                    // The idempotency key is what makes retrying a POST safe. Derive it from the
                    // business operation, never from the attempt: it must be stable across retries.
                    .header("Idempotency-Key", request.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> classify(res));
        } catch (ResourceAccessException ex) {
            // Wraps IOException: connect timeout, read timeout, connection reset, DNS failure.
            // The request may or may not have been processed - the idempotency key covers us.
            throw new TransientPaymentException("transport failure calling payment gateway", ex, null);
        }
    }

    /**
     * Maps the HTTP response onto the exception taxonomy.
     *
     * <p>{@code exchange()} exposes status, headers and body in one place and - unlike
     * {@code retrieve()} - does not throw on error statuses, so all classification stays here.
     * {@code exchange()} also closes the response for you.
     */
    private ChargeResponse classify(ConvertibleClientHttpResponse res) throws IOException {
        int code = res.getStatusCode().value();
        if (res.getStatusCode().is2xxSuccessful()) {
            ChargeResponse body = res.bodyTo(ChargeResponse.class);
            if (body == null) {
                throw new TransientPaymentException("empty body from payment gateway");
            }
            if (!body.succeeded()) {
                // HTTP 200 carrying a business decline: permanent, do not retry.
                throw new PaymentDeclinedException("declined: " + body.message());
            }
            return body;
        }
        String body = safeBody(res);
        Duration retryAfter = parseRetryAfter(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        if (code == 429) {
            throw new TransientPaymentException("rate limited by gateway: " + body, null, retryAfter);
        }
        if (code == 408 || code >= 500) {
            throw new TransientPaymentException("gateway " + code + ": " + body, null, retryAfter);
        }
        if (code == 402) {
            throw new PaymentDeclinedException("declined by gateway: " + body);
        }
        log.warn("permanent failure from payment gateway: status={} body={}", code, body);
        throw new PermanentPaymentException("gateway rejected the request (" + code + "): " + body, code);
    }

    /**
     * {@code @Recover} is spring-retry's fallback. Its matching rules bite everybody once:
     * <ul>
     *   <li>The return type must match the {@code @Retryable} method's.</li>
     *   <li>The first parameter is the exception; the rest must match the original argument list
     *       (or be omitted entirely).</li>
     *   <li><b>It is consulted for NON-retryable exceptions too.</b> Once a class declares any
     *       {@code @Recover} method, spring-retry wires a recovery callback for the whole class, so
     *       an exception listed in {@code noRetryFor} also goes looking for a recoverer. If none
     *       matches its type you do not get your original exception - you get
     *       {@code ExhaustedRetryException: Cannot locate recovery method}, which then defeats
     *       downstream exception handling (we hit exactly this: a 402 decline arrived at the
     *       circuit breaker as an ExhaustedRetryException and the order was never marked failed).</li>
     * </ul>
     * Hence the parameter is the shared base type {@link PaymentException}, not
     * {@code TransientPaymentException}. Rethrowing is deliberate: the circuit breaker upstream
     * needs to see the real failure and choose the right fallback.
     *
     * <p>If you have nothing useful to do in recovery, declare NO {@code @Recover} at all - the
     * last exception is then rethrown as-is.
     */
    @Recover
    ChargeResponse recover(PaymentException ex, ChargeRequest request) {
        log.error("payment attempts finished unsuccessfully for order {}: {}",
                request.orderNumber(), ex.toString());
        throw ex;
    }

    private static String safeBody(ConvertibleClientHttpResponse res) {
        try {
            String body = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return body.length() > 300 ? body.substring(0, 300) : body;
        } catch (IOException | RuntimeException ex) {
            return "<unreadable body>";
        }
    }

    /**
     * {@code Retry-After} is either delta-seconds ({@code 120}) or an HTTP date
     * ({@code Wed, 21 Oct 2026 07:28:00 GMT}). Honour it, but CAP it: a buggy or hostile upstream
     * must not be able to park your threads for an hour.
     */
    Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Duration value;
        try {
            value = Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException notANumber) {
            try {
                var when = java.time.ZonedDateTime.parse(header.trim(),
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                value = Duration.between(java.time.ZonedDateTime.now(when.getZone()), when);
            } catch (DateTimeParseException unparseable) {
                return null;
            }
        }
        if (value.isNegative()) {
            return Duration.ZERO;
        }
        Duration cap = config.maxRetryAfter();
        return value.compareTo(cap) > 0 ? cap : value;
    }
}
