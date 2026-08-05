package com.roadguard.domain.enums;

// Skills a mechanic can have. A request needs one of these,
// and we only offer it to mechanics who have it.
public enum Specialization {

    TIRE,
    BATTERY,
    ENGINE,
    ELECTRICAL,
    BRAKES,
    FUEL,
    TOWING,

    // Fallback when we can't work out anything more specific.
    GENERAL
}
