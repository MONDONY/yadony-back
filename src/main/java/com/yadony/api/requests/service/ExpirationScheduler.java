package com.yadony.api.requests.service;

import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class ExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScheduler.class);

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final NegotiationExpiryRunner runner;
    private final RequestsConfig config;

    public ExpirationScheduler(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               NegotiationExpiryRunner runner,
                               RequestsConfig config) {
        this.requestRepo = requestRepo;
        this.threadRepo  = threadRepo;
        this.runner      = runner;
        this.config      = config;
    }

    /**
     * Scheduled entry-point: expire both package requests and negotiation threads.
     * Cron expression is externalized in yadony.requests.auto-expire-check-cron (default: every 15 min).
     * In test profile it is set to "-" (disabled) so this method is never triggered automatically.
     *
     * <p>NOT {@code @Transactional}: each item is expired in its own {@code REQUIRES_NEW}
     * transaction via {@link NegotiationExpiryRunner}, so a single row's optimistic-lock
     * conflict (a thread finalized concurrently — {@code NegotiationThreadEntity} carries
     * {@code @Version}) is skipped without rolling back the rest of the batch.
     */
    @Scheduled(cron = "${yadony.requests.auto-expire-check-cron}")
    public void runExpiration() {
        expireRequests();
        expireThreads();
    }

    /** Durée de vie d'une demande sans date (« Date flexible ») : 60 jours après sa publication. */
    static final int FLEXIBLE_REQUEST_LIFETIME_DAYS = 60;

    void expireRequests() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC);
        for (PackageRequestEntity req : requestRepo.findExpired(cutoff)) {
            UUID id = req.getId();
            safely(() -> runner.expireRequest(id), "request", id);
        }
        LocalDateTime flexibleCutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(FLEXIBLE_REQUEST_LIFETIME_DAYS);
        for (PackageRequestEntity req : requestRepo.findFlexibleExpired(flexibleCutoff)) {
            UUID id = req.getId();
            safely(() -> runner.expireRequest(id), "request", id);
        }
    }

    void expireThreads() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1) OPEN inactif
        for (NegotiationThreadEntity t : threadRepo.findInactive(now.minusHours(config.threadInactivityHours()))) {
            UUID id = t.getId();
            safely(() -> runner.expireThread(id, NegotiationThreadStatus.OPEN, "INACTIVE_OPEN"), "thread", id);
        }
        // 2) AWAITING_TRIP dépassé
        for (NegotiationThreadEntity t : threadRepo.findAwaitingTripExpired(now.minusHours(config.awaitingTripHours()))) {
            UUID id = t.getId();
            safely(() -> runner.expireThread(id, NegotiationThreadStatus.AWAITING_TRIP, "AWAITING_TRIP_TIMEOUT"), "thread", id);
        }
        // 3) AWAITING_PAYMENT dépassé
        for (NegotiationThreadEntity t : threadRepo.findAwaitingPaymentExpired(now.minusHours(config.awaitingPaymentHours()))) {
            UUID id = t.getId();
            safely(() -> runner.expireThread(id, NegotiationThreadStatus.AWAITING_PAYMENT, "AWAITING_PAYMENT_TIMEOUT"), "thread", id);
        }
        // 4) AWAITING_PAYMENT sur un trajet devenu impayable (terminé, annulé, remise passée)
        for (NegotiationThreadEntity t : threadRepo.findAwaitingPaymentWithTrip()) {
            UUID id = t.getId();
            safely(() -> runner.expireThreadIfTripUnavailable(id), "thread", id);
        }
    }

    /**
     * Runs one per-item expiry, tolerating an optimistic-lock conflict (the row was
     * finalized/modified concurrently) so it never aborts the rest of the batch.
     */
    private void safely(Runnable op, String kind, UUID id) {
        try {
            op.run();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Skipped {} {} expiry — concurrently modified", kind, id);
        }
    }
}
