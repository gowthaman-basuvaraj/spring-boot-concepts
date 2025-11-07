package com.example.demo.utils;

public class UserNotFoundException extends Exception {
    public UserNotFoundException(String user) {
        super("User not found: " + user);
    }
}
