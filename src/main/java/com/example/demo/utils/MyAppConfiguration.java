package com.example.demo.utils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "my.app.config")
@Validated
public class MyAppConfiguration {
    @NotBlank
    private String setting1;
    @NotNull
    private Integer setting2;
    // ... getters and setters


    public String getSetting1() {
        return setting1;
    }

    public void setSetting1(String setting1) {
        this.setting1 = setting1;
    }

    public Integer getSetting2() {
        return setting2;
    }

    public void setSetting2(Integer setting2) {
        this.setting2 = setting2;
    }

    @Override
    public String toString() {
        return String.format("MyAppConfiguration{setting1='%s', setting2=%d}", setting1, setting2);
    }
}