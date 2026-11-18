package com.example.orders.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.orders.service.tx.CheckedBusinessException;
import com.example.orders.service.tx.PropagationDemoService;
import com.example.orders.service.tx.SavepointDemoService;
import com.example.orders.service.tx.TransactionBoundaryDemoService;
import com.example.orders.service.tx.TxInfo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A playground for the transaction demos: {@code GET /api/tx/propagation} and
 * {@code POST /api/tx/scenarios/{name}} let you watch the behaviour in the log instead of only
 * reading about it. The authoritative version is {@code TransactionPropagationTest}.
 */
@RestController
@RequestMapping("/api/tx")
public class TransactionLabController {

    private final TransactionBoundaryDemoService boundaries;
    private final PropagationDemoService propagation;
    private final SavepointDemoService savepoints;

    public TransactionLabController(TransactionBoundaryDemoService boundaries,
            PropagationDemoService propagation, SavepointDemoService savepoints) {
        this.boundaries = boundaries;
        this.propagation = propagation;
        this.savepoints = savepoints;
    }

    /** Shows the ambient transaction for each propagation mode, called from NO transaction. */
    @GetMapping("/propagation")
    public Map<String, Object> propagationOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callerHasTransaction", TxInfo.capture());
        result.put("REQUIRED", propagation.required("http-required", false));
        result.put("REQUIRES_NEW", propagation.requiresNew("http-requires-new", false));
        result.put("SUPPORTS", propagation.supports());
        result.put("NOT_SUPPORTED", propagation.notSupported("http-not-supported"));
        result.put("READ_ONLY", propagation.readOnly());
        try {
            result.put("MANDATORY", propagation.mandatory());
        } catch (RuntimeException expected) {
            result.put("MANDATORY", "threw " + expected.getClass().getSimpleName()
                    + " - MANDATORY requires an existing transaction");
        }
        result.put("NEVER", propagation.never());
        return result;
    }

    @GetMapping("/required-joins-caller")
    public List<TxInfo> requiredJoinsCaller() {
        return boundaries.requiredJoinsCaller();
    }

    /**
     * Runs one named scenario and reports what happened. Each scenario is documented on the
     * corresponding service method.
     */
    @PostMapping("/scenarios/{name}")
    public Map<String, Object> scenario(@PathVariable String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name);
        try {
            switch (name) {
                case "requires-new-survives-rollback" -> {
                    boundaries.requiresNewSurvivesOuterRollback();
                    result.put("outcome", "unexpected: should have thrown");
                }
                // Works: savepoints over the JDBC transaction manager.
                case "nested-isolated" -> result.put("outcome", savepoints.nestedFailureIsIsolated());
                case "nested-success" -> result.put("outcome", savepoints.nestedSuccessCommitsBoth());
                // Fails: NESTED is not supported by JpaTransactionManager + Hibernate.
                case "nested-jpa-unsupported" -> result.put("outcome", boundaries.nestedUnderJpaIsUnsupported());
                // Fails at COMMIT with UnexpectedRollbackException, on both managers.
                case "required-poisons-outer" -> result.put("outcome",
                        boundaries.requiredFailurePoisonsOuterTransaction());
                case "required-poisons-outer-jdbc" -> result.put("outcome",
                        savepoints.requiredFailurePoisonsOuter());
                case "self-invocation-broken" -> result.put("txInfo", boundaries.selfInvocationBroken());
                case "self-invocation-fixed" -> result.put("txInfo", boundaries.selfInvocationFixed());
                case "checked-commits" -> boundaries.checkedExceptionCommitsByDefault();
                case "checked-rolls-back" -> boundaries.checkedExceptionWithRollbackFor();
                case "no-rollback-for" -> boundaries.runtimeExceptionWithNoRollbackFor();
                case "rollback-only" -> boundaries.markRollbackOnlyWithoutThrowing();
                case "programmatic" -> result.put("txInfo", boundaries.programmatic());
                case "programmatic-rollback" -> boundaries.programmaticRollback();
                case "synchronization" -> {
                    List<String> callbacks = new ArrayList<>();
                    boundaries.withSynchronization(callbacks);
                    result.put("callbacks", callbacks);
                }
                default -> result.put("outcome", "unknown scenario");
            }
            result.putIfAbsent("outcome", "completed");
        } catch (CheckedBusinessException | RuntimeException ex) {
            // Expected for several scenarios - that IS the lesson.
            result.put("outcome", "threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        result.put("rowsByLabel", labelCounts());
        return result;
    }

    private Map<String, Long> labelCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        List.of("outer-will-rollback", "inner-requires-new-commits", "outer-survives",
                        "nested-will-rollback", "savepoint-outer", "savepoint-inner",
                        "savepoint-outer-ok", "savepoint-inner-ok", "required-outer", "required-inner",
                        "poisoned-outer", "poisoned-inner",
                        "self-invocation-broken", "self-invocation-fixed",
                        "checked-commits", "checked-rolls-back", "runtime-no-rollback",
                        "marked-rollback-only", "programmatic", "programmatic-rollback",
                        "with-synchronization")
                .forEach(label -> counts.put(label, propagation.countByLabel(label)));
        return counts;
    }
}
