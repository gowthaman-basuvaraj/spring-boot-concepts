package com.example.demo.utils;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "my.app.config")
public class MyAppConfiguration {
    private String setting1;
    private int setting2;
    // ... getters and setters


    @Override
    public String toString() {
        return String.format("MyAppConfiguration{setting1='%s', setting2=%d}", setting1, setting2);
    }
}