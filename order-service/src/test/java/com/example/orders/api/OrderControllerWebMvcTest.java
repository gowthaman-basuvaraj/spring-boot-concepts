package com.example.orders.api;

import java.math.BigDecimal;
import java.util.List;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderNotFoundException;
import com.example.orders.domain.OrderStatus;
import com.example.orders.service.OrderQueryService;
import com.example.orders.service.OrderService;
import com.example.platform.autoconfigure.PlatformAutoConfiguration;
import com.example.platform.autoconfigure.PlatformConversionAutoConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
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
 * Web slice: {@code @WebMvcTest} loads the MVC infrastructure (controllers, converters,
 * {@code @RestControllerAdvice}, argument resolvers, filters) and NOTHING else - no JPA, no
 * DataSource, no RestClient. The services are replaced with mocks.
 *
 * <p>That is exactly the right scope for testing HTTP concerns: status codes, headers, JSON shape,
 * validation messages, error mapping. Testing those through a full {@code @SpringBootTest} is
 * slower and conflates "the controller is wrong" with "the database is wrong".
 *
 * <p>Note what still runs for real here: bean validation on the request body, the custom
 * {@code @CurrentActor} resolver from {@code WebConfig}, and the ProblemDetail advice.
 *
 * <p>What does NOT run by default: a slice test applies only a fixed allow-list of
 * auto-configurations, so third-party and in-house starters are switched off - the correlation
 * filter from {@code platform-starter} included. {@code @ImportAutoConfiguration} opts a specific
 * one back in. Being able to name that annotation is the difference between "my filter does not
 * work in tests" and a thirty-second fix.
 */
@WebMvcTest(OrderController.class)
@ImportAutoConfiguration({ PlatformConversionAutoConfiguration.class, PlatformAutoConfiguration.class })
@DisplayName("HTTP layer: status codes, headers, validation, problem details")
class OrderControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    // @MockitoBean (Boot 3.4+) replaces the deprecated @MockBean. Both REPLACE a bean definition,
    // which makes each distinct set of mocks a separate context-cache key - the usual reason a
    // test suite is slower than it needs to be.
    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @Test
    @DisplayName("POST returns 201 with a Location header")
    void createReturns201() throws Exception {
        given(orderService.placeOrder(any(), anyString())).willReturn(sampleOrder());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "alice")
                        .content("""
                                {
                                  "customerEmail": "alice@example.com",
                                  "currency": "EUR",
                                  "lines": [{"sku": "SKU-LAPTOP", "quantity": 1, "unitPrice": 999.99}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/orders/ord-1")))
                .andExpect(jsonPath("$.orderNumber").value("ord-1"))
                .andExpect(jsonPath("$.totalAmount").value(999.99));
    }

    @Test
    @DisplayName("invalid body returns 400 problem+json with per-field errors")
    void validationFailureReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerEmail": "not-an-email", "currency": "eur", "lines": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.lines").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("unknown JSON property is rejected rather than ignored")
    void unknownPropertyIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerEmail": "a@b.com", "currency": "EUR", "typo": 1,
                                 "lines": [{"sku": "S", "quantity": 1, "unitPrice": 1.00}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing order maps to 404 problem+json")
    void notFoundMapsTo404() throws Exception {
        willThrow(new OrderNotFoundException("ord-404"))
                .given(orderQueryService).byNumber("ord-404");

        mockMvc.perform(get("/api/orders/ord-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://errors.example.com/orders/order-not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET exposes an ETag derived from the @Version column")
    void getExposesETag() throws Exception {
        given(orderQueryService.byNumber("ord-1")).willReturn(OrderResponse.from(sampleOrder()));

        mockMvc.perform(get("/api/orders/ord-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", "max-age=30, private"));
    }

    @Test
    @DisplayName("the correlation filter echoes the incoming id back")
    void correlationIdIsEchoed() throws Exception {
        given(orderQueryService.byNumber("ord-1")).willReturn(OrderResponse.from(sampleOrder()));

        mockMvc.perform(get("/api/orders/ord-1").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    @DisplayName("a bad enum value in a query parameter is a 400, not a 500")
    void invalidEnumParameter() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "NOT_A_STATUS"))
                .andExpect(status().is4xxClientError());
    }

    private static Order sampleOrder() {
        Order order = new Order("ord-1", "alice@example.com", "EUR");
        order.addLine("SKU-LAPTOP", 1, new BigDecimal("999.99"));
        return order;
    }
}
