package com.example.orders.api;

import com.example.orders.domain.InsufficientStockException;
import com.example.orders.domain.OrderNotFoundException;
import com.example.orders.service.OrderQueryService;
import com.example.orders.service.OrderService;
import com.example.platform.autoconfigure.PlatformAutoConfiguration;
import com.example.platform.autoconfigure.PlatformConversionAutoConfiguration;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract, pinned.
 *
 * <p>Error responses are part of your public API and they break silently: nothing fails to compile
 * when a 409 quietly becomes a 500, and clients find out in production. Every status mapping and
 * every field a client might branch on is asserted here.
 */
@WebMvcTest(OrderController.class)
@ImportAutoConfiguration({ PlatformConversionAutoConfiguration.class, PlatformAutoConfiguration.class })
@DisplayName("Error contract: RFC 7807 problem details")
class ErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @Test
    @DisplayName("every error is application/problem+json with the standard members")
    void standardMembers() throws Exception {
        willThrow(new OrderNotFoundException("ord-404")).given(orderQueryService).byNumber("ord-404");

        mockMvc.perform(get("/api/orders/ord-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/order-not-found"))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/api/orders/ord-404"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("domain conflicts carry machine-readable extensions, not just a sentence")
    void extensionsAreMachineReadable() throws Exception {
        given(orderService.placeOrder(any(), anyString()))
                .willThrow(new InsufficientStockException("SKU-DOCK", 999, 10));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/insufficient-stock"))
                .andExpect(jsonPath("$.sku").value("SKU-DOCK"))
                .andExpect(jsonPath("$.requested").value(999))
                .andExpect(jsonPath("$.available").value(10));
    }

    @Test
    @DisplayName("body validation returns a field -> message map")
    void bodyValidationErrors() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"nope\",\"currency\":\"eur\",\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/validation-failed"))
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.lines").exists());
    }

    @Test
    @DisplayName("a custom constraint reports through the same contract")
    void customConstraintErrors() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"a@b.com\",\"currency\":\"EUR\","
                                + "\"lines\":[{\"sku\":\"laptop\",\"quantity\":1,\"unitPrice\":9.99}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].sku']")
                        .value(Matchers.containsString("not a valid SKU")));
    }

    @Test
    @DisplayName("a class-level constraint reports against a property, not as a global error")
    void crossFieldConstraintErrors() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"a@b.com\",\"currency\":\"EUR\",\"lines\":["
                                + "{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":9.99},"
                                + "{\"sku\":\"SKU-MOUSE\",\"quantity\":2,\"unitPrice\":9.99}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.lines").value(Matchers.containsString("more than once")));
    }

    @Test
    @DisplayName("method-parameter validation produces the same shape as body validation")
    void parameterValidationErrors() throws Exception {
        mockMvc.perform(get("/api/orders/customers/not-an-email/lifetime-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/validation-failed"))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    @DisplayName("optimistic lock -> 409, so the client knows to re-read and retry")
    void optimisticLockIsAConflict() throws Exception {
        willThrow(new OptimisticLockingFailureException("row moved"))
                .given(orderQueryService).byNumber("ord-1");

        mockMvc.perform(get("/api/orders/ord-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://errors.example.com/orders/concurrent-modification"));
    }

    @Test
    @DisplayName("a database constraint -> 409, and the message does not leak the schema")
    void dataIntegrityIsAConflictAndLeaksNothing() throws Exception {
        given(orderService.placeOrder(any(), anyString())).willThrow(
                new DataIntegrityViolationException("could not execute statement; constraint "
                        + "[uq_orders_order_number]; insert into orders (order_number) values ('dup')"));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/data-conflict"))
                // The constraint name, the table and the value must NOT reach the client.
                .andExpect(content().string(Matchers.not(Matchers.containsString("uq_orders_order_number"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("insert into"))));
    }

    @Test
    @DisplayName("an unexpected failure is opaque but traceable")
    void unexpectedFailuresLeakNothing() throws Exception {
        given(orderService.placeOrder(any(), anyString()))
                .willThrow(new RuntimeException("NPE at com.example.internal.SecretVault:42"));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(content().string(Matchers.not(Matchers.containsString("com.example.internal"))));
    }

    @Test
    @DisplayName("503s advertise Retry-After so clients back off instead of hammering")
    void retryAfterIsAdvertised() throws Exception {
        willThrow(new PessimisticLockingFailureException("lock timeout"))
                .given(orderQueryService).byNumber("ord-busy");

        mockMvc.perform(get("/api/orders/ord-busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    @DisplayName("Spring's own MVC exceptions keep problem+json too")
    void frameworkExceptionsAreProblemDetails() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static String validBody() {
        return "{\"customerEmail\":\"a@b.com\",\"currency\":\"EUR\","
                + "\"lines\":[{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":9.99}]}";
    }
}
