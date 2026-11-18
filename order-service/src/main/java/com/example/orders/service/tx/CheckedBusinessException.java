package com.example.orders.service.tx;

/**
 * A CHECKED exception, used to demonstrate the single most surprising default in Spring's
 * transaction management: <b>a checked exception does not roll back</b>.
 *
 * <p>The rule is {@code RuntimeException} and {@code Error} roll back, everything else commits.
 * It comes from EJB and it has cost the industry a great deal of money. Either make your domain
 * exceptions unchecked, or spell out {@code @Transactional(rollbackFor = Exception.class)}.
 */
public class CheckedBusinessException extends Exception {

    public CheckedBusinessException(String message) {
        super(message);
    }
}
