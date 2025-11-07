package com.example.demo.controller;

import com.example.demo.utils.MyAppConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppController {
    private final MyAppConfiguration myAppConfiguration;

    public AppController(MyAppConfiguration myAppConfiguration) {
        this.myAppConfiguration = myAppConfiguration;
    }

    @GetMapping("/app")
    public String index() {
        System.out.println("MyAppConfiguration: " + myAppConfiguration);
        return "app";
    }
}
