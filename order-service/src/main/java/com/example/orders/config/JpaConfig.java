package com.example.orders.config;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * JPA / transaction-manager customization. Auditing lives in {@link JpaAuditingConfig} so that
 * slice tests can import it on its own.
 *
 * <p>Note what is NOT here: {@code @EnableJpaRepositories} and {@code @EntityScan}. Boot's
 * auto-configuration already scans from the {@code @SpringBootApplication} package down. Add them
 * only when entities or repositories live outside that tree (a shared module) - and when you do,
 * you must list ALL packages, because the auto-configured default is replaced, not extended.
 */
@Configuration(proxyBeanMethods = false)
public class JpaConfig {

    /**
     * The PRIMARY transaction manager, declared explicitly.
     *
     * <h3>Why not just customize Boot's?</h3>
     * Boot's {@code JpaBaseConfiguration#transactionManager} is annotated
     * {@code @ConditionalOnMissingBean(TransactionManager.class)}. The moment this application
     * declares ANY {@code TransactionManager} bean - for example the
     * {@link #jdbcTransactionManager} below - Boot backs off completely and you must supply the
     * JPA one yourself. If you only need to tweak settings and do not need a second manager,
     * prefer a {@link org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer}
     * bean and let Boot keep ownership.
     *
     * <h3>Why {@code setDataSource(...)}?</h3>
     * Boot creates the manager WITHOUT a DataSource. In that mode it binds no
     * {@code ConnectionHolder}, so a {@code JdbcTemplate}/{@code JdbcClient} call inside a
     * {@code @Transactional} method checks out a <b>second, independent</b> connection: it cannot
     * see the JPA transaction's unflushed rows, it burns two pool slots per request, and it can
     * deadlock against the transaction it is nested inside. Setting the DataSource makes JPA and
     * plain JDBC share one physical connection and one transaction - which is exactly what
     * {@code OrderAnalyticsDao} relies on.
     *
     * <h3>Why the name {@code transactionManager}?</h3>
     * {@code @Transactional} without a qualifier resolves the bean named {@code transactionManager}
     * (or the {@code @Primary} one). Spring Data JPA repositories default to that name too. Name it
     * anything else and you get mysterious "no transaction in progress" failures.
     */
    @Bean
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        JpaTransactionManager tm = new JpaTransactionManager(entityManagerFactory);
        tm.setDataSource(dataSource);
        // Needed for Propagation.NESTED - see jdbcTransactionManager() for why it is not enough.
        tm.setNestedTransactionAllowed(true);
        // Fail instead of hanging forever when a lock cannot be obtained.
        tm.setDefaultTimeout(30);
        return tm;
    }

    /**
     * A second, JDBC-only transaction manager - the ONLY way to get working
     * {@code Propagation.NESTED} in a Hibernate application.
     *
     * <h3>The honest truth about NESTED and JPA</h3>
     * NESTED is implemented with JDBC savepoints. {@code JpaTransactionManager} delegates savepoint
     * support to the {@code JpaDialect}'s transaction data object, and Hibernate's
     * {@code SessionTransactionData} does not implement {@code SavepointManager}. So even with
     * {@code nestedTransactionAllowed = true} and a DataSource bound, a NESTED call through the JPA
     * manager fails with:
     *
     * <pre>NestedTransactionNotSupportedException: JpaDialect does not support savepoints
     * - check your JPA provider's capabilities</pre>
     *
     * This is a real, permanent limitation, not a misconfiguration - and it is why so much folklore
     * claims NESTED "does not work in Spring". It works, through
     * {@link org.springframework.jdbc.support.JdbcTransactionManager} (or Spring ORM's
     * {@code HibernateTransactionManager}), for work done over plain JDBC. See
     * {@code SavepointDemoService}.
     *
     * <p>Mixing managers is NOT a way to nest JPA work inside JDBC work: two managers mean two
     * independent transactions on (potentially) two connections. Use this one for a self-contained
     * JDBC unit of work, and keep JPA work under the primary manager.
     *
     * <p>{@code JdbcTransactionManager} is preferred over its parent
     * {@code DataSourceTransactionManager} because it translates {@code SQLException} into Spring's
     * {@code DataAccessException} hierarchy.
     */
    @Bean
    JdbcTransactionManager jdbcTransactionManager(DataSource dataSource) {
        JdbcTransactionManager tm = new JdbcTransactionManager(dataSource);
        tm.setNestedTransactionAllowed(true);
        tm.setDefaultTimeout(30);
        return tm;
    }

    /**
     * Hibernate settings that have no Boot property, set without taking over the whole
     * {@code EntityManagerFactory} configuration.
     */
    @Bean
    HibernatePropertiesCustomizer hibernateTuning() {
        return properties -> {
            // Lets Hibernate delay connection acquisition to the first statement, which is what
            // makes LazyConnectionDataSourceProxy worthwhile.
            properties.put("hibernate.connection.provider_disables_autocommit", "true");
            // Batch writes. order_inserts/order_updates group statements by table so that batching
            // actually kicks in. (Remember: IDENTITY ids defeat insert batching entirely.)
            properties.put("hibernate.jdbc.batch_size", "25");
            properties.put("hibernate.order_inserts", "true");
            properties.put("hibernate.order_updates", "true");
            properties.put("hibernate.batch_versioned_data", "true");
            // Query plan cache: cheap, big win on JPQL-heavy services.
            properties.put("hibernate.query.plan_cache_max_size", "2048");
        };
    }
}
