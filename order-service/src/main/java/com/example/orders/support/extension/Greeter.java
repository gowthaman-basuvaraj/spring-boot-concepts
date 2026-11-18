package com.example.orders.support.extension;

/** The trivial bean type that {@link GreeterRegistrar} registers one of per configured name. */
public class Greeter {

    private final String prefix;
    private final String name;

    public Greeter(String prefix, String name) {
        this.prefix = prefix;
        this.name = name;
    }

    public String greet() {
        return prefix + ", " + name + "!";
    }

    public String getName() {
        return name;
    }
}
