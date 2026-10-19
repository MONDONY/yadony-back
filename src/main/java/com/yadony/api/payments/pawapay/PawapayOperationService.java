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
        return create(kind, PawapayOperationPurpose.BID_PAYMENT, null, paymentId, relatedOperationId,
                amount, currency, provider, country, msisdn);
    }

    /**
     * Variante complète : porte le {@code purpose} et l'{@code userId} d'une opération de wallet
     * (recharge ou remboursement), sans paiement de colis. L'ancienne signature délègue ici avec
     * {@code BID_PAYMENT} et {@code userId = null}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PawapayOperationEntity create(PawapayOperationKind kind, PawapayOperationPurpose purpose, UUID userId,
                                         UUID paymentId, UUID relatedOperationId, BigDecimal amount, String currency,
                                         String provider, String country, String msisdn) {
        if (paymentId != null && repository.existsByPaymentIdAndKindAndStatusIn(paymentId, kind,
                PawapayOperationStatus.LIVE_OR_DONE)) {
            throw inProgress(kind);
        }
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, purpose, userId, paymentId,
                relatedOperationId, amount, currency, provider, country, msisdn);
        return save(op);
    }

    /**
     * Opération d'un supplément de prix (purpose {@code BID_SUPPLEMENT}) : {@code userId}
     * obligatoire (expéditeur pour le dépôt et le remboursement, voyageur pour le versement,
     * {@code chk_pawapay_ops_wallet_user}), {@code payment_id} toujours nul. Au plus une opération
     * vivante ou aboutie par (supplément, type) : garde applicative + index
     * {@code uq_pawapay_ops_live_per_supplement} (V338), traduits en 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PawapayOperationEntity createForSupplement(PawapayOperationKind kind, UUID userId, UUID bidSupplementId,
                                                      UUID relatedOperationId, BigDecimal amount, String currency,
                                                      String provider, String country, String msisdn) {
        if (userId == null || bidSupplementId == null) {
            throw new IllegalArgumentException("Opération de supplément sans utilisateur ou sans supplément");
        }
        if (repository.existsByBidSupplementIdAndKindAndStatusIn(bidSupplementId, kind,
                PawapayOperationStatus.LIVE_OR_DONE)) {
            throw inProgress(kind);
        }
        return save(PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, userId, bidSupplementId,
                relatedOperationId, amount, currency, provider, country, msisdn));
    }

    private PawapayOperationEntity save(PawapayOperationEntity op) {
        PawapayOperationKind kind = op.getKind();
        UUID paymentId = op.getPaymentId();
        UUID userId = op.getUserId();
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
            String lowerCause = causeMessage != null ? causeMessage.toLowerCase(Locale.ROOT) : "";
            if (lowerCause.contains("uq_pawapay_ops_live_per_payment")) {
                log.warn("pawaPay {} : index unique heurté (course avec un create concurrent) pour paymentId={} : {}",
                        kind, paymentId, causeMessage);
                throw inProgress(kind);
            }
            // Même filet pour un supplément de prix (V338) : deux initiations concurrentes
            // franchissent toutes deux la garde applicative, l'index tranche.
            if (lowerCause.contains("uq_pawapay_ops_live_per_supplement")) {
                log.warn("pawaPay {} : opération déjà vivante pour le supplément {} : {}",
                        kind, op.getBidSupplementId(), causeMessage);
                throw inProgress(kind);
            }
            // Même filet, pour la recharge du portefeuille : la garde applicative lit avant
            // d'écrire, deux requêtes concurrentes la franchissent toutes les deux ; l'index
            // unique partiel (V331, qui ne compte que les recharges à topup_guard vrai, donc
            // non expirées) est l'arbitre. Le message rendu est celui de la garde, l'utilisateur
            // ne doit pas voir deux textes selon qui des deux a gagné la course.
            if (lowerCause.contains("uq_pawapay_ops_live_wallet_topup")) {
                log.warn("pawaPay {} : recharge déjà en attente (course avec un create concurrent) pour userId={} : {}",
                        kind, userId, causeMessage);
                throw PawapayErrors.walletTopupAlreadyPending();
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
     * journaliser : le poller relira le vrai statut auprès de
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
            events.publishEvent(new PawapayOperationCompletedEvent(id, op.getKind(), op.getPurpose(),
                    op.getPaymentId(), op.getUserId()));
        } else if (newStatus.isFinal()) {
            // Relecture obligatoire : le COALESCE d'applyTransition peut avoir
            // conservé un failureCode/failureMessage antérieur différent des
            // paramètres reçus ici (ex. code posé par un état non-final
            // précédent, cet appel-ci n'en apporte pas de nouveau). L'événement
            // doit refléter ce qui est réellement en base, pas ce qui a été
            // passé à cette méthode.
            PawapayOperationEntity reloaded = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("pawaPay : opération disparue juste après sa transition " + id));
            events.publishEvent(new PawapayOperationFailedEvent(id, op.getKind(), op.getPurpose(), op.getPaymentId(),
                    op.getUserId(), reloaded.getFailureCode(), reloaded.getFailureMessage()));
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

    /** Opération vivante ou aboutie d'un supplément, pour ce type. */
    @Transactional(readOnly = true)
    public Optional<PawapayOperationEntity> findLiveForSupplement(UUID bidSupplementId, PawapayOperationKind kind) {
        return repository.findFirstByBidSupplementIdAndKindAndStatusInOrderByCreatedAtDesc(bidSupplementId, kind,
                PawapayOperationStatus.LIVE_OR_DONE);
    }

    /** Dernière opération d'un supplément pour ce type, quel que soit son statut. */
    @Transactional(readOnly = true)
    public Optional<PawapayOperationEntity> findLatestForSupplement(UUID bidSupplementId, PawapayOperationKind kind) {
        return repository.findFirstByBidSupplementIdAndKindOrderByCreatedAtDesc(bidSupplementId, kind);
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
