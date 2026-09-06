package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle de vie d'une opération pawaPay. Ne connaît ni bid ni paiement métier : sa seule
 * sortie est {@link PawapayOperationCompletedEvent} / {@link PawapayOperationFailedEvent},
 * publiés au plus une fois par opération (transition gagnée par {@code applyTransition}).
 */
@Service
public class PawapayOperationService {

    public enum Source { CALLBACK, POLL, SYSTEM }

    private static final Logger log = LoggerFactory.getLogger(PawapayOperationService.class);

    private final PawapayOperationRepository repository;
    private final ApplicationEventPublisher events;

    public PawapayOperationService(PawapayOperationRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Réserve l'identifiant AVANT tout appel HTTP, dans sa propre transaction : une fois
     * cette méthode revenue, l'id est en base quoi qu'il arrive au réseau ensuite.
     * Garde applicative + index unique partiel : au plus une opération vivante par
     * (paiement, type).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PawapayOperationEntity create(PawapayOperationKind kind, UUID paymentId, UUID relatedOperationId,
                                         BigDecimal amount, String currency, String provider, String country,
                                         String msisdn) {
        if (paymentId != null && repository.existsByPaymentIdAndKindAndStatusIn(paymentId, kind,
                PawapayOperationStatus.LIVE_OR_DONE)) {
            throw inProgress(kind);
        }
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId,
                relatedOperationId, amount, currency, provider, country, msisdn);
        try {
            return repository.saveAndFlush(op);
        } catch (DataIntegrityViolationException e) {
            // Ne traduire en 409 QUE la violation de l'index unique visé : un
            // catch large attraperait aussi une FK/NOT NULL/CHECK sans rapport
            // (ex. paymentId pas encore visible depuis cette connexion
            // REQUIRES_NEW si l'appelant vient de créer le paiement sans
            // committer) et la déguiserait en faux 409, cause perdue.
            Throwable mostSpecific = e.getMostSpecificCause();
            String causeMessage = mostSpecific != null ? mostSpecific.getMessage() : e.getMessage();
            if (causeMessage != null
                    && causeMessage.toLowerCase(Locale.ROOT).contains("uq_pawapay_ops_live_per_payment")) {
                log.warn("pawaPay {} : index unique heurté (course avec un create concurrent) pour paymentId={} : {}",
                        kind, paymentId, causeMessage);
                throw inProgress(kind);
            }
            log.error("pawaPay {} : violation d'intégrité sans rapport avec l'index unique, propagée telle quelle "
                    + "(paymentId={}) : {}", kind, paymentId, causeMessage, e);
            throw e;
        }
    }

