package com.example.orders.support;

import com.example.orders.config.AppProperties;
import com.example.orders.domain.StockItem;
import com.example.orders.repo.StockItemRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup hooks, and when to use which.
 *
 * <ul>
 *   <li>{@code @PostConstruct} - per-bean initialisation. Runs before the context is fully
 *       refreshed, so do NOT touch other beans' behaviour or start threads here.</li>
 *   <li>{@link ApplicationRunner}/{@code CommandLineRunner} - after the context is refreshed but
 *       BEFORE the {@code ApplicationReadyEvent}. The right place for one-off data setup. An
 *       exception here fails startup, which is usually what you want.</li>
 *   <li>{@link ApplicationReadyEvent} - after everything, including the web server binding its
 *       port. The right place for "we are live" logging, warm-up calls or registering with a
 *       service registry.</li>
 *   <li>{@code ApplicationContextInitializer}/{@code EnvironmentPostProcessor} - before any bean
 *       exists (see the platform starter).</li>
 * </ul>
 *
 * <p>Restricted to the {@code dev} profile: seeding demo data in production is how test orders end
 * up in a quarterly report.
 */
@Component
@Profile("dev")
public class StartupTour implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupTour.class);

    private final StockItemRepository stock;
    private final AppProperties properties;
    private final Environment environment;

    public StartupTour(StockItemRepository stock, AppProperties properties, Environment environment) {
        this.stock = stock;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (stock.count() == 0) {
            stock.save(new StockItem("SKU-LAPTOP", 50));
            stock.save(new StockItem("SKU-MOUSE", 500));
            stock.save(new StockItem("SKU-DOCK", 10));
            log.info("seeded demo stock items");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printTour() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String base = "http://localhost:" + port;
        log.info("""

                ======================================================================
                 order-service is up. Guided tour:

                   Create an order     POST   {}/api/orders           (see http/api.http)
                   Fetch one           GET    {}/api/orders/<orderNumber>
                   Search / page       GET    {}/api/orders?status=paid&page=0&size=20
                   Statistics          GET    {}/api/orders/statistics?days=30
                   Transaction lab     GET    {}/api/tx/propagation
                   Scenario            POST   {}/api/tx/scenarios/nested-isolated
                   Health              GET    {}/actuator/health
                   Custom endpoint     GET    {}/actuator/platform
                   Conditions report   GET    {}/actuator/conditions
                   Metrics             GET    {}/actuator/prometheus

                 Payment gateway base url: {}
                ======================================================================
                """, base, base, base, base, base, base, base, base, base, base,
                properties.payments().baseUrl());
    }
}
