package com.example.demo.service;

public class UserRegisteredEvent {
    private String email;

    public UserRegisteredEvent(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
