package com.example.platform.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or every public method of a class) as auditable.
 *
 * <p>This is the idiomatic way to extend the framework with your own declarative behaviour:
 * annotation + pointcut + advice, exactly how {@code @Transactional} and {@code @Cacheable} work.
 *
 * <p>RUNTIME retention is mandatory - AOP reads the annotation reflectively at call time.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** Business action name; defaults to {@code ClassName.methodName}. */
    String action() default "";

    /** Include argument values (masked) in the audit record. */
    boolean includeArgs() default true;

    /** Include the returned value's {@code toString()}; off by default to avoid huge payloads. */
    boolean includeResult() default false;
}
