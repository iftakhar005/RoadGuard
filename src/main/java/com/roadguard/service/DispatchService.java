package com.roadguard.service;

import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Severity;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class DispatchService {

    private final ServiceRequestRepository requests;
    private final RequestOfferRepository offers;
    private final UserRepository users;
    private final MatchingService matching;
    private final TransactionTemplate tx;

    private final BlockingQueue<DispatchTask> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong broadcasts = new AtomicLong();

    private ExecutorService workers;
    private volatile boolean running;

    @Value("${app.dispatch.worker-threads:4}")
    private int workerThreads;

    @Value("${app.dispatch.offer-count:3}")
    private int offerCount;

    public DispatchService(ServiceRequestRepository requests,
                           RequestOfferRepository offers,
                           UserRepository users,
                           MatchingService matching,
                           TransactionTemplate tx) {
        this.requests = requests;
        this.offers = offers;
        this.users = users;
        this.matching = matching;
        this.tx = tx;
    }

    @PostConstruct
    public void start() {
        running = true;
        workers = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r);
            t.setName("dispatch-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerThreads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("Dispatch pool started with {} workers", workerThreads);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    public void enqueue(Long requestId, Severity severity) {
        queue.offer(new DispatchTask(
                requestId,
                severity == null ? Severity.MEDIUM : severity,
                sequence.incrementAndGet()));
    }

    private void workerLoop() {
        while (running) {
            DispatchTask task;
            try {
                task = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                continue;
            }
            try {
                broadcast(task.requestId());
            } catch (Exception e) {
                log.warn("Dispatch failed for request {}: {}", task.requestId(), e.toString());
            }
        }
    }

    public BroadcastResult broadcast(Long requestId) {
        BroadcastResult result = tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId).orElse(null);
            if (request == null) {
                return BroadcastResult.notFound();
            }

            RequestStatus current = request.getStatus();
            if (current != RequestStatus.SEARCHING && current != RequestStatus.REASSIGNING) {
                return BroadcastResult.skipped(current);
            }

            List<MatchingService.Candidate> candidates = matching.findCandidates(request);
            if (candidates.isEmpty()) {
                return BroadcastResult.noCandidates();
            }

            List<MatchingService.Candidate> top = candidates.size() > offerCount
                    ? candidates.subList(0, offerCount)
                    : candidates;

            Set<Long> offeredTo = new LinkedHashSet<>();
            for (MatchingService.Candidate c : top) {
                offeredTo.add(c.profile().getUser().getId());
            }

            String token = request.startNewOfferRound(offeredTo);
            if (!current.canTransitionTo(RequestStatus.OFFERED)) {
                return BroadcastResult.skipped(current);
            }
            request.setStatus(RequestStatus.OFFERED);
            requests.save(request);

            List<RequestOffer> rows = new ArrayList<>();
            for (Long mechanicUserId : offeredTo) {
                User mechanic = users.findById(mechanicUserId).orElse(null);
                if (mechanic != null) {
                    rows.add(new RequestOffer(request, mechanic, token));
                }
            }
            offers.saveAll(rows);

            return BroadcastResult.sent(token, offeredTo);
        });

        if (result != null && result.sent()) {
            broadcasts.incrementAndGet();
            log.info("Request {} offered to {} mechanics", requestId, result.offeredTo().size());
        }
        return result;
    }

    public int queueDepth() {
        return queue.size();
    }

    public long broadcastCount() {
        return broadcasts.get();
    }

    record DispatchTask(Long requestId, Severity severity, long seq) implements Comparable<DispatchTask> {
        @Override
        public int compareTo(DispatchTask other) {
            int bySeverity = Integer.compare(severity.priority(), other.severity.priority());
            return bySeverity != 0 ? bySeverity : Long.compare(seq, other.seq);
        }
    }

    public record BroadcastResult(boolean sent,
                                  String offerToken,
                                  Set<Long> offeredTo,
                                  String reason) {

        static BroadcastResult sent(String token, Set<Long> offeredTo) {
            return new BroadcastResult(true, token, offeredTo, "sent");
        }

        static BroadcastResult notFound() {
            return new BroadcastResult(false, null, Set.of(), "request not found");
        }

        static BroadcastResult noCandidates() {
            return new BroadcastResult(false, null, Set.of(), "no candidates in range");
        }

        static BroadcastResult skipped(RequestStatus status) {
            return new BroadcastResult(false, null, Set.of(), "not dispatchable from " + status);
        }
    }
}
