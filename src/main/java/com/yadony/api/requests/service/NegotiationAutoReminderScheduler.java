package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.UserZones;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.event.NegotiationNudgeSentEvent;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rappel automatique de la partie qui doit répondre dans un fil de négociation resté sans réponse.
 *
 * <p>Pendant la bêta, 5 personnes ont relancé à la main et 5 négociations ont été annulées : le
 * bouton « Relancer » repose sur celui qui attend. Ici, quand un fil {@code OPEN} est silencieux
 * depuis {@code delay} (24 h), la partie qui doit répondre reçoit le même rappel qu'une relance
 * manuelle (« X attend de vos nouvelles sur votre négociation »). Un seul rappel par période
 * d'attente : un nouveau message remet le compteur à zéro.
 *
 * <p>Garde-fous : éteint par défaut ({@code enabled}) ; jamais un fil plus vieux que
 * {@code maxAge} (les fils expirent à 72 h, et à la mise en service on ne réveille pas des fils
 * morts) ; envoi seulement dans la plage horaire locale du destinataire ; pas de rappel
 * automatique juste après une relance faite à la main.
 *
 * <p>Chaque fil est réservé dans sa propre transaction (horodatage, commit) AVANT l'envoi, qui part
 * hors transaction : un échec sur un fil n'annule ni les autres ni ne rejoue des notifications
 * déjà parties. L'événement est celui de la relance manuelle : le destinataire qui a bloqué
 * l'expéditeur n'est pas prévenu, comme pour une relance à la main.
 */
@Component
public class NegotiationAutoReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(NegotiationAutoReminderScheduler.class);

    private final NegotiationThreadRepository threadRepo;
    private final NegotiationMessageRepository messageRepo;
    private final PackageRequestRepository requestRepo;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final TransactionOperations transactions;

    @Value("${yadony.requests.auto-reminder.enabled:false}") private boolean enabled;
    @Value("${yadony.requests.auto-reminder.delay:PT24H}") private Duration delay;
    @Value("${yadony.requests.auto-reminder.max-age:PT48H}") private Duration maxAge;
    @Value("${yadony.requests.auto-reminder.window-start-hour:9}") private int windowStartHour;
    @Value("${yadony.requests.auto-reminder.window-end-hour:20}") private int windowEndHour;

    public NegotiationAutoReminderScheduler(NegotiationThreadRepository threadRepo,
                                            NegotiationMessageRepository messageRepo,
                                            PackageRequestRepository requestRepo,
                                            UserRepository userRepository,
                                            ApplicationEventPublisher eventPublisher,
                                            AuditService auditService,
                                            TransactionOperations transactions) {
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.requestRepo = requestRepo;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.transactions = transactions;
    }

    @Scheduled(cron = "${yadony.requests.auto-reminder.cron:0 20 * * * *}")
    public void remind() {
        if (!enabled) {
            return;
        }
        remindAt(Instant.now());
    }

    /** Point d'entrée testable : l'horloge est fournie. */
    int remindAt(Instant now) {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        List<UUID> candidates = threadRepo.findAutoReminderCandidates(
                nowUtc.minus(delay), nowUtc.minus(maxAge));
        int sent = 0;
        for (UUID id : candidates) {
            NegotiationNudgeSentEvent event;
            try {
                event = transactions.execute(status -> reserve(id, now, nowUtc));
            } catch (RuntimeException e) {
                // Conflit de version, fil modifié entre la lecture et l'écriture : on réessaiera
                // au prochain passage, rien n'a été réservé.
                log.warn("Negotiation auto reminder reservation failed for thread {}: {}", id, e.getMessage());
                continue;
            }
            if (event == null) {
                continue;
            }
            try {
                eventPublisher.publishEvent(event);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Negotiation auto reminder dispatch failed for thread {}: {}", id, e.getMessage());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Negotiation auto reminders: {} candidates, {} sent", candidates.size(), sent);
        }
        return sent;
    }

    /**
     * Réserve le rappel (horodatage, sans toucher {@code lastActivityAt}) et rend l'événement à
     * publier, ou {@code null} si le fil est à ignorer. Tout est revérifié ici : la boucle peut
     * durer, et le fil a pu recevoir un message ou être clos depuis la lecture des candidats.
     */
    private NegotiationNudgeSentEvent reserve(UUID threadId, Instant now, LocalDateTime nowUtc) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId).orElse(null);
        if (thread == null || thread.getStatus() != NegotiationThreadStatus.OPEN) {
            return null;
        }
        LocalDateTime silentSince = nowUtc.minus(delay);
        LocalDateTime lastActivity = thread.getLastActivityAt();
        if (lastActivity == null || lastActivity.isAfter(silentSince)) {
            return null;
        }
        if (thread.getAutoReminderAt() != null && !thread.getAutoReminderAt().isBefore(lastActivity)) {
            return null;
        }
        if (thread.getLastNudgeAt() != null && thread.getLastNudgeAt().isAfter(silentSince)) {
            return null;
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId()).orElse(null);
        if (request == null) {
            return null;
        }
        List<NegotiationMessageEntity> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (messages.isEmpty()) {
            return null;
        }
        // Celui qui attend est l'auteur du dernier message ; celui qui doit répondre, l'autre.
        UUID waitingUserId = messages.get(messages.size() - 1).getFromUserId();
        UUID mustActUserId = waitingUserId.equals(thread.getTravelerId())
                ? request.getSenderId() : thread.getTravelerId();
        UserEntity mustAct = userRepository.findById(mustActUserId).orElse(null);
        UserEntity waiting = userRepository.findById(waitingUserId).orElse(null);
        if (mustAct == null || waiting == null) {
            return null;
        }
        if (!UserZones.isWithinWindow(mustAct.getCountry(), now, windowStartHour, windowEndHour)) {
            return null;
        }
        thread.setAutoReminderAt(nowUtc);
        threadRepo.save(thread);
        auditService.log("NEGOTIATION_THREAD", threadId, "AUTO_REMINDER_SENT", null,
                Map.of("source", "SYSTEM", "target", mustActUserId.toString()));
        return new NegotiationNudgeSentEvent(
                thread.getId(), request.getId(), waitingUserId, mustActUserId, waiting.publicDisplayName());
    }
}
