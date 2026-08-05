package com.roadguard.repository;

import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.enums.OfferOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestOfferRepository extends JpaRepository<RequestOffer, Long> {

    List<RequestOffer> findByRequestIdOrderBySentAtAsc(Long requestId);

    // Everyone who got this particular round, so the losers can be told it's gone.
    List<RequestOffer> findByRequestIdAndOfferToken(Long requestId, String offerToken);

    Optional<RequestOffer> findByRequestIdAndMechanicIdAndOfferToken(Long requestId,
                                                                     Long mechanicId,
                                                                     String offerToken);

    // Offer cards still sitting open on a mechanic's screen.
    List<RequestOffer> findByMechanicIdAndOutcomeIsNull(Long mechanicId);

    // Used by the concurrency test: after the race there must be exactly one of these.
    long countByRequestIdAndOutcome(Long requestId, OfferOutcome outcome);
}
