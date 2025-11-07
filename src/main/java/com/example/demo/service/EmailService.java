package com.example.demo.service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
public class EmailService {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistration(UserRegisteredEvent event) {
        // This method will ONLY be called *after* the
        // publisher's transaction has successfully committed.

        // This solves the data visibility race condition.
        // It also ensures we don't send an email for a user
        // that failed to save.

        System.out.println("Sending email to: " + event.getEmail());
        // ...
    }
}
