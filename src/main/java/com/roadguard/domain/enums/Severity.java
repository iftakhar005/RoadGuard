package com.roadguard.domain.enums;

// How bad the breakdown is. Comes from the AI photo check,
// or defaults to MEDIUM if the driver didn't send a photo.
//
// This isn't just a label - urgent requests search a bigger radius,
// go out to more mechanics, and skip ahead in the dispatch queue.
public enum Severity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    // Sort key for the dispatch queue. Smaller number = goes first.
    public int priority() {
        return switch (this) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }

    public boolean isUrgent() {
        return this == HIGH || this == CRITICAL;
    }
}
