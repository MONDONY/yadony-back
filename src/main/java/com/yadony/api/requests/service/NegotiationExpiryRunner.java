package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.NegotiationExpiredEvent;
import com.yadony.api.requests.event.PackageRequestExpiredEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Per-item expiry, each in its OWN transaction ({@code REQUIRES_NEW}). Split out of
 * {@link ExpirationScheduler} so one row's optimistic-lock conflict (e.g. an
 * {@code AWAITING_PAYMENT} thread finalized concurrently, now that
 * {@code NegotiationThreadEntity} carries {@code @Version}) cannot roll back the
 * whole expiry batch. Each method re-loads and re-checks status, so a concurrently
 * finalized/expired row is skipped idempotently rather than clobbered.
 *
 * <p>Must be a separate Spring bean (not a private method) so the {@code REQUIRES_NEW}
 * proxy actually applies — self-invocation would bypass it.
 */
@Component
public class NegotiationExpiryRunner {

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public NegotiationExpiryRunner(PackageRequestRepository requestRepo,
                                   NegotiationThreadRepository threadRepo,
                                   AnnouncementRepository announcementRepo,
                                   ApplicationEventPublisher eventPublisher,
                                   AuditService auditService) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.announcementRepo = announcementRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireThread(UUID threadId, NegotiationThreadStatus expectedStatus, String reason) {
        NegotiationThreadEntity t = threadRepo.findById(threadId).orElse(null);
        // Re-check status: a thread finalized/expired between the scheduler's scan and
        // now must NOT be clobbered to EXPIRED — skip idempotently.
        if (t == null || t.getStatus() != expectedStatus) {
            return;
        }
        expire(t, reason);
    }

    /**
     * Expire un fil {@code AWAITING_PAYMENT} dont le trajet ne peut plus être payé : trajet
     * terminé, annulé, retiré (ou supprimé), ou date limite de remise passée. Sans cela le
     * fil restait payable en apparence jusqu'au délai de 24 h, et chaque tentative de
     * paiement était refusée par initiate-payment (même garde que celle-ci).
     *
     * @return {@code true} si le fil a été expiré
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireThreadIfTripUnavailable(UUID threadId) {
        NegotiationThreadEntity t = threadRepo.findById(threadId).orElse(null);
        if (t == null
                || t.getStatus() != NegotiationThreadStatus.AWAITING_PAYMENT
                || t.getTravelerAnnouncementId() == null) {
            return false;
        }
        boolean unavailable = announcementRepo.findById(t.getTravelerAnnouncementId())
            .map(ann -> AnnouncementStatus.OUT_OF_MARKET.contains(ann.getStatus())
                || NegotiationService.isTripHandoverOver(ann, Instant.now()))
            .orElse(true);
        if (!unavailable) {
            return false;
        }
        expire(t, "TRIP_UNAVAILABLE");
        return true;
    }

    private void expire(NegotiationThreadEntity t, String reason) {
        t.setStatus(NegotiationThreadStatus.EXPIRED);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(t);
        reopenRequestWhenNoActiveNegotiation(t.getPackageRequestId());
        softDeleteOrphanedDedicatedTrip(t);
        eventPublisher.publishEvent(new NegotiationExpiredEvent(
            t.getId(), t.getPackageRequestId(), null, t.getTravelerId()));
        auditService.log("NEGOTIATION_THREAD", t.getId(), "EXPIRED", null,
            Map.of("source", "SYSTEM", "reason", reason));
    }

    /**
     * Miroir de {@code NegotiationService#softDeleteOrphanedDedicatedTrip} pour le
     * chemin d'expiration système : un trajet dédié (créé exclusivement pour cette
     * demande via createDedicatedTrip) devenu orphelin doit être soft-deleted,
     * sinon il reste ACTIVE pour toujours dans "Mes trajets" du voyageur.
     */
    private void softDeleteOrphanedDedicatedTrip(NegotiationThreadEntity thread) {
        UUID announcementId = thread.getTravelerAnnouncementId();
        if (announcementId == null) {
            return;
        }
        announcementRepo.findById(announcementId).ifPresent(ann -> {
            if (thread.getPackageRequestId().equals(ann.getLinkedPackageRequestId())) {
                ann.softDelete();
                announcementRepo.save(ann);
                auditService.log("ANNOUNCEMENT", ann.getId(), "DEDICATED_TRIP_ORPHANED_ON_EXPIRE", null,
                    Map.of("threadId", thread.getId().toString()));
            }
        });
    }

    private void reopenRequestWhenNoActiveNegotiation(UUID requestId) {
        PackageRequestEntity request = requestRepo.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            return;
        }
        boolean hasActiveThread = threadRepo.findByPackageRequestId(requestId).stream()
            .anyMatch(thread -> thread.getStatus().isActive());
        if (!hasActiveThread) {
            request.setStatus(PackageRequestStatus.OPEN);
            requestRepo.save(request);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireRequest(UUID requestId) {
        PackageRequestEntity req = requestRepo.findById(requestId).orElse(null);
        // findExpired matches OPEN/NEGOTIATING; re-check so a request that moved on
        // (ACCEPTED/CANCELLED/…) is skipped.
        if (req == null
                || (req.getStatus() != PackageRequestStatus.OPEN
                    && req.getStatus() != PackageRequestStatus.NEGOTIATING)) {
            return;
        }
        req.setStatus(PackageRequestStatus.EXPIRED);
        requestRepo.save(req);
        eventPublisher.publishEvent(new PackageRequestExpiredEvent(req.getId(), req.getSenderId()));
        auditService.log("PACKAGE_REQUEST", req.getId(), "EXPIRED", null,
            Map.of("source", "SYSTEM"));
    }
}
