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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AssignmentService {

    private final ServiceRequestRepository requests;
    private final MechanicProfileRepository mechanics;
    private final RequestOfferRepository offers;
    private final UserRepository users;
    private final TransactionTemplate tx;

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

    public int trackedLockCount() {
        return locks.size();
    }
}
