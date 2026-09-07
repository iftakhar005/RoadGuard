package com.roadguard.domain;

import com.roadguard.domain.enums.OfferOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "request_offers")
@Getter
@Setter
@NoArgsConstructor
public class RequestOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private ServiceRequest request;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private User mechanic;

    @Column(nullable = false, length = 64)
    private String offerToken;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OfferOutcome outcome;

    public RequestOffer(ServiceRequest request, User mechanic, String offerToken) {
        this.request = request;
        this.mechanic = mechanic;
        this.offerToken = offerToken;
    }
}
