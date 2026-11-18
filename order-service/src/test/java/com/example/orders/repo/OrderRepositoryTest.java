package com.example.orders.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.orders.config.JpaAuditingConfig;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderStatus;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A SLICE test: {@code @DataJpaTest} starts JPA, Flyway and a transaction manager and nothing else -
 * no web layer, no RestClient, no scheduling. Seconds instead of tens of seconds, and a failure
 * points at persistence rather than at "something in the app".
 *
 * <p>Two behaviours of {@code @DataJpaTest} that you must know:
 * <ol>
 *   <li>Each test method runs in a transaction that is ROLLED BACK afterwards. Convenient, but it
 *       means you cannot observe commit behaviour here (see {@code TransactionPropagationTest}).</li>
 *   <li>Because the transaction never commits, Hibernate may never flush. {@code flush()} and
 *       {@code clear()} are therefore not optional in these tests - without them you are asserting
 *       against objects in the first-level cache, and your mappings are never exercised.</li>
 * </ol>
 */
@DataJpaTest
// Slice tests skip regular @Configuration classes, so auditing has to be imported explicitly -
// otherwise every insert fails with NULL not allowed for column "CREATED_AT".
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("Spring Data JPA: queries, projections, locking, bulk updates")
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void seed() {
        orders.save(newOrder("ord-1", "alice@example.com", OrderStatus.NEW, "SKU-LAPTOP", 1, "999.99"));
        orders.save(newOrder("ord-2", "alice@example.com", OrderStatus.PAID, "SKU-MOUSE", 3, "25.50"));
        orders.save(newOrder("ord-3", "bob@example.com", OrderStatus.PAID, "SKU-LAPTOP", 2, "999.99"));
        // Push the INSERTs to the database and empty the persistence context, so every assertion
        // below really round-trips through SQL.
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("derived query methods")
    void derivedQueries() {
        assertThat(orders.findByOrderNumber("ord-1")).isPresent();
        assertThat(orders.findByOrderNumber("nope")).isEmpty();
        assertThat(orders.existsByOrderNumber("ord-2")).isTrue();
    }

    @Test
    @DisplayName("@EntityGraph loads the lines in one query instead of N+1")
    void entityGraphFetchesLines() {
        Order order = orders.findWithLinesByOrderNumber("ord-2").orElseThrow();
        entityManager.clear();

        // Already initialised: no LazyInitializationException even outside a session.
        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getLines().get(0).getSku()).isEqualTo("SKU-MOUSE");
    }

    @Test
    @DisplayName("interface projections select only the columns they declare")
    void projections() {
        Page<OrderRepository.OrderSummary> page =
                orders.findAllByStatus(OrderStatus.PAID, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allSatisfy(summary ->
                assertThat(summary.getStatus()).isEqualTo(OrderStatus.PAID));
    }

    @Test
    @DisplayName("JPQL with named parameters")
    void jpqlQuery() {
        List<Order> recent = orders.findRecentForCustomer(
                "alice@example.com", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(recent).hasSize(2);
    }

    @Test
    @DisplayName("Specifications compose optional filters into one query")
    void specifications() {
        Page<Order> page = orders.findAll(
                OrderSpecifications.allOf(
                        OrderSpecifications.hasStatus(OrderStatus.PAID),
                        OrderSpecifications.customerEmailContains("ALICE"),
                        OrderSpecifications.totalAtLeast(new BigDecimal("50.00"))),
                PageRequest.of(0, 10, Sort.by("orderNumber")));

        assertThat(page.getContent()).extracting(Order::getOrderNumber).containsExactly("ord-2");
    }

    @Test
    @DisplayName("Specification with a join + distinct")
    void specificationWithJoin() {
        List<Order> laptops = orders.findAll(OrderSpecifications.containsSku("SKU-LAPTOP"));

        assertThat(laptops).extracting(Order::getOrderNumber).containsExactlyInAnyOrder("ord-1", "ord-3");
    }

    @Test
    @DisplayName("custom fragment implementation with a JPQL constructor expression")
    void customFragment() {
        List<StatusTotal> totals = orders.statusTotalsSince(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(totals).extracting(StatusTotal::status)
                .containsExactlyInAnyOrder(OrderStatus.NEW, OrderStatus.PAID);
        assertThat(totals).filteredOn(t -> t.status() == OrderStatus.PAID)
                .singleElement()
                .satisfies(t -> assertThat(t.count()).isEqualTo(2));
    }

    @Test
    @DisplayName("bulk update touches rows in one statement and clears the session")
    void bulkUpdate() {
        int updated = orders.bulkUpdateStatus(OrderStatus.PAID, OrderStatus.SHIPPED, Instant.now());

        assertThat(updated).isEqualTo(2);
        // clearAutomatically = true means the session was emptied, so this re-reads from the DB
        // rather than returning the stale PAID entities.
        assertThat(orders.findByOrderNumber("ord-2").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("@Version detects a concurrent modification")
    void optimisticLocking() {
        Order first = orders.findByOrderNumber("ord-1").orElseThrow();
        assertThat(first.getVersion()).isZero();

        // Simulate another transaction winning the race by bumping the version behind our back.
        EntityManager em = entityManager.getEntityManager();
        em.createNativeQuery("update orders set version = version + 1 where order_number = 'ord-1'")
                .executeUpdate();

        first.markPaid();

        assertThatExceptionOfType(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
                .isThrownBy(() -> {
                    orders.saveAndFlush(first);
                });
    }

    @Test
    @DisplayName("auditing fills created/updated timestamps")
    void auditingPopulatesTimestamps() {
        Order order = orders.findByOrderNumber("ord-1").orElseThrow();

        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getCreatedBy()).isNotBlank();
    }

    private static Order newOrder(String number, String email, OrderStatus status,
            String sku, int quantity, String price) {
        Order order = new Order(number, email, "EUR");
        order.addLine(sku, quantity, new BigDecimal(price));
        if (status == OrderStatus.PAID) {
            order.markPaid();
        }
        return order;
    }
}
