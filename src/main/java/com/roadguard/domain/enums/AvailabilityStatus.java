package com.roadguard.domain.enums;

// Tells us whether a mechanic can take work right now.
// Only ONLINE mechanics get offers.
public enum AvailabilityStatus {

    // Not connected, or we stopped getting heartbeats from them.
    OFFLINE,

    // Connected and free.
    ONLINE,

    // Already on a job, so skip them when matching.
    BUSY
}
