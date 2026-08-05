package com.roadguard.domain.enums;

// How a single offer ended up. Null while the offer is still open.
public enum OfferOutcome {

    // This mechanic won the race.
    ACCEPTED,

    // They pressed decline.
    DECLINED,

    // Someone else got there first.
    TAKEN,

    // Nobody answered and the round timed out.
    TIMEOUT
}
