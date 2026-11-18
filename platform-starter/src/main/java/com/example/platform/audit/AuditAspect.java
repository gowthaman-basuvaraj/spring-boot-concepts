package com.example.platform.audit;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.platform.autoconfigure.PlatformProperties;
import com.example.platform.correlation.CorrelationContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Around advice implementing {@link Audited}.
 *
 * <p>Things a senior engineer will look for here:
 * <ul>
 *   <li><b>Pointcut covers both targets</b> - the annotation may sit on the method or the class,
 *       so the pointcut ORs {@code @annotation} and {@code @within}.</li>
 *   <li><b>Resolve the annotation from the target method, not the interface method.</b>
 *       With JDK dynamic proxies {@code signature.getMethod()} can be the interface method, which
 *       carries no annotations. {@code AnnotatedElementUtils} + the target class fixes that and
 *       also handles meta-annotations.</li>
 *   <li><b>Ordering matters.</b> This advice must run OUTSIDE {@code @Transactional} (lower order
 *       value = more outer) so the audit record covers commit time too... but it must run INSIDE
 *       retry so each attempt is audited. Order 20 with tx at 100 and retry at 0 gives
 *       retry -&gt; audit -&gt; transaction.</li>
 *   <li><b>Never swallow the exception.</b> Advice that catches and returns null is how you lose a
 *       week of your life.</li>
 * </ul>
 */
@Aspect
public class AuditAspect implements Ordered {

    public static final int ORDER = 20;

    private final AuditSink sink;
    private final AuditCounters counters;
    private final Set<String> maskedFields;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AuditAspect(AuditSink sink, AuditCounters counters, PlatformProperties.Audit config,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.sink = sink;
        this.counters = counters;
        this.maskedFields = config.maskedFields() == null ? Set.of()
                : config.maskedFields().stream().map(String::toLowerCase).collect(Collectors.toSet());
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(com.example.platform.audit.Audited) || @within(com.example.platform.audit.Audited)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Class<?> targetClass = pjp.getTarget().getClass();
        Method method = resolveTargetMethod(signature, targetClass);
        Audited annotation = AnnotatedElementUtils.findMergedAnnotation(method, Audited.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, Audited.class);
        }
        String action = (annotation == null || annotation.action().isBlank())
                ? signature.getDeclaringType().getSimpleName() + "." + signature.getName()
                : annotation.action();

        Instant start = Instant.now();
        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            emit(action, annotation, signature, pjp.getArgs(), result, start, startNanos, null);
            return result;
        } catch (Throwable failure) {
            emit(action, annotation, signature, pjp.getArgs(), null, start, startNanos, failure);
            throw failure; // rethrow: advice is transparent
        }
    }

    private void emit(String action, Audited annotation, MethodSignature signature, Object[] args,
            Object result, Instant start, long startNanos, Throwable failure) {
        long millis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        boolean includeArgs = annotation == null || annotation.includeArgs();
        boolean includeResult = annotation != null && annotation.includeResult();
        AuditRecord record = new AuditRecord(
                start,
                action,
                CorrelationContext.get(),
                includeArgs ? maskArgs(signature.getParameterNames(), args) : List.of(),
                includeResult && result != null ? String.valueOf(result) : null,
                millis,
                failure == null ? AuditRecord.Outcome.SUCCESS : AuditRecord.Outcome.FAILURE,
                failure == null ? null : failure.getClass().getSimpleName() + ": " + failure.getMessage());

        counters.record(record);
        sink.accept(record);

        // Optional dependency: getIfAvailable() returns null when micrometer is absent.
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Timer.builder("platform.audited")
                    .tag("action", action)
                    .tag("outcome", record.outcome().name())
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofMillis(millis));
        }
    }

    private List<String> maskArgs(String[] names, Object[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String name = (names != null && i < names.length && names[i] != null) ? names[i] : "arg" + i;
            boolean masked = maskedFields.contains(name.toLowerCase());
            out.add(name + "=" + (masked ? "***" : abbreviate(args[i])));
        }
        return out;
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    private static Method resolveTargetMethod(MethodSignature signature, Class<?> targetClass) {
        try {
            return targetClass.getMethod(signature.getName(), signature.getParameterTypes());
        } catch (NoSuchMethodException ex) {
            return signature.getMethod();
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
