package com.example.orders.web.filter;

import com.example.platform.correlation.CorrelationIdFilter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the application's filters, in a deliberate order.
 *
 * <h2>Order matters, and the numbers are not arbitrary</h2>
 * Lower value = earlier (more outer). The chain in this application ends up as:
 *
 * <pre>
 *   -2147483628  CorrelationIdFilter        (platform starter) - so EVERYTHING below is logged with an id
 *   -2147483618  PayloadSizeLimitFilter     - reject huge bodies before anyone allocates
 *          ...   Boot's own filters         (metrics, request context, form content, encoding)
 *          -100  [Spring Security would go here: SecurityProperties.DEFAULT_FILTER_ORDER]
 *             0  RequestResponseLoggingFilter - log the request as the application really saw it
 *          ...   DispatcherServlet          -> interceptors -> controller
 * </pre>
 *
 * <h2>Why register with {@code FilterRegistrationBean} rather than {@code @Component}?</h2>
 * <ul>
 *   <li>A {@code Filter} that is also a {@code @Component} is auto-registered for {@code /*} with
 *       order {@code LOWEST_PRECEDENCE} - you get no control, and if you ALSO register it manually
 *       it runs <b>twice</b>.</li>
 *   <li>{@code FilterRegistrationBean} gives explicit order, URL patterns, dispatcher types and a
 *       name that shows up in {@code /actuator/mappings}.</li>
 *   <li>{@code setDispatcherTypes} matters for error dispatches: by default a filter does NOT run
 *       on {@code DispatcherType.ERROR}, so your correlation id is missing from the very responses
 *       you most want to trace.</li>
 * </ul>
 *
 * <h2>Filter or interceptor or advice?</h2>
 * Filter: outside MVC, sees every request including static resources and error dispatches, can wrap
 * the request/response, cannot see the handler method. Interceptor: inside MVC, knows the handler.
 * {@code @RestControllerAdvice}: only for exceptions thrown by a handler - it cannot help with
 * anything a filter rejected.
 */
@Configuration(proxyBeanMethods = false)
public class FilterChainConfig {

    @Bean
    FilterRegistrationBean<PayloadSizeLimitFilter> payloadSizeLimitFilter(
            @org.springframework.beans.factory.annotation.Value("${app.web.max-request-bytes:1048576}")
            long maxBytes) {
        FilterRegistrationBean<PayloadSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new PayloadSizeLimitFilter(maxBytes));
        // Immediately after the correlation filter: cheap, and it protects everything behind it.
        registration.setOrder(CorrelationIdFilter.ORDER + 10);
        registration.addUrlPatterns("/api/*");
        registration.setName("payloadSizeLimitFilter");
        return registration;
    }

    /**
     * Off by default: it is genuinely useful locally and genuinely dangerous in production, so it
     * is opt-in rather than something you have to remember to disable.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.web", name = "request-logging", havingValue = "true")
    FilterRegistrationBean<RequestResponseLoggingFilter> requestLoggingFilter(
            @org.springframework.beans.factory.annotation.Value("${app.web.log-bodies:false}")
            boolean logBodies) {
        FilterRegistrationBean<RequestResponseLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestResponseLoggingFilter(logBodies, 500));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.addUrlPatterns("/api/*", "/stub/*");
        registration.setName("requestResponseLoggingFilter");
        return registration;
    }
}
