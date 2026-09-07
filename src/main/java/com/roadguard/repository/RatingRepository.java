package com.roadguard.repository;

import com.roadguard.domain.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    Optional<Rating> findByRequestId(Long requestId);

    boolean existsByRequestId(Long requestId);

    List<Rating> findByMechanicIdOrderByCreatedAtDesc(Long mechanicId);
}