    /**
     * Marque la soumission — via {@link PawapayOperationRepository#markSubmittedIfStillCreated}
     * (UPDATE gardé par {@code WHERE status = CREATED}), jamais par lecture
     * d'entité puis {@code save()}. Ce read-modify-write serait sans
     * protection réelle : {@code applyTransition} (bulk JPQL) n'incrémente
     * jamais {@code @Version}, donc un callback pawaPay qui aurait déjà posé
     * COMPLETED avant que cette réponse HTTP d'initiation ne revienne serait
     * écrasé silencieusement, sans la moindre {@code OptimisticLockException}.
     * Si la garde perd la course (0 ligne touchée), on se contente de
     * journaliser : le poller (tâche 10) relira le vrai statut auprès de
     * pawaPay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(UUID id, PawapayInitiationResult result) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PawapayOperationStatus status = PawapayOperationStatus.CREATED;
        String failureCode = null;
        String failureMessage = null;
        LocalDateTime finalizedAt = null;
        switch (result.outcome()) {
            case ACCEPTED -> status = PawapayOperationStatus.ACCEPTED;
            case REJECTED -> {
                status = PawapayOperationStatus.SUBMIT_REJECTED;
                failureCode = result.failureCode();
                failureMessage = result.failureMessage();
                finalizedAt = now;
            }
            // DUPLICATE_IGNORED : pawaPay connaît déjà cet id — statut laissé à CREATED
            // (no-op), seul submittedAt avance ; le poller relira le vrai statut.
            case DUPLICATE_IGNORED -> log.warn("pawaPay {} : DUPLICATE_IGNORED, statut laissé au poller", id);
        }
        int updated = repository.markSubmittedIfStillCreated(id, status, failureCode, failureMessage, finalizedAt, now);
        if (updated == 0) {
            log.warn("pawaPay {} : callback plus rapide que la réponse pawaPay, statut du callback conservé", id);
        }
    }

    /**
     * Point de transition unique (callback, poller, système). Vrai si la ligne a bougé ;
     * l'événement final n'est publié que dans ce cas — jamais deux fois.
     */
    @Transactional
    public boolean apply(UUID id, PawapayOperationStatus newStatus, String failureCode, String failureMessage,
                         String providerTransactionId, String authorizationUrl, String raw, Source source) {
        Optional<PawapayOperationEntity> found = repository.findById(id);
        if (found.isEmpty()) {
            log.warn("pawaPay : opération inconnue {} ({})", id, source);
            return false;
        }
        PawapayOperationEntity op = found.get();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int moved = repository.applyTransition(id, newStatus, failureCode, failureMessage, providerTransactionId,
                authorizationUrl, raw,
                source == Source.CALLBACK ? now : null,
                source == Source.POLL ? now : null,
                newStatus.isFinal() ? now : null,
                now);
        if (moved == 0) {
            if (op.getStatus() == newStatus) {
                // Rejeu banal : la ligne portait déjà ce statut final (callback
                // répété par pawaPay, ou poller repassé après le callback).
                log.info("pawaPay {} {} : déjà {}, rejeu ({}) ignoré", op.getKind(), id, newStatus, source);
            } else {
                // Désaccord : deux sources (callback / poller) affirment deux
                // statuts finaux différents pour la même opération. Le rail
                // reste sûr dans les deux cas (un seul est retenu, jamais
                // deux versements), mais l'incohérence mérite un humain.
                log.warn("pawaPay {} {} : désaccord de statut final — {} déjà posé (lu avant tentative), "
                        + "{} reçu via {} ignoré — vérification humaine recommandée",
                        op.getKind(), id, op.getStatus(), newStatus, source);
            }
            return false;
        }
        if (newStatus == PawapayOperationStatus.COMPLETED) {
            events.publishEvent(new PawapayOperationCompletedEvent(id, op.getKind(), op.getPaymentId()));
        } else if (newStatus.isFinal()) {
            // Relecture obligatoire : le COALESCE d'applyTransition peut avoir
            // conservé un failureCode/failureMessage antérieur différent des
            // paramètres reçus ici (ex. code posé par un état non-final
            // précédent, cet appel-ci n'en apporte pas de nouveau). L'événement
            // doit refléter ce qui est réellement en base, pas ce qui a été
            // passé à cette méthode.
            PawapayOperationEntity reloaded = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("pawaPay : opération disparue juste après sa transition " + id));
            events.publishEvent(new PawapayOperationFailedEvent(id, op.getKind(), op.getPaymentId(),
                    reloaded.getFailureCode(), reloaded.getFailureMessage()));
        }
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<PawapayOperationEntity> findLive(UUID paymentId, PawapayOperationKind kind) {
        return repository.findFirstByPaymentIdAndKindAndStatusInOrderByCreatedAtDesc(paymentId, kind,
                PawapayOperationStatus.LIVE_OR_DONE);
    }

    @Transactional(readOnly = true)
    public Optional<PawapayOperationEntity> findLatest(UUID paymentId, PawapayOperationKind kind) {
        return repository.findFirstByPaymentIdAndKindOrderByCreatedAtDesc(paymentId, kind);
    }

    @Transactional(readOnly = true)
    public PawapayOperationEntity get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IllegalStateException("Opération pawaPay introuvable : " + id));
    }

    private static YadonyBusinessException inProgress(PawapayOperationKind kind) {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-operation-in-progress",
                "Mobile Money Operation In Progress",
                "Une opération " + kind.name().toLowerCase() + " est déjà en cours pour ce paiement");
    }
}
