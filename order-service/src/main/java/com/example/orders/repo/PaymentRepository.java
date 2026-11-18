package com.example.orders.repo;

import java.util.List;

import com.example.orders.domain.Payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByOrderNumberOrderByAttemptNoAsc(String orderNumber);

    long countByOrderNumber(String orderNumber);
}
