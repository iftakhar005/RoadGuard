package com.roadguard.domain.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// The states a service request moves through.
//
// Normal path:
//   CREATED -> DIAGNOSING -> SEARCHING -> OFFERED -> ACCEPTED
//           -> EN_ROUTE -> ARRIVED -> IN_PROGRESS -> COMPLETED
//
// Things that go wrong:
//   nobody accepts in time  -> back to SEARCHING with a bigger radius,
//                              and ESCALATED once we run out of attempts
//   mechanic drops off      -> REASSIGNING, then straight back into dispatch
//   driver cancels          -> CANCELLED
//
// The legal moves are kept in one map below instead of being spread out
// over the service classes. That way checking a transition is one call,
// and the state machine can be tested on its own.
public enum RequestStatus {

    CREATED,

    // Driver sent a photo and we're waiting on the AI check.
    DIAGNOSING,

    // Looking for mechanics to offer this to.
    SEARCHING,

    // Offers are out, waiting for someone to accept first.
    OFFERED,

    // Someone won the race and is assigned.
    ACCEPTED,

    EN_ROUTE,
    ARRIVED,
    IN_PROGRESS,

    // Assigned mechanic went quiet, so this is going back out.
    REASSIGNING,

    // Ran out of attempts, an admin has to deal with it.
    ESCALATED,

    COMPLETED,
    CANCELLED;

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = new EnumMap<>(RequestStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(DIAGNOSING, SEARCHING, CANCELLED));
        ALLOWED.put(DIAGNOSING, EnumSet.of(SEARCHING, CANCELLED));
        ALLOWED.put(SEARCHING, EnumSet.of(OFFERED, ESCALATED, CANCELLED));
        // OFFERED back to SEARCHING is the timeout widening the radius.
        ALLOWED.put(OFFERED, EnumSet.of(ACCEPTED, SEARCHING, ESCALATED, CANCELLED));
        ALLOWED.put(ACCEPTED, EnumSet.of(EN_ROUTE, REASSIGNING, CANCELLED));
        ALLOWED.put(EN_ROUTE, EnumSet.of(ARRIVED, REASSIGNING, CANCELLED));
        ALLOWED.put(ARRIVED, EnumSet.of(IN_PROGRESS, REASSIGNING, CANCELLED));
        ALLOWED.put(IN_PROGRESS, EnumSet.of(COMPLETED, REASSIGNING, CANCELLED));
        // A reassigned request goes through the same path as a brand new one.
        ALLOWED.put(REASSIGNING, EnumSet.of(SEARCHING, OFFERED, CANCELLED));
        // Admin can push an escalated request back into dispatch.
        ALLOWED.put(ESCALATED, EnumSet.of(SEARCHING, CANCELLED));
        // Nothing comes after these two.
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

    // A mechanic is currently on the hook for this one, so if they drop off
    // we have to send it back out.
    public boolean isAssignedToMechanic() {
        return this == ACCEPTED || this == EN_ROUTE || this == ARRIVED || this == IN_PROGRESS;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
