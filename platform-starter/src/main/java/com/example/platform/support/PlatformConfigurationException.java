package com.example.platform.support;

/** Thrown when platform configuration is self-contradictory; paired with a FailureAnalyzer. */
public class PlatformConfigurationException extends RuntimeException {

    private final String remedy;

    public PlatformConfigurationException(String message, String remedy) {
        super(message);
        this.remedy = remedy;
    }

    public String getRemedy() {
        return remedy;
    }
}
