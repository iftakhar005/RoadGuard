package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.Rating;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RatingRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.RatingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RatingService {

    private final RatingRepository ratings;
    private final ServiceRequestRepository requests;
    private final MechanicProfileRepository mechanics;

    @Transactional
    public RatingResponse rate(AuthUser caller, Long requestId, int stars, String comment) {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
        }
        if (comment != null && comment.length() > 500) {
            throw new IllegalArgumentException("Comment cannot exceed 500 characters");
        }

        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

        if (!request.getDriver().getId().equals(caller.getId())) {
            throw new AccessDeniedException("Only the driver of this request can rate it");
        }

        if (request.getStatus() != RequestStatus.COMPLETED) {
            throw new IllegalStateException("Only completed requests can be rated");
        }

        if (ratings.existsByRequestId(requestId)) {
            throw new IllegalStateException("This request has already been rated");
        }

        if (request.getAssignedMechanic() == null) {
            throw new IllegalStateException("This request has no assigned mechanic");
        }

        Rating rating = new Rating(
                request,
                request.getDriver(),
                request.getAssignedMechanic(),
                stars,
                comment == null ? null : comment.trim());

        try {
            ratings.saveAndFlush(rating);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This request has already been rated");
        }

        Long mechanicUserId = request.getAssignedMechanic().getId();
        MechanicProfile profile = mechanics.findByUserId(mechanicUserId)
                .orElseThrow(() -> new IllegalStateException("Mechanic profile not found"));

        List<Rating> mechanicRatings = ratings.findByMechanicIdOrderByCreatedAtDesc(mechanicUserId);
        double avg = mechanicRatings.stream().mapToInt(Rating::getStars).average().orElse(0.0);
        profile.setAvgRating(Math.round(avg * 10.0) / 10.0);
        profile.setRatingCount(mechanicRatings.size());
        mechanics.save(profile);

        return RatingResponse.from(rating);
    }
}
