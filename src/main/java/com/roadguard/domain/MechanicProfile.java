package com.roadguard.domain;

import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.ShopType;
import com.roadguard.domain.enums.Specialization;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "mechanic_profiles")
@Getter
@Setter
@NoArgsConstructor
public class MechanicProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mechanic_specializations",
            joinColumns = @JoinColumn(name = "mechanic_profile_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "specialization", length = 20)
    private Set<Specialization> specializations = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AvailabilityStatus status = AvailabilityStatus.OFFLINE;

    private Double currentLat;
    private Double currentLng;

    private Instant lastHeartbeat;

    private double avgRating = 0.0;
    private int ratingCount = 0;

    @Column(length = 120)
    private String shopName;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ShopType shopType;

    @Column(length = 30)
    private String contactPhone;

    @Column(length = 255)
    private String shopImagePath;

    @Column(length = 300)
    private String shopAddress;

    private Double shopLat;
    private Double shopLng;

    @Version
    private int version;

    public MechanicProfile(User user) {
        this.user = user;
    }

    public boolean hasSkill(Specialization needed) {
        return specializations.contains(needed);
    }

    public boolean hasShop() {
        return shopName != null && !shopName.isBlank() && shopLat != null && shopLng != null;
    }

    public boolean hasLocation() {
        return currentLat != null && currentLng != null;
    }

    public void addRating(int stars) {
        avgRating = ((avgRating * ratingCount) + stars) / (ratingCount + 1);
        ratingCount++;
    }
}
