package com.example.orders.support.extension;

import com.example.orders.service.InventoryService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A real bean carrying {@link Monitored}, so {@link TimingBeanPostProcessor} has something to proxy
 * and the mechanism is exercised at runtime rather than only described.
 *
 * <p>It is interface-backed on purpose - a JDK dynamic proxy can only implement interfaces, which
 * is itself the lesson. Note also the ordering: this bean ends up wrapped in a timing proxy, which
 * wraps Spring's transaction proxy, which wraps the target. Two layers of proxy around one object
 * is completely normal in a Spring application, and it is why a stack trace has eight frames you
 * did not write.
 */
public interface MonitoredInventoryGateway {

    int available(String sku);

    @Monitored(slowerThanMillis = 50)
    @Component
    class Default implements MonitoredInventoryGateway {

        private final InventoryService inventory;

        public Default(InventoryService inventory) {
            this.inventory = inventory;
        }

        @Override
        @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
        public int available(String sku) {
            return inventory.available(sku);
        }
    }
}
