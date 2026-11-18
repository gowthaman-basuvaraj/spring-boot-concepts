package com.example.orders.payments;

import com.example.orders.payments.PaymentDtos.ChargeRequest;
import com.example.orders.payments.PaymentDtos.ChargeResponse;

import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative HTTP client (Spring 6 "HTTP interface").
 *
 * <p>This is the modern replacement for OpenFeign in a plain-Spring stack: define an interface with
 * {@code @HttpExchange} methods, and let {@code HttpServiceProxyFactory} generate the
 * implementation over a {@code RestClient} (blocking), {@code WebClient} (reactive) or
 * {@code RestTemplate} adapter. See {@code HttpClientConfig} for the wiring.
 *
 * <p>Trade-offs versus using {@code RestClient} directly:
 * <ul>
 *   <li>+ The contract is readable in one screen and trivially mockable in tests.</li>
 *   <li>+ No boilerplate for URI building, headers or body conversion.</li>
 *   <li>- Error handling is centralised in the adapter's status handler, so per-call classification
 *     (the thing {@code PaymentGatewayCaller} does) is harder to express.</li>
 *   <li>- A 4xx/5xx becomes a {@code RestClientResponseException}, which you must map yourself.</li>
 * </ul>
 * Rule of thumb: declarative interfaces for simple, well-behaved APIs; an explicit
 * {@code RestClient} for anything where failure classification matters.
 */
@HttpExchange(url = "/", accept = "application/json", contentType = "application/json")
public interface PaymentHttpApi {

    @PostExchange("/charges")
    ChargeResponse charge(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @org.springframework.web.bind.annotation.RequestBody ChargeRequest request);

    @GetExchange("/charges/{reference}")
    ChargeResponse lookup(@org.springframework.web.bind.annotation.PathVariable String reference);
}
