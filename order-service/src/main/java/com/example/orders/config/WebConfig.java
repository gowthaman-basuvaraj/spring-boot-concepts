package com.example.orders.config;

import java.time.Duration;
import java.util.List;

import com.example.orders.api.ActorArgumentResolver;
import com.example.orders.domain.OrderStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC customization.
 *
 * <p>THE rule: implement {@link WebMvcConfigurer}, never add {@code @EnableWebMvc}. In a Boot
 * application {@code @EnableWebMvc} switches off {@code WebMvcAutoConfiguration} wholesale and you
 * silently lose content negotiation defaults, static resource handling, the configured
 * {@code ObjectMapper}, error handling and more. Boot collects every {@code WebMvcConfigurer} bean
 * and applies them on top of its own defaults.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final ActorArgumentResolver actorArgumentResolver;

    public WebConfig(ActorArgumentResolver actorArgumentResolver) {
        this.actorArgumentResolver = actorArgumentResolver;
    }

    /** Custom {@code @CurrentActor} parameter injection - see {@link ActorArgumentResolver}. */
    @Override
    public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorArgumentResolver);
    }

    /**
     * Converters registered here serve {@code @RequestParam}/{@code @PathVariable} binding - NOT
     * JSON bodies, which are Jackson's job. Boot binds enums case-sensitively; this one accepts
     * {@code ?status=paid}.
     */
    @Override
    public void addFormatters(@NonNull FormatterRegistry registry) {
        registry.addConverter(new Converter<String, OrderStatus>() {
            @Override
            public OrderStatus convert(@NonNull String source) {
                return OrderStatus.valueOf(source.trim().toUpperCase());
            }
        });
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // Interceptors run inside DispatcherServlet and know the handler method - use them for
        // handler-aware concerns. Filters sit outside MVC and see every request (static resources,
        // error dispatches, other servlets) - use them for correlation ids, auth, compression.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                    @NonNull Object handler) {
                request.setAttribute("handlerStartNanos", System.nanoTime());
                return true;
            }

            @Override
            public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                    @NonNull Object handler, Exception ex) {
                Object start = request.getAttribute("handlerStartNanos");
                if (start instanceof Long startNanos && log.isDebugEnabled()) {
                    log.debug("{} {} -> {} in {}ms", request.getMethod(), request.getRequestURI(),
                            response.getStatus(), (System.nanoTime() - startNanos) / 1_000_000);
                }
            }
        }).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:[*]")
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                .maxAge(Duration.ofMinutes(30).toSeconds());
    }
}
