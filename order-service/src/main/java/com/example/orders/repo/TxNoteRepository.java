package com.example.orders.repo;

import com.example.orders.domain.TxNote;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TxNoteRepository extends JpaRepository<TxNote, Long> {

    long countByLabel(String label);

    boolean existsByLabel(String label);
}
