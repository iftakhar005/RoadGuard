package com.roadguard.domain.enums;

public enum AcceptOutcome {

    ACCEPTED,

    ALREADY_TAKEN,

    OFFER_EXPIRED,

    NOT_OFFERED_TO_YOU,

    MECHANIC_NOT_AVAILABLE,

    REQUEST_NOT_FOUND;

    public boolean isWin() {
        return this == ACCEPTED;
    }
}
