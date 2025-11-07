package com.example.demo.controller;

import com.example.demo.service.EchoService;
import com.example.demo.utils.MyAppConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppController {
    private final MyAppConfiguration myAppConfiguration;
    private final EchoService echoService;

    public AppController(MyAppConfiguration myAppConfiguration, EchoService echoService) {
        this.myAppConfiguration = myAppConfiguration;
        this.echoService = echoService;
    }

    @GetMapping("/app")
    public String index() {
        System.out.println("MyAppConfiguration: " + myAppConfiguration);
        echoService.echo("New Request");
        return "app";
    }
}
