package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
            throw inProgress(kind);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(UUID id, PawapayInitiationResult result) {
        PawapayOperationEntity op = get(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        op.setSubmittedAt(now);
        switch (result.outcome()) {
            case ACCEPTED -> op.setStatus(PawapayOperationStatus.ACCEPTED);
            case REJECTED -> {
                op.setStatus(PawapayOperationStatus.SUBMIT_REJECTED);
                op.setFailureCode(result.failureCode());
                op.setFailureMessage(result.failureMessage());
                op.setFinalizedAt(now);
            }
            // DUPLICATE_IGNORED : pawaPay connaît déjà cet id — le poller relit le vrai statut.
            case DUPLICATE_IGNORED -> log.warn("pawaPay {} {} : DUPLICATE_IGNORED, statut laissé au poller", op.getKind(), id);
        }
        repository.save(op);
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
            log.info("pawaPay {} {} : déjà final, transition {} ignorée ({})", op.getKind(), id, newStatus, source);
            return false;
        }
        if (newStatus == PawapayOperationStatus.COMPLETED) {
            events.publishEvent(new PawapayOperationCompletedEvent(id, op.getKind(), op.getPaymentId()));
        } else if (newStatus.isFinal()) {
            events.publishEvent(new PawapayOperationFailedEvent(id, op.getKind(), op.getPaymentId(),
                    failureCode, failureMessage));
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
