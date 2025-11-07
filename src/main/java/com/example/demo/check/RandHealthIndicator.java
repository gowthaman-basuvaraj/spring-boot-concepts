package com.example.demo.check;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class RandHealthIndicator extends AbstractHealthIndicator {
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        double chance = ThreadLocalRandom.current().nextDouble();
        if (chance > 0.9) {
            builder.up();
        } else {
            builder.down();

        }

    }
}
