package com.roadguard.tcp;

import java.util.Collection;

public record OfferClosedEvent(Long requestId, Collection<Long> mechanicUserIds) {
}
