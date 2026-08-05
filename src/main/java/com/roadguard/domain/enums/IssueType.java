package com.roadguard.domain.enums;

// What the driver picks from the dropdown on the SOS form.
public enum IssueType {

    FLAT_TIRE,
    DEAD_BATTERY,
    ENGINE,
    ELECTRICAL,
    BRAKES,
    FUEL_EMPTY,
    OVERHEATING,
    LOCKOUT,
    TOWING,
    OTHER;

    // Which mechanic skill to look for when we have no AI diagnosis -
    // either the driver skipped the photo or the AI call failed.
    public Specialization defaultSpecialization() {
        return switch (this) {
            case FLAT_TIRE -> Specialization.TIRE;
            case DEAD_BATTERY -> Specialization.BATTERY;
            case ENGINE, OVERHEATING -> Specialization.ENGINE;
            case ELECTRICAL -> Specialization.ELECTRICAL;
            case BRAKES -> Specialization.BRAKES;
            case FUEL_EMPTY -> Specialization.FUEL;
            case TOWING -> Specialization.TOWING;
            case LOCKOUT, OTHER -> Specialization.GENERAL;
        };
    }
}
