package com.example.demo.utils;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "User already exists")
public class UserExistsException extends Exception {
    public UserExistsException(String user) {
        super("User already exists: " + user);
    }
}
