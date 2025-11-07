package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

@Entity
public class Tag extends Base {
    private String name;
    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    private Post post;


}
