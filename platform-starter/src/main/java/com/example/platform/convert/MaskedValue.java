package com.example.platform.convert;

/**
 * A secret that refuses to print itself. Use for API keys bound from configuration:
 * even a careless {@code log.info("props={}", props)} cannot leak it.
 */
public final class MaskedValue implements CharSequence {

    private final String value;

    public MaskedValue(String value) {
        this.value = value == null ? "" : value;
    }

    public String reveal() {
        return value;
    }

    public boolean hasValue() {
        return !value.isBlank();
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public String toString() {
        if (value.isBlank()) {
            return "(unset)";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
