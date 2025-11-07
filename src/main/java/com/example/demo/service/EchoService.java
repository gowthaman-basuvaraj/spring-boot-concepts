package com.example.demo.service;

import org.springframework.stereotype.Service;

@Service
public class EchoService {
    public void echo(String mes){
        System.out.println("mes::"+mes);
    }
}
