package com.roadguard.domain.enums;

public enum Severity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isUrgent() {
        return this == HIGH || this == CRITICAL;
    }

    public int priority() {
        return switch (this) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }
}
