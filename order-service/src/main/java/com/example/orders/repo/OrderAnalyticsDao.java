package com.example.orders.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL, deliberately.
 *
 * <p>JPA is for the write model - loading an aggregate, mutating it, letting dirty checking emit
 * the UPDATE. For reporting and list screens, JPQL fights you: you want window functions, CTEs,
 * exact column lists and no entity instantiation. Mixing both in one service is normal and good;
 * the mistake is forcing every query through the ORM.
 *
 * <p>{@link JdbcClient} (Spring 6.1+) is the modern fluent API over {@code JdbcTemplate}: named
 * parameters, {@code query(Type.class)}, {@code optional()}, no {@code RowMapper} boilerplate for
 * records.
 *
 * <p>{@code readOnly = true} matters here for three reasons: Hibernate skips dirty checking, the
 * JDBC connection is flagged read-only (some drivers optimise), and - in this application - the
 * routing DataSource sends the connection to the read replica.
 */
@Repository
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class OrderAnalyticsDao {

    private final JdbcClient jdbc;

    public OrderAnalyticsDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Column labels map to record components with relaxed naming ({@code order_day} ->
     * {@code orderDay}), so no RowMapper is needed.
     */
    public record DailyRevenue(LocalDate orderDay, long orders, BigDecimal revenue) {
    }

    public List<DailyRevenue> dailyRevenueSince(Instant since) {
        return jdbc.sql("""
                        -- `day` would be a reserved word on H2; alias deliberately avoids it.
                        -- Reserved-word collisions are the most common reason a query works on
                        -- one engine and not another.
                        select cast(created_at as date) as order_day,
                               count(*)                as orders,
                               sum(total_amount)       as revenue
                        from orders
                        where created_at >= :since
                          and status in ('PAID', 'SHIPPED')
                        group by cast(created_at as date)
                        order by order_day desc
                        """)
                .param("since", java.sql.Timestamp.from(since))
                // Records map positionally by column label; no RowMapper needed.
                .query(DailyRevenue.class)
                .list();
    }

    public Optional<BigDecimal> lifetimeValue(String customerEmail) {
        return jdbc.sql("select coalesce(sum(total_amount), 0) from orders where customer_email = :email")
                .param("email", customerEmail)
                .query(BigDecimal.class)
                .optional();
    }

    /**
     * Shows that JdbcClient and JPA share the same transaction and connection: this count sees
     * flushed-but-uncommitted rows written through the EntityManager in the same transaction.
     */
    public long countOrders() {
        return jdbc.sql("select count(*) from orders").query(Long.class).single();
    }
}
