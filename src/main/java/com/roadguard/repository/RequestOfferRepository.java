package com.roadguard.repository;

import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.enums.OfferOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestOfferRepository extends JpaRepository<RequestOffer, Long> {

    List<RequestOffer> findByRequestIdOrderBySentAtAsc(Long requestId);

    List<RequestOffer> findByRequestIdAndOfferToken(Long requestId, String offerToken);

    Optional<RequestOffer> findByRequestIdAndMechanicIdAndOfferToken(Long requestId,
                                                                     Long mechanicId,
                                                                     String offerToken);

    List<RequestOffer> findByMechanicIdAndOutcomeIsNull(Long mechanicId);

    long countByRequestIdAndOutcome(Long requestId, OfferOutcome outcome);
}
