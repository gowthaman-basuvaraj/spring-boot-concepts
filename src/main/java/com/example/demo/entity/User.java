package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "users", indexes = @Index(unique = true, columnList = "user_name"))
public class User {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    private String userName;
    private String address;
    private String pinCode;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPinCode() {
        return pinCode;
    }

    public void setPinCode(String pinCode) {
        this.pinCode = pinCode;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }
}
