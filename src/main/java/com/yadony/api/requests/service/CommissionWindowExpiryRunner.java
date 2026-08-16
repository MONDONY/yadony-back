package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.NegotiationProperties;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.NegotiationCommissionExpiredEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Les deux sorties système de l'attente de commission cash :
 * <ul>
 *   <li>expiration du délai laissé au voyageur pour régler ({@code
 *       AWAITING_COMMISSION} → {@code EXPIRED}) — sur le modèle de
 *       {@link NegotiationExpiryRunner} : chaque thread traité dans SA PROPRE
 *       transaction {@code REQUIRES_NEW}, pour qu'un conflit de verrou optimiste
 *       sur un thread ne fasse pas échouer tout le balayage ;</li>
 *   <li>balayage de rattrapage des fils {@code AUTO_REJECTED}/{@code EXPIRED}
 *       porteurs d'une commission carte débitée (ou en cours de 3DS) que
 *       personne n'a jamais fait rembourser — la place a été prise par un
 *       concurrent, ou le délai a expiré, pendant que le voyageur complétait
 *       (ou abandonnait) l'authentification. Sans ce balayage, Yadony garde
 *       indéfiniment une commission pour un accord qui n'a jamais eu lieu.</li>
 * </ul>
 *
 * <p>Auto-référence résolue au proxy Spring ({@link #self}), même pattern que
 * {@link NegotiationService#setSelf}: {@link #expireOverdueThreads()} doit
 * appeler {@link #expireOne} <em>à travers</em> le proxy pour que
 * {@code @Transactional(REQUIRES_NEW)} s'applique réellement — un appel direct
 * (self-invocation) contournerait silencieusement l'AOP Spring. Par défaut
 * {@code self = this}, donc les tests unitaires (sans contexte Spring)
 * fonctionnent sans mock supplémentaire.
 */
@Component
public class CommissionWindowExpiryRunner {

    private static final Logger log = LoggerFactory.getLogger(CommissionWindowExpiryRunner.class);

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final NegotiationProperties negotiationProperties;
    private final CashGatePort cashGatePort;

    public CommissionWindowExpiryRunner(PackageRequestRepository requestRepo,
                                        NegotiationThreadRepository threadRepo,
                                        AnnouncementRepository announcementRepo,
                                        ApplicationEventPublisher eventPublisher,
                                        AuditService auditService,
                                        NegotiationProperties negotiationProperties,
                                        CashGatePort cashGatePort) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.announcementRepo = announcementRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.negotiationProperties = negotiationProperties;
        this.cashGatePort = cashGatePort;
    }

    private CommissionWindowExpiryRunner self = this;

    @Autowired
    public void setSelf(@Lazy CommissionWindowExpiryRunner self) {
        this.self = self;
    }

    /**
     * Toutes les 5 minutes : la fenêtre par défaut est de 2 h, un balayage plus
     * espacé ferait attendre un voyageur ayant réglé juste après l'échéance —
     * ou le délai réel côté client — jusqu'à 5 min de plus, borne acceptable.
     * Désactivé en profil test via {@code yadony.negotiation.commission-expire-check-cron: "-"}.
     */
    @Scheduled(cron = "${yadony.negotiation.commission-expire-check-cron}")
    public void run() {
        expireOverdueThreads();
        refundOrphanedCommissions();
    }

    void expireOverdueThreads() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
            .minusMinutes(negotiationProperties.commissionWindowMinutes());
        for (NegotiationThreadEntity t : threadRepo.findExpiredAwaitingCommission(cutoff)) {
            UUID id = t.getId();
            try {
                self.expireOne(id);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Skipped commission-window expiry for thread {} — concurrently modified", id);
            }
        }
    }

    /**
     * Idempotent : ne traite que les threads encore {@code AWAITING_COMMISSION}
     * au moment de la mutation (pas seulement au moment du scan) — un règlement
     * ou un renoncement concurrent entre le scan et ce traitement doit être
     * respecté sans être écrasé.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireOne(UUID threadId) {
        NegotiationThreadEntity t = threadRepo.findById(threadId).orElse(null);
        if (t == null || t.getStatus() != NegotiationThreadStatus.AWAITING_COMMISSION) {
            return;
        }
        t.setStatus(NegotiationThreadStatus.EXPIRED);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(t);

        PackageRequestEntity request = requestRepo.findById(t.getPackageRequestId()).orElse(null);
        UUID senderId = null;
        if (request != null) {
            senderId = request.getSenderId();
            reopenRequestWhenNoActiveNegotiation(request);
        }
        softDeleteOrphanedDedicatedTrip(t, request);

        eventPublisher.publishEvent(new NegotiationCommissionExpiredEvent(
            t.getId(), t.getPackageRequestId(), senderId, t.getTravelerId()));
        auditService.log("NEGOTIATION_THREAD", t.getId(), "COMMISSION_WINDOW_EXPIRED", null,
            Map.of("source", "SYSTEM"));
    }

    private void reopenRequestWhenNoActiveNegotiation(PackageRequestEntity request) {
        if (request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            return;
        }
        boolean hasActiveThread = threadRepo.findByPackageRequestId(request.getId()).stream()
            .anyMatch(thread -> thread.getStatus().isActive());
        if (!hasActiveThread) {
            request.setStatus(PackageRequestStatus.OPEN);
            requestRepo.save(request);
        }
    }

    /**
     * Miroir de {@code NegotiationService#softDeleteOrphanedDedicatedTrip} /
     * {@code NegotiationExpiryRunner#softDeleteOrphanedDedicatedTrip} : un trajet
     * dédié devenu orphelin (créé exclusivement pour cette demande) doit être
     * soft-deleted, sinon il reste {@code ACTIVE} pour toujours dans « Mes
     * trajets » du voyageur.
     */
    private void softDeleteOrphanedDedicatedTrip(NegotiationThreadEntity thread, PackageRequestEntity request) {
        UUID announcementId = thread.getTravelerAnnouncementId();
        if (announcementId == null || request == null) {
            return;
        }
        announcementRepo.findById(announcementId).ifPresent(ann -> {
            if (request.getId().equals(ann.getLinkedPackageRequestId())) {
                ann.softDelete();
                announcementRepo.save(ann);
                auditService.log("ANNOUNCEMENT", ann.getId(), "DEDICATED_TRIP_ORPHANED_ON_COMMISSION_EXPIRE", null,
                    Map.of("threadId", thread.getId().toString()));
            }
        });
    }

    /**
     * Rattrapage : rembourse toute commission de négociation réellement débitée
     * (relecture Stripe par {@link CashGatePort#refundNegotiationCommissionIfCharged})
     * sur un fil {@code AUTO_REJECTED}/{@code EXPIRED} qu'aucun {@code confirm-commission}
     * client n'est jamais venu régler — voyageur qui a fermé l'app pendant la 3DS,
     * ou perdu le réseau, après que la place a été prise ou le délai dépassé.
     * {@link CashGatePort#refundNegotiationCommissionIfCharged} ne lève jamais et est
     * idempotente (no-op si déjà remboursé ou jamais débité) : pas de garde
     * supplémentaire nécessaire ici, et pas de transaction dédiée — le port ouvre
     * lui-même sa propre {@code REQUIRES_NEW}.
     */
    void refundOrphanedCommissions() {
        for (NegotiationThreadEntity t : threadRepo.findUnrefundedChargedCommissions()) {
            cashGatePort.refundNegotiationCommissionIfCharged(t.getTravelerId(), t.getId());
        }
    }
}
