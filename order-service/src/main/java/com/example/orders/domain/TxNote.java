package com.example.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A throwaway row used by the transaction-propagation demos: writing one and then asserting whether
 * it survived is the only honest way to show what REQUIRES_NEW, NESTED and rollback rules really do.
 */
@Entity
@Table(name = "tx_note")
public class TxNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String label;

    protected TxNote() {
    }

    public TxNote(String label) {
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
