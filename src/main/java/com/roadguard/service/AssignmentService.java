package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.OfferOutcome;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AssignmentService {

    private final ServiceRequestRepository requests;
    private final MechanicProfileRepository mechanics;
    private final RequestOfferRepository offers;
    private final UserRepository users;
    private final TransactionTemplate tx;

    private static final Set<RequestStatus> HANDLED_BY_MECHANIC = EnumSet.of(
            RequestStatus.EN_ROUTE,
            RequestStatus.ARRIVED,
            RequestStatus.IN_PROGRESS,
            RequestStatus.COMPLETED);

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    private volatile boolean safeMode = true;

    public AssignmentService(ServiceRequestRepository requests,
                             MechanicProfileRepository mechanics,
                             RequestOfferRepository offers,
                             UserRepository users,
                             TransactionTemplate tx) {
        this.requests = requests;
        this.mechanics = mechanics;
        this.offers = offers;
        this.users = users;
        this.tx = tx;
    }

    public AcceptOutcome accept(Long requestId, Long mechanicUserId, String offerToken) {
        if (!safeMode) {
            return runAccept(requestId, mechanicUserId, offerToken);
        }

        ReentrantLock lock = locks.computeIfAbsent(requestId, id -> new ReentrantLock(true));
        lock.lock();
        try {
            return runAccept(requestId, mechanicUserId, offerToken);
        } finally {
            lock.unlock();
        }
    }

    private AcceptOutcome runAccept(Long requestId, Long mechanicUserId, String offerToken) {
        try {
            return tx.execute(status -> attempt(requestId, mechanicUserId, offerToken));
        } catch (OptimisticLockingFailureException e) {
            return AcceptOutcome.ALREADY_TAKEN;
        }
    }

    private AcceptOutcome attempt(Long requestId, Long mechanicUserId, String offerToken) {
        ServiceRequest request = requests.findById(requestId).orElse(null);
        if (request == null) {
            return AcceptOutcome.REQUEST_NOT_FOUND;
        }

        RequestStatus current = request.getStatus();
        if (current != RequestStatus.OFFERED && current != RequestStatus.REASSIGNING) {
            return AcceptOutcome.ALREADY_TAKEN;
        }
        if (request.isAssigned()) {
            return AcceptOutcome.ALREADY_TAKEN;
        }
        if (offerToken == null || !offerToken.equals(request.getCurrentOfferToken())) {
            return AcceptOutcome.OFFER_EXPIRED;
        }
        if (!request.wasOfferedTo(mechanicUserId)) {
            return AcceptOutcome.NOT_OFFERED_TO_YOU;
        }
        if (!current.canTransitionTo(RequestStatus.ACCEPTED)) {
            return AcceptOutcome.ALREADY_TAKEN;
        }

        MechanicProfile profile = mechanics.findByUserId(mechanicUserId).orElse(null);
        if (profile == null || profile.getStatus() != AvailabilityStatus.ONLINE) {
            return AcceptOutcome.MECHANIC_NOT_AVAILABLE;
        }

        User mechanic = users.findById(mechanicUserId).orElse(null);
        if (mechanic == null) {
            return AcceptOutcome.MECHANIC_NOT_AVAILABLE;
        }

        request.setAssignedMechanic(mechanic);
        request.setStatus(RequestStatus.ACCEPTED);
        request.setAcceptedAt(Instant.now());
        requests.save(request);

        profile.setStatus(AvailabilityStatus.BUSY);
        mechanics.save(profile);

        resolveOffers(requestId, offerToken, mechanicUserId);
        return AcceptOutcome.ACCEPTED;
    }

    private void resolveOffers(Long requestId, String offerToken, Long winnerUserId) {
        List<RequestOffer> round = offers.findByRequestIdAndOfferToken(requestId, offerToken);
        for (RequestOffer offer : round) {
            if (offer.getOutcome() != null) {
                continue;
            }
            offer.setOutcome(offer.getMechanic().getId().equals(winnerUserId)
                    ? OfferOutcome.ACCEPTED
                    : OfferOutcome.TAKEN);
        }
        offers.saveAll(round);
    }

    public StatusChange advanceStatus(Long requestId, Long mechanicUserId, RequestStatus target) {
        if (!HANDLED_BY_MECHANIC.contains(target)) {
            return StatusChange.NOT_ALLOWED;
        }
        return withLock(requestId, () -> tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId).orElse(null);
            if (request == null) {
                return StatusChange.NOT_FOUND;
            }
            User assigned = request.getAssignedMechanic();
            if (assigned == null || !assigned.getId().equals(mechanicUserId)) {
                return StatusChange.NOT_YOURS;
            }
            if (!request.getStatus().canTransitionTo(target)) {
                return StatusChange.NOT_ALLOWED;
            }

            request.setStatus(target);
            if (target == RequestStatus.COMPLETED) {
                request.setCompletedAt(Instant.now());
                mechanics.findByUserId(mechanicUserId).ifPresent(profile -> {
                    profile.setStatus(AvailabilityStatus.ONLINE);
                    mechanics.save(profile);
                });
            }
            requests.save(request);
            return StatusChange.OK;
        }));
    }

    public StatusChange cancel(Long requestId, Long driverUserId) {
        return withLock(requestId, () -> tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId).orElse(null);
            if (request == null) {
                return StatusChange.NOT_FOUND;
            }
            if (!request.getDriver().getId().equals(driverUserId)) {
                return StatusChange.NOT_YOURS;
            }
            if (!request.getStatus().canTransitionTo(RequestStatus.CANCELLED)) {
                return StatusChange.NOT_ALLOWED;
            }

            User assigned = request.getAssignedMechanic();
            if (assigned != null) {
                mechanics.findByUserId(assigned.getId()).ifPresent(profile -> {
                    if (profile.getStatus() == AvailabilityStatus.BUSY) {
                        profile.setStatus(AvailabilityStatus.ONLINE);
                        mechanics.save(profile);
                    }
                });
            }

            request.setStatus(RequestStatus.CANCELLED);
            request.setCurrentOfferToken(null);
            request.getOfferedTo().clear();
            requests.save(request);
            return StatusChange.OK;
        }));
    }

    public ExpiryOutcome expireRound(Long requestId,
                                     String offerToken,
                                     double maxRadiusKm,
                                     int maxAttempts) {
        return withLock(requestId, () -> tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId).orElse(null);
            if (request == null || request.getStatus() != RequestStatus.OFFERED) {
                return ExpiryOutcome.NOT_EXPIRED;
            }
            if (offerToken != null && !offerToken.equals(request.getCurrentOfferToken())) {
                return ExpiryOutcome.NOT_EXPIRED;
            }

            String expiredToken = request.getCurrentOfferToken();
            request.setSearchAttempts(request.getSearchAttempts() + 1);
            request.setCurrentOfferToken(null);
            request.getOfferedTo().clear();
            request.setOfferedAt(null);

            if (expiredToken != null) {
                List<RequestOffer> round = offers.findByRequestIdAndOfferToken(requestId, expiredToken);
                for (RequestOffer offer : round) {
                    if (offer.getOutcome() == null) {
                        offer.setOutcome(OfferOutcome.TIMEOUT);
                    }
                }
                offers.saveAll(round);
            }

            if (request.getSearchAttempts() > maxAttempts) {
                if (!request.getStatus().canTransitionTo(RequestStatus.ESCALATED)) {
                    return ExpiryOutcome.NOT_EXPIRED;
                }
                request.setStatus(RequestStatus.ESCALATED);
                requests.save(request);
                return ExpiryOutcome.ESCALATED;
            }

            double widened = Math.min(request.getSearchRadiusKm() * 2, maxRadiusKm);
            request.setSearchRadiusKm(widened);
            if (!request.getStatus().canTransitionTo(RequestStatus.SEARCHING)) {
                return ExpiryOutcome.NOT_EXPIRED;
            }
            request.setStatus(RequestStatus.SEARCHING);
            requests.save(request);
            return ExpiryOutcome.WIDENED;
        }));
    }

    public <T> T runLocked(Long requestId, java.util.function.Supplier<T> action) {
        return withLock(requestId, action);
    }

    private <T> T withLock(Long requestId, java.util.function.Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(requestId, id -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean releaseFromMechanic(Long requestId, Long mechanicUserId) {
        ReentrantLock lock = locks.computeIfAbsent(requestId, id -> new ReentrantLock(true));
        lock.lock();
        try {
            Boolean released = tx.execute(status -> {
                ServiceRequest request = requests.findById(requestId).orElse(null);
                if (request == null) {
                    return false;
                }
                User assigned = request.getAssignedMechanic();
                if (assigned == null || !assigned.getId().equals(mechanicUserId)) {
                    return false;
                }
                if (!request.getStatus().isAssignedToMechanic()) {
                    return false;
                }
                if (!request.getStatus().canTransitionTo(RequestStatus.REASSIGNING)) {
                    return false;
                }

                request.setAssignedMechanic(null);
                request.setStatus(RequestStatus.REASSIGNING);
                request.setCurrentOfferToken(null);
                request.getOfferedTo().clear();
                requests.save(request);
                return true;
            });
            return Boolean.TRUE.equals(released);
        } catch (OptimisticLockingFailureException e) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    public AcceptOutcome decline(Long requestId, Long mechanicUserId, String offerToken) {
        return tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId).orElse(null);
            if (request == null) {
                return AcceptOutcome.REQUEST_NOT_FOUND;
            }
            if (offerToken == null || !offerToken.equals(request.getCurrentOfferToken())) {
                return AcceptOutcome.OFFER_EXPIRED;
            }
            if (!request.wasOfferedTo(mechanicUserId)) {
                return AcceptOutcome.NOT_OFFERED_TO_YOU;
            }

            offers.findByRequestIdAndOfferToken(requestId, offerToken).stream()
                    .filter(o -> o.getMechanic().getId().equals(mechanicUserId))
                    .filter(o -> o.getOutcome() == null)
                    .forEach(o -> {
                        o.setOutcome(OfferOutcome.DECLINED);
                        offers.save(o);
                    });

            return AcceptOutcome.ACCEPTED;
        });
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public void setSafeMode(boolean safeMode) {
        this.safeMode = safeMode;
    }

    public enum StatusChange {
        OK,
        NOT_FOUND,
        NOT_YOURS,
        NOT_ALLOWED
    }

    public enum ExpiryOutcome {
        WIDENED,
        ESCALATED,
        NOT_EXPIRED
    }

    public int trackedLockCount() {
        return locks.size();
    }
}
