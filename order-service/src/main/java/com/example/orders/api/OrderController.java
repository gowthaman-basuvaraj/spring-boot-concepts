package com.example.orders.api;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderStatus;
import com.example.orders.repo.OrderAnalyticsDao;
import com.example.orders.repo.StatusTotal;
import com.example.orders.service.OrderQueryService;
import com.example.orders.service.OrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;

/**
 * The HTTP layer. Thin by design: validate, delegate, map the result to a status code.
 *
 * <p>What a controller must NOT contain: business rules, {@code @Transactional}, entity references
 * in its signatures, or {@code try/catch} around service calls (that is what
 * {@link ApiExceptionHandler} is for).
 */
@RestController
@RequestMapping("/api/orders")
// Required for constraints on method PARAMETERS (@PathVariable @Email below). Without @Validated
// those annotations are ignored; violations then surface as ConstraintViolationException, which
// ApiExceptionHandler maps to 400.
@org.springframework.validation.annotation.Validated
public class OrderController {

    private final OrderService orders;
    private final OrderQueryService queries;

    public OrderController(OrderService orders, OrderQueryService queries) {
        this.orders = orders;
        this.queries = queries;
    }

    /**
     * 201 Created with a {@code Location} header - the correct answer for a resource-creating POST.
     *
     * <p>{@code @Valid} is what actually triggers bean validation; without it the annotations on
     * the DTO are decoration. A violation becomes {@code MethodArgumentNotValidException}, which
     * the exception handler turns into a 400 with field-level detail.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody CreateOrderRequest request,
            @CurrentActor String actor) {
        Order created = orders.placeOrder(request, actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{orderNumber}")
                .buildAndExpand(created.getOrderNumber())
                .toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(created));
    }

    /**
     * {@code ETag} + {@code Cache-Control} from the optimistic-locking version. A client can then
     * send {@code If-None-Match} and get a cheap 304 - the version column pays for itself twice.
     */
    @GetMapping("/{orderNumber}")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderNumber) {
        OrderResponse response = queries.byNumber(orderNumber);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                .body(response);
    }

    /**
     * Paged, filtered list.
     *
     * <p>{@code Pageable} is resolved by Spring Data's argument resolver from
     * {@code ?page=0&size=20&sort=createdAt,desc}. {@code @PageableDefault} caps the size, which
     * matters: without it a client can ask for {@code size=100000} and OOM the service. Also set
     * {@code spring.data.web.pageable.max-page-size}.
     *
     * <p>The {@code status} parameter is converted by the custom converter in {@code WebConfig},
     * so {@code ?status=paid} works as well as {@code ?status=PAID}.
     */
    @GetMapping
    public Page<OrderResponse> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) BigDecimal minTotal,
            @RequestParam(required = false) String sku,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return queries.search(status, email, minTotal, sku, pageable);
    }

    /**
     * Cancellation is modelled as a POST to a sub-resource rather than a PATCH with a status field:
     * it is an operation with its own rules, not a field assignment. Either is defensible; what is
     * not defensible is letting clients PUT arbitrary status transitions.
     */
    @PostMapping("/{orderNumber}/cancellation")
    public OrderResponse cancel(@PathVariable String orderNumber) {
        return OrderResponse.from(orders.cancel(orderNumber));
    }

    @GetMapping("/statistics")
    public Map<String, Object> statistics(
            @RequestParam(defaultValue = "30") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<StatusTotal> totals = queries.statusTotalsSince(since);
        List<OrderAnalyticsDao.DailyRevenue> revenue = queries.dailyRevenue(since);
        return Map.of("since", since, "byStatus", totals, "dailyRevenue", revenue);
    }

    @GetMapping("/customers/{email}/lifetime-value")
    public Map<String, Object> lifetimeValue(@PathVariable @Email String email) {
        return Map.of("customerEmail", email, "lifetimeValue", queries.lifetimeValue(email));
    }
}
