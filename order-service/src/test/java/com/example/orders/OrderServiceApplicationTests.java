package com.example.orders;

import javax.sql.DataSource;

import com.example.orders.config.AppProperties;
import com.example.platform.actuate.PlatformEndpoint;
import com.example.platform.audit.AuditAspect;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context test. Boring, and the highest-value test in the repository: it proves that every
 * bean definition, every {@code @ConfigurationProperties} validation, every Flyway migration and
 * {@code ddl-auto=validate} agree with each other. Most "works on my machine" failures are caught
 * right here.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderServiceApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JpaTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AppProperties appProperties;

    @Test
    void contextLoads() {
        assertThat(appProperties.payments().maxAttempts()).isEqualTo(3);
    }

    /** The starter contributed its beans without being component-scanned. */
    @Test
    void platformStarterIsAutoConfigured(@Autowired AuditAspect auditAspect,
            @Autowired PlatformEndpoint platformEndpoint) {
        assertThat(auditAspect).isNotNull();
        assertThat(platformEndpoint.platform()).containsKey("applicationName");
    }

    @Test
    void primaryDataSourceIsTheLazyRoutingProxy() {
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
    }

    /**
     * Guards the subtle bug fixed in {@code JpaConfig}: without a DataSource on the transaction
     * manager, JPA and {@code JdbcClient} silently use two different connections.
     */
    @Test
    void transactionManagerSharesTheDataSourceWithJdbc() {
        assertThat(transactionManager.getDataSource()).isSameAs(dataSource);
        assertThat(transactionManager.isNestedTransactionAllowed()).isTrue();
    }

    /** Flyway ran, and ddl-auto=validate did not object to the result. */
    @Test
    void migrationsHaveBeenApplied() {
        // Note the quoting of BOTH the table and the column: Flyway creates its history table
        // with quoted lower-case identifiers, while unquoted identifiers in H2 (PostgreSQL mode)
        // are folded to upper case. Mixing the two is a classic identifier-case trap, and it is
        // the reason the application schema in V1 avoids quoted identifiers entirely.
        Integer applied = jdbcClient
                .sql("select count(*) from \"flyway_schema_history\" where \"success\" = true")
                .query(Integer.class)
                .single();
        assertThat(applied).isGreaterThanOrEqualTo(3);
        assertThat(jdbcClient.sql("select count(*) from stock_item").query(Integer.class).single())
                .isGreaterThanOrEqualTo(3);
    }
}
