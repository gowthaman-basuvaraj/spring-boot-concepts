package com.example.orders.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the calling actor into a handler method parameter.
 *
 * <p>Resolved by {@link ActorArgumentResolver}. This is how you keep "who is calling?" out of
 * every method signature as a {@code @RequestHeader} - and the same mechanism Spring Security uses
 * for {@code @AuthenticationPrincipal}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentActor {

    /** Fail the request with 400 when the actor cannot be determined. */
    boolean required() default false;
}
