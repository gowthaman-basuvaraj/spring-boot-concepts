package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

@Entity
public class Comment extends Base {
    private String content;
    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    private Post post;


}
