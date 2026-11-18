package com.example.orders.support.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean whose public calls should be timed by {@link TimingBeanPostProcessor}.
 *
 * <p>Deliberately a <b>type</b>-level marker: the post-processor decides whether to proxy a bean
 * before any method is called, so a method-level marker would be the wrong granularity for that
 * mechanism. (Method-level is what AOP is for - see {@code @Audited}.)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Monitored {

    /** Calls slower than this are logged at WARN. */
    long slowerThanMillis() default 250;
}
