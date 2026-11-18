package com.example.orders.api;

import com.example.platform.correlation.CorrelationContext;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * A custom {@link HandlerMethodArgumentResolver} - the extension point behind
 * {@code @AuthenticationPrincipal}, {@code @PageableDefault} and friends.
 *
 * <p>Registered in {@code WebConfig#addArgumentResolvers}. Two rules:
 * <ul>
 *   <li>{@code supportsParameter} must be cheap and exact; it is called for every parameter of
 *       every handler on every request (results are cached per method, but keep it trivial).</li>
 *   <li>Returning {@code null} is legal and means "argument is null" - throw only when the
 *       contract says the value is mandatory.</li>
 * </ul>
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    static final String ACTOR_HEADER = "X-Actor";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentActor.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String actor = request == null ? null : request.getHeader(ACTOR_HEADER);
        if (actor == null || actor.isBlank()) {
            CurrentActor annotation = parameter.getParameterAnnotation(CurrentActor.class);
            if (annotation != null && annotation.required()) {
                throw new ServletRequestBindingException("Missing required header " + ACTOR_HEADER);
            }
            // Fall back to something traceable rather than "unknown".
            return "anonymous:" + CorrelationContext.getOrGenerate();
        }
        return actor.trim();
    }
}
