package com.roadguard.tcp;

import java.util.Collection;

public record OffersSentEvent(Long requestId, Collection<Long> mechanicUserIds) {
}
