package com.example.orders.config;

import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Database connectivity, done the way a senior engineer would wire it.
 *
 * <h2>What this class demonstrates</h2>
 * <ol>
 *   <li><b>Two physical pools</b> (primary + read replica), each with its own Hikari settings,
 *       bound from {@code app.datasource.*}.</li>
 *   <li><b>A routing DataSource</b> that picks the replica whenever the current transaction is
 *       marked {@code readOnly = true}. No call site has to know which pool it is using.</li>
 *   <li><b>{@code LazyConnectionDataSourceProxy}</b>, which is what makes (2) actually work - and
 *       is a good idea even without routing.</li>
 *   <li><b>Flyway pinned to the primary</b> via {@code @FlywayDataSource}: migrations must never
 *       run against a replica.</li>
 * </ol>
 *
 * <h2>Why LazyConnectionDataSourceProxy is mandatory here</h2>
 * {@code JpaTransactionManager} acquires a JDBC connection when the transaction BEGINS, before any
 * SQL is issued. At that moment the routing key would be read... and it does work for readOnly,
 * but the connection is held for the whole method even if it never queries. The lazy proxy defers
 * {@code getConnection()} until the first real statement, which (a) lets the routing decision see
 * the fully-initialised transaction state, (b) returns pool capacity to the application for
 * transactions that end up doing no SQL, and (c) avoids the pool being drained by long methods that
 * only occasionally touch the database.
 *
 * <h2>Connection pool sizing - the only formula you need</h2>
 * {@code pool size = ((core_count * 2) + effective_spindle_count)}, and for a web app:
 * <b>small is fast</b>. 10 is a good starting point for most services; 100 is almost always a
 * misconfiguration that moves the queue from your app into the database. {@code max-lifetime} must
 * be shorter than any connection timeout enforced by the database or a proxy in front of it
 * (PgBouncer, RDS Proxy), and {@code leak-detection-threshold} is free money in production.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** Routing key. An enum keeps the two cases closed and greppable. */
    public enum Route { PRIMARY, REPLICA }

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.primary")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * {@code @FlywayDataSource} tells Boot's Flyway auto-configuration to migrate THIS DataSource
     * rather than the {@code @Primary} one (which is the lazy routing proxy). Migrations against a
     * replica would fail, or worse, against the wrong database would succeed.
     */
    @Bean(destroyMethod = "close")
    @FlywayDataSource
    @ConfigurationProperties("app.datasource.primary.hikari")
    public HikariDataSource primaryDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("primary-pool");
        return ds;
    }

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("app.datasource.replica.hikari")
    public HikariDataSource replicaDataSource(
            @org.springframework.beans.factory.annotation.Qualifier("replicaDataSourceProperties")
            DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("replica-pool");
        ds.setReadOnly(true); // defence in depth: the driver rejects writes on this pool
        return ds;
    }

    /**
     * The bean every other component injects. Marked {@code @Primary} so JPA, Spring Data,
     * {@code JdbcClient} and the transaction manager all use it.
     *
     * <p>Note that declaring ANY {@code DataSource} bean makes Boot's
     * {@code DataSourceAutoConfiguration} back off entirely - which is why we also have to declare
     * {@code DataSourceProperties} ourselves above.
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource primaryDataSource, HikariDataSource replicaDataSource) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // TransactionSynchronizationManager is the bridge between declarative
                // @Transactional(readOnly = true) and infrastructure code like this.
                boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
                return readOnly ? Route.REPLICA : Route.PRIMARY;
            }
        };
        routing.setTargetDataSources(Map.of(Route.PRIMARY, primaryDataSource, Route.REPLICA, replicaDataSource));
        routing.setDefaultTargetDataSource(primaryDataSource);
        routing.afterPropertiesSet();
        log.info("Configured routing DataSource: primary={} replica={}",
                primaryDataSource.getJdbcUrl(), replicaDataSource.getJdbcUrl());
        return new LazyConnectionDataSourceProxy(routing);
    }
}
