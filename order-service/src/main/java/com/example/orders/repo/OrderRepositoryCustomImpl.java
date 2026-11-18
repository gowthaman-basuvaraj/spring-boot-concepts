package com.example.orders.repo;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Custom fragment implementation.
 *
 * <p>Naming rule: {@code <FragmentInterface> + Impl} (the suffix is configurable via
 * {@code @EnableJpaRepositories(repositoryImplementationPostfix = ...)}). Get the name wrong and
 * you get "No property 'statusTotalsSince' found for type Order" at startup - that error message
 * almost always means a misnamed or misplaced Impl class.
 *
 * <p>This class is NOT a {@code @Repository} bean by scanning; Spring Data instantiates it and
 * plugs it into the repository proxy, so {@code @PersistenceContext} injection still applies.
 */
public class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

    /**
     * {@code @PersistenceContext} injects a transaction-scoped EntityManager PROXY, not a real one.
     * Injecting an {@code EntityManager} with {@code @Autowired} would hand you the shared
     * {@code EntityManagerFactory}-created instance and break thread safety.
     */
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<StatusTotal> statusTotalsSince(Instant since) {
        // Constructor expression: JPQL can build records/DTOs directly. Fully-qualified name
        // required, and the constructor must match exactly.
        return entityManager.createQuery("""
                        select new com.example.orders.repo.StatusTotal(
                            o.status, count(o), coalesce(sum(o.totalAmount), 0))
                        from Order o
                        where o.createdAt >= :since
                        group by o.status
                        """, StatusTotal.class)
                .setParameter("since", since)
                .getResultList();
    }
}
