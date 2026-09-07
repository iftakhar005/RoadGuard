package com.roadguard.domain.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RequestStatus {

    CREATED,

    DIAGNOSING,

    SEARCHING,

    OFFERED,

    ACCEPTED,

    EN_ROUTE,
    ARRIVED,
    IN_PROGRESS,

    REASSIGNING,

    ESCALATED,

    COMPLETED,
    CANCELLED;

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = new EnumMap<>(RequestStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(DIAGNOSING, SEARCHING, CANCELLED));
        ALLOWED.put(DIAGNOSING, EnumSet.of(SEARCHING, CANCELLED));
        ALLOWED.put(SEARCHING, EnumSet.of(OFFERED, ESCALATED, CANCELLED));

        ALLOWED.put(OFFERED, EnumSet.of(ACCEPTED, SEARCHING, ESCALATED, CANCELLED));
        ALLOWED.put(ACCEPTED, EnumSet.of(EN_ROUTE, REASSIGNING, CANCELLED));
        ALLOWED.put(EN_ROUTE, EnumSet.of(ARRIVED, REASSIGNING, CANCELLED));
        ALLOWED.put(ARRIVED, EnumSet.of(IN_PROGRESS, REASSIGNING, CANCELLED));
        ALLOWED.put(IN_PROGRESS, EnumSet.of(COMPLETED, REASSIGNING, CANCELLED));

        ALLOWED.put(REASSIGNING, EnumSet.of(SEARCHING, OFFERED, CANCELLED));

        ALLOWED.put(ESCALATED, EnumSet.of(SEARCHING, CANCELLED));

        ALLOWED.put(COMPLETED, EnumSet.noneOf(RequestStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(RequestStatus.class));
    }

    public boolean canTransitionTo(RequestStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<RequestStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isAssignedToMechanic() {
        return this == ACCEPTED || this == EN_ROUTE || this == ARRIVED || this == IN_PROGRESS;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
