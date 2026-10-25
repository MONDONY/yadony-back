package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versement carte au voyageur en trois temps, sans connexion JDBC ni verrou de ligne tenu pendant
 * l'appel Stripe (PROD_CHECKLIST 9.23) :
 * <ol>
 *   <li><b>réserver</b> ({@link #reserve}, dans la transaction courte de l'appelant qui porte le
 *       claim atomique ESCROW → RELEASED) : la ligne {@link StripeReleaseIntentEntity} fige montant,
 *       devise, destination et charge source, avec un bail de {@link #LEASE} ;</li>
 *   <li><b>appeler</b> ({@link #execute}, hors transaction) : recherche d'un Transfer déjà émis
 *       ({@link StripeTransferLookup}), sinon {@code Transfer.create} avec la clé stable
 *       {@code transfer-<paymentId>-r<révision>} (ou capture legacy, clé {@code capture-<paymentId>}) ;</li>
 *   <li><b>finaliser</b> (transaction courte) : UPDATE conditionnel {@code PENDING → DONE}, identifiant
 *       du Transfer, audit et {@link PaymentReleasedEvent} — un seul traitement y parvient.</li>
 * </ol>
 *
 * <p>Issue de l'appel :
 * <ul>
 *   <li>Stripe <b>refuse</b> (4xx : requête invalide, compte, permission) : rien n'a été créé, la
 *       réservation passe REVERTED et le claim est annulé (RELEASED → ESCROW, seulement sans Transfer
 *       enregistré), comme l'ancien rollback ;</li>
 *   <li>issue <b>incertaine</b> (réseau, 5xx, limite de débit, clé d'idempotence en cours) : le
 *       paiement reste RELEASED, la réservation PENDING, et {@link StripeReleaseRecoveryJob} rejoue
 *       à l'identique (même clé, même montant ; la recherche Stripe retrouve un Transfer créé malgré
 *       l'erreur). Au bout de {@link #ALERT_AFTER_ATTEMPTS} tentatives, alerte
 *       {@code RELEASE_PENDING_<paymentId>}. Plus jamais d'annulation du claim alors qu'un Transfer a
 *       pu partir.</li>
 * </ul>
 *
 * <p><b>Jamais deux Transfers</b> (revue race-condition, PROD_CHECKLIST 9.23) :
 * <ul>
 *   <li>chaque prise de bail (réservation, reprise du job, annulation admin) pose un nouveau
 *       {@code lease_token} ; toute écriture conditionnelle (envoi, finalisation en échec, annulation,
 *       reprise différée) l'exige : un détenteur dont le bail a expiré ne peut plus rien écrire, et
 *       n'envoie plus rien ({@code markCreateSent} refusé avant {@code Transfer.create}) ;</li>
 *   <li>{@code create_sent_at} trace chaque envoi de {@code Transfer.create}. Une recherche Stripe
 *       vide ne prouve rien tant qu'un envoi a moins de {@link #SEND_SAFETY} (la requête peut être
 *       encore en route, ou pas encore listée) : pas de retour en séquestre, pas d'annulation admin,
 *       donc pas de nouvelle révision ni de nouvelle clé. Au sein d'une révision, la clé est la même :
 *       Stripe déduplique ;</li>
 *   <li>une nouvelle révision ne part que d'une réservation REVERTED ou CANCELLED, posée sous ces
 *       conditions, et sa première action est encore une recherche Stripe par {@code transfer_group} ;</li>
 *   <li>retour en séquestre, annulation et finalisation verrouillent d'abord le colis (ordre
 *       colis → paiement), comme le claim de livraison et la libération forcée.</li>
 * </ul>
 */
@Component
public class StripeReleaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(StripeReleaseExecutor.class);

    /**
     * Bail d'une réservation : au-delà, le job peut la reprendre. Plus long que le pire appel du
     * SDK (connexion 30 s + lecture 80 s, trois essais avec les relances de 9.24 : 5,5 min).
     */
    public static final Duration LEASE = Duration.ofMinutes(10);

    /**
     * Après un envoi de {@code Transfer.create}, délai pendant lequel une recherche Stripe vide ne
     * prouve pas l'absence de Transfer (requête en vol d'un détenteur au bail expiré, réponse ou
     * listage en retard) : bail + 5 min. Avant ce délai, ni retour en séquestre ni annulation.
     */
    public static final Duration SEND_SAFETY = LEASE.plus(Duration.ofMinutes(5));

    /** Préfixe (16) + UUID (36) = 52, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String PENDING_ALERT_PREFIX = "RELEASE_PENDING_";

    static final int ALERT_AFTER_ATTEMPTS = 3;
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    public enum Result {
        /** Transfer (ou capture legacy) créé et finalisé par ce traitement. */
        RELEASED,
        /** Un Transfer existait déjà chez Stripe : base réalignée, aucun second Transfer. */
        REALIGNED,
        /** Issue incertaine : paiement RELEASED, réservation PENDING, reprise par le job. */
        PENDING_RETRY,
        /** Stripe a refusé sans rien créer : claim annulé, paiement ESCROW. */
        REVERTED,
        /** Une garde est apparue avant l'appel (aucun appel Stripe) : claim annulé, paiement ESCROW. */
        BLOCKED,
        /** Déjà finalisé ou annulé par un autre traitement, ou bail détenu ailleurs. */
        NOT_OWNED
    }

    public record Execution(Result result, String stripeObjectId, String error) {
        public boolean paid() {
            return result == Result.RELEASED || result == Result.REALIGNED;
        }
    }

    /** Ce que la réservation fige pour rejouer l'appel Stripe à l'identique. */
    public record ReleaseSpec(UUID paymentId, StripeReleaseIntentEntity.Kind kind, Long amountMinor, String currency,
                              String destination, String sourceTransaction, String source, String auditAction,
                              UUID bidId, UUID travelerId, UUID senderId, java.util.Set<String> overriddenGuards) {
        public ReleaseSpec(UUID paymentId, StripeReleaseIntentEntity.Kind kind, Long amountMinor, String currency,
                           String destination, String sourceTransaction, String source, String auditAction,
                           UUID bidId, UUID travelerId, UUID senderId) {
            this(paymentId, kind, amountMinor, currency, destination, sourceTransaction, source, auditAction,
                    bidId, travelerId, senderId, java.util.Set.of());
        }
    }

    /**
     * Gardes revérifiées juste avant l'appel Stripe, dans une transaction courte.
     * @return la raison du blocage, {@code null} si le versement peut partir
     */
    public interface ReleaseGuard {
        String blockingReason(StripeReleaseIntentEntity intent);

        /**
         * Verrou de ligne du colis dans la transaction courante, pris en premier (ordre colis →
         * annonce → paiement) par le retour en séquestre, l'annulation et la finalisation.
         */
        default void lockParcel(UUID bidId) {}
    }

    /** Aucune garde (tests unitaires des autres composants). */
    static final ReleaseGuard NO_GUARD = intent -> null;

    private final StripeReleaseIntentRepository intentRepository;
    private final PaymentRepository paymentRepository;
    private final StripeTransferLookup transferLookup;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminAlertEscalator alertEscalator;
    private final TransactionOperations transaction;
    private final Clock clock;
    private final ReleaseGuard guard;

    @Autowired
    public StripeReleaseExecutor(StripeReleaseIntentRepository intentRepository, PaymentRepository paymentRepository,
                                 StripeTransferLookup transferLookup, AuditService auditService,
                                 ApplicationEventPublisher eventPublisher, AdminAlertEscalator alertEscalator,
                                 PlatformTransactionManager transactionManager, StripeReleaseGuard guard) {
        this(intentRepository, paymentRepository, transferLookup, auditService, eventPublisher, alertEscalator,
                EscrowCaptureService.requiresNew(transactionManager), Clock.systemUTC(), guard);
    }

    StripeReleaseExecutor(StripeReleaseIntentRepository intentRepository, PaymentRepository paymentRepository,
                          StripeTransferLookup transferLookup, AuditService auditService,
                          ApplicationEventPublisher eventPublisher, AdminAlertEscalator alertEscalator,
                          TransactionOperations transaction, Clock clock, ReleaseGuard guard) {
        this.intentRepository = intentRepository;
        this.paymentRepository = paymentRepository;
        this.transferLookup = transferLookup;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.alertEscalator = alertEscalator;
        this.transaction = transaction;
        this.clock = clock;
        this.guard = guard != null ? guard : NO_GUARD;
    }

    /**
     * Phase 1 : réserve le versement, dans la transaction de l'appelant qui vient de poser le claim
     * ESCROW → RELEASED (verrou de ligne du paiement tenu : aucune réservation concurrente). Une
     * réservation REVERTED est réutilisée ; une réservation DONE signifie qu'un versement est déjà
     * parti pour ce paiement : on refuse (l'exception annule le claim), jamais un second versement.
     */
    public StripeReleaseIntentEntity reserve(ReleaseSpec spec) {
        LocalDateTime now = now();
        StripeReleaseIntentEntity intent = intentRepository.findByPaymentId(spec.paymentId()).orElse(null);
        if (intent != null && intent.getStatus() != StripeReleaseIntentEntity.Status.REVERTED
                && intent.getStatus() != StripeReleaseIntentEntity.Status.CANCELLED) {
            throw new IllegalStateException("Versement du paiement " + spec.paymentId() + " déjà réservé ("
                    + intent.getStatus() + ", " + intent.getStripeObjectId() + ") : aucun second versement");
        }
        if (intent == null) {
            intent = new StripeReleaseIntentEntity();
            intent.setPaymentId(spec.paymentId());
            intent.setRevision(1);
        } else {
            // Nouvelle réservation après une annulation : nouvelle révision, donc nouvelle clé
            // d'idempotence (les paramètres, ex. le net, peuvent différer de la précédente).
            intent.setRevision(intent.getRevision() + 1);
        }
        intent.setKind(spec.kind());
        intent.setStatus(StripeReleaseIntentEntity.Status.PENDING);
        intent.setAmountMinor(spec.amountMinor());
        intent.setCurrency(spec.currency());
        intent.setDestination(spec.destination());
        intent.setSourceTransaction(spec.sourceTransaction());
        intent.setSource(spec.source());
        intent.setAuditAction(spec.auditAction());
        intent.setBidId(spec.bidId());
        intent.setTravelerId(spec.travelerId());
        intent.setSenderId(spec.senderId());
        intent.setOverriddenGuards(overriddenGuards(spec.overriddenGuards()));
        intent.setAttempts(1);
        intent.setLeaseUntil(now.plus(LEASE));
        intent.setLeaseToken(UUID.randomUUID());
        // Nouvelle révision : aucun envoi encore (la précédente a été close sous preuve, ou jamais envoyée).
        intent.setCreateSentAt(null);
        intent.setLastError(null);
        intent.setStripeObjectId(null);
        StripeReleaseIntentEntity saved = intentRepository.save(intent);
        return saved != null ? saved : intent;
    }

    /**
     * Reprise par le job : prend le bail d'une réservation échue (transaction courte), puis appelle
     * Stripe et finalise. {@link Result#NOT_OWNED} si elle n'est plus à reprendre.
     */
    public Execution resume(UUID intentId) {
        LocalDateTime now = now();
        UUID token = UUID.randomUUID();
        Boolean leased = transaction.execute(tx ->
                intentRepository.claimLease(intentId, now, now.plus(LEASE), token) == 1);
        if (!Boolean.TRUE.equals(leased)) {
            return new Execution(Result.NOT_OWNED, null, null);
        }
        StripeReleaseIntentEntity intent = intentRepository.findById(intentId).orElse(null);
        if (intent == null || !token.equals(intent.getLeaseToken())) {
            // Bail repris entre-temps par un autre traitement : on ne fait rien.
            return new Execution(Result.NOT_OWNED, null, null);
        }
        try {
            return execute(intent);
        } catch (RuntimeException e) {
            // Panne locale (base, garde) pendant la reprise : délai croissant et alerte au seuil,
            // comme une issue Stripe incertaine. Le paiement reste réservé.
            log.error("Reprise du versement {} en échec", intent.getPaymentId(), e);
            return retryLater(intent, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Phases 2 et 3 : appel Stripe hors transaction, puis finalisation conditionnelle. L'appelant ne
     * doit tenir aucune transaction (sa connexion resterait empruntée pendant l'appel) : refusé
     * avant tout appel Stripe, la réservation est alors reprise par le job.
     */
    public Execution execute(StripeReleaseIntentEntity intent) {
        return execute(intent, null);
    }

    /**
     * Même chose avec le paiement déjà lu par l'appelant : seuls ses champs immuables sont utilisés
     * (PaymentIntent, montant, commission, devise, date de création), jamais son statut.
     */
    public Execution execute(StripeReleaseIntentEntity intent, PaymentEntity snapshot) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Appel Stripe du versement " + intent.getPaymentId()
                    + " demandé sous transaction : refusé (PROD_CHECKLIST 9.23)");
        }
        UUID paymentId = intent.getPaymentId();
        PaymentEntity payment = snapshot != null ? snapshot : paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.error("Versement réservé pour un paiement introuvable : {}", paymentId);
            return new Execution(Result.NOT_OWNED, null, "paiement introuvable");
        }
        // Dernier envoi connu pour cette révision, lu APRÈS la prise de bail : aucun autre
        // traitement ne peut plus envoyer (jeton), seul un envoi antérieur peut être en vol.
        LocalDateTime priorSent = intent.getCreateSentAt();
        // Vrai dès que, DANS CETTE exécution, la recherche Stripe a réussi et n'a trouvé aucun
        // Transfer, ET qu'aucun envoi antérieur ne peut être encore en vol : un refus ou une garde
        // qui suit peut alors rendre le séquestre en toute sûreté.
        boolean revertable = false;
        try {
            if (intent.getKind() == StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE) {
                return captureLegacy(intent, payment, priorSent);
            }
            // La clé d'idempotence ne vit que 24 h : on cherche d'abord chez Stripe un Transfer déjà
            // émis pour ce paiement (essai précédent dont la réponse s'est perdue, Transfer d'avant
            // ce lot). S'il existe, la base est réalignée dessus, aucun second Transfer.
            Optional<String> existing;
            try {
                existing = transferLookup.findExistingTransfer(
                        paymentId, intent.getDestination(), payment.getCreatedAt());
            } catch (StripeException lookupFailure) {
                if (priorSent == null) {
                    // Aucun Transfer n'a jamais été envoyé pour cette révision (sinon create_sent_at
                    // serait posé, sous jeton, avant l'envoi). Recherche impossible = on ne verse pas
                    // (fail-closed) et on rend le séquestre ; l'auto-réparation retentera.
                    log.error("Versement du paiement {} : recherche Stripe d'un Transfer existant impossible ({})",
                            paymentId, lookupFailure.getMessage());
                    return revert(intent, "recherche Stripe impossible : "
                            + lookupFailure.getClass().getSimpleName() + ": " + lookupFailure.getMessage());
                }
                // Un Transfer a été envoyé lors d'un essai précédent : la base ne revient jamais en
                // séquestre sur une recherche ratée. On rejouera.
                return retryLater(intent, "recherche Stripe impossible : "
                        + lookupFailure.getClass().getSimpleName() + ": " + lookupFailure.getMessage());
            }
            if (existing.isPresent()) {
                log.warn("Payment {}: Stripe Transfer {} already exists — no second Transfer, DB realigned",
                        paymentId, existing.get());
                return finalizeDone(intent, payment, existing.get(), true);
            }
            // Recherche vide : preuve seulement si aucun envoi antérieur n'est récent.
            revertable = sendSettled(priorSent);
            // Gardes revérifiées sous verrou juste avant l'appel.
            String blocked = guard.blockingReason(intent);
            if (blocked != null) {
                return blockedBeforeCall(intent, blocked, revertable);
            }
            if (!markCreateSent(intent)) {
                // Bail repris pendant la recherche ou la garde : aucun envoi hors bail.
                return new Execution(Result.NOT_OWNED, null, null);
            }
            Transfer transfer = Transfer.create(transferParams(intent),
                    RequestOptions.builder().setIdempotencyKey(transferKey(intent)).build());
            return finalizeDone(intent, payment, transfer != null ? transfer.getId() : null, false);
        } catch (StripeException e) {
            // Refus 4xx de NOTRE envoi : rien créé sous cette clé (une requête antérieure de même
            // clé réussie aurait été rejouée, pas refusée) ; retour en séquestre seulement si aucun
            // envoi antérieur n'est encore possible en vol.
            return onStripeFailure(intent, e, revertable || priorSent == null);
        }
    }

    /** Aucun envoi, ou dernier envoi plus vieux que {@link #SEND_SAFETY} (plus aucune requête en vol). */
    private boolean sendSettled(LocalDateTime sentAt) {
        return sentAt == null || !sentAt.isAfter(now().minus(SEND_SAFETY));
    }

    /** Trace l'envoi sous jeton, juste avant l'appel ; {@code false} si le bail n'est plus à nous. */
    private boolean markCreateSent(StripeReleaseIntentEntity intent) {
        LocalDateTime sentAt = now();
        Boolean marked = transaction.execute(tx ->
                intentRepository.markCreateSent(intent.getId(), intent.getLeaseToken(), sentAt) == 1);
        if (!Boolean.TRUE.equals(marked)) {
            log.warn("Versement du paiement {} : bail repris par un autre traitement, aucun envoi",
                    intent.getPaymentId());
            return false;
        }
        intent.setCreateSentAt(sentAt);
        return true;
    }

    private Execution captureLegacy(StripeReleaseIntentEntity intent, PaymentEntity payment,
                                    LocalDateTime priorSent) throws StripeException {
        // Destination charge historique : capturer verse au voyageur (transfer_data posé à la création).
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        String status = pi != null ? pi.getStatus() : null;
        if (pi == null || "canceled".equals(status) || "requires_payment_method".equals(status)) {
            return revert(intent, "PaymentIntent " + status + " : plus rien à capturer");
        }
        if (!EscrowCaptureService.STATUS_SUCCEEDED.equals(status)) {
            // PaymentIntent relu à l'instant et non capturé : rien n'a été versé, sauf capture
            // encore en vol d'un envoi récent.
            String blocked = guard.blockingReason(intent);
            if (blocked != null) {
                return blockedBeforeCall(intent, blocked, sendSettled(priorSent));
            }
            if (!markCreateSent(intent)) {
                return new Execution(Result.NOT_OWNED, null, null);
            }
            // Clé d'idempotence stable : une reprise ne déclenche pas une seconde capture.
            pi.capture(PaymentIntentCaptureParams.builder().build(),
                    RequestOptions.builder().setIdempotencyKey("capture-" + intent.getPaymentId()).build());
        }
        return finalizeDone(intent, payment, payment.getStripePaymentIntentId(), false);
    }

    /** Seules les gardes levables par dérogation sont retenues, dans un ordre stable. */
    static String overriddenGuards(java.util.Set<String> guards) {
        if (guards == null || guards.isEmpty()) {
            return null;
        }
        String kept = java.util.stream.Stream.of(StripeReleaseIntentEntity.GUARD_DISPUTED,
                        StripeReleaseIntentEntity.GUARD_BENEFICIARY_HELD)
                .filter(guards::contains).collect(java.util.stream.Collectors.joining(","));
        return kept.isEmpty() ? null : kept;
    }

    /** Clé d'idempotence du Transfer, propre à la révision de la réservation. */
    static String transferKey(StripeReleaseIntentEntity intent) {
        return "transfer-" + intent.getPaymentId() + "-r" + intent.getRevision();
    }

    static TransferCreateParams transferParams(StripeReleaseIntentEntity intent) {
        UUID paymentId = intent.getPaymentId();
        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(intent.getAmountMinor())
                .setCurrency(intent.getCurrency())
                .setDestination(intent.getDestination())
                .putMetadata("bid_id", intent.getBidId() != null ? intent.getBidId().toString() : "")
                .putMetadata("payment_id", paymentId != null ? paymentId.toString() : "")
                .putMetadata("source", String.valueOf(intent.getSource()));
        if (paymentId != null) {
            // Groupe stable, filtrable chez Stripe quel que soit le compte de destination.
            builder.setTransferGroup(StripeTransferLookup.transferGroup(paymentId));
        }
        if (intent.getSourceTransaction() != null && !intent.getSourceTransaction().isBlank()) {
            builder.setSourceTransaction(intent.getSourceTransaction());
        }
        return builder.build();
    }

    /** Phase 3 : un seul traitement fait passer la réservation en DONE, trace et notifie. */
    private Execution finalizeDone(StripeReleaseIntentEntity intent, PaymentEntity payment, String objectId,
                                   boolean realigned) {
        UUID paymentId = intent.getPaymentId();
        Boolean mine = transaction.execute(tx -> {
            guard.lockParcel(intent.getBidId());
            if (intentRepository.markDone(intent.getId(), objectId) == 0) {
                return false;
            }
            if (intent.getKind() == StripeReleaseIntentEntity.Kind.TRANSFER && objectId != null) {
                paymentRepository.recordStripeTransferId(paymentId, objectId);
                // Suivi du virement bancaire du voyageur (bankpayout/), ouvert après commit.
                eventPublisher.publishEvent(new com.yadony.api.payments.events.StripeTransferRecordedEvent(
                        paymentId, intent.getBidId(), intent.getTravelerId(), objectId, intent.getDestination()));
            }
            if (realigned) {
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("paymentId", String.valueOf(paymentId));
                audit.put("bidId", String.valueOf(intent.getBidId()));
                audit.put("transferId", objectId);
                audit.put("source", intent.getSource());
                auditService.log("PAYMENT", paymentId, "TRANSFER_ALREADY_EXISTS_REALIGNED", intent.getBidId(), audit);
                // Versement confirmé : notification, cache et métrique comme un versement direct
                // (markDone garantit un seul gagnant, donc un seul événement).
                publishReleased(intent, payment);
                return true;
            }
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("paymentId", String.valueOf(paymentId));
            audit.put("bidId", String.valueOf(intent.getBidId()));
            audit.put("piId", String.valueOf(payment.getStripePaymentIntentId()));
            audit.put("amount", payment.getAmount() != null ? payment.getAmount().toPlainString() : "null");
            audit.put("legacy", String.valueOf(intent.getKind() == StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE));
            audit.put("source", intent.getSource());
            if (objectId != null && intent.getKind() == StripeReleaseIntentEntity.Kind.TRANSFER) {
                audit.put("transferId", objectId);
            }
            auditService.log("PAYMENT", paymentId, intent.getAuditAction(), intent.getBidId(), audit);
            publishReleased(intent, payment);
            return true;
        });
        if (!Boolean.TRUE.equals(mine)) {
            log.info("Versement du paiement {} déjà finalisé par un autre traitement", paymentId);
            return new Execution(Result.NOT_OWNED, objectId, null);
        }
        resolvePendingAlert(paymentId);
        resolveBlockedAlert(paymentId);
        log.info("Versement du paiement {} {} ({}, source {})", paymentId, realigned ? "réaligné" : "effectué",
                objectId, intent.getSource());
        return new Execution(realigned ? Result.REALIGNED : Result.RELEASED, objectId, null);
    }

    /** Publié dans la transaction : les écouteurs AFTER_COMMIT (cache, notification) partent au commit. */
    private void publishReleased(StripeReleaseIntentEntity intent, PaymentEntity payment) {
        if (intent.getBidId() != null && intent.getTravelerId() != null && payment != null) {
            eventPublisher.publishEvent(PaymentReleasedEvent.card(intent.getBidId(), intent.getTravelerId(),
                    intent.getSenderId(), payment.getAmount(), payment.getCommissionAmount(), payment.getCurrency()));
        }
    }

    private Execution onStripeFailure(StripeReleaseIntentEntity intent, StripeException e, boolean revertable) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (StripeFailures.definitiveRefusal(e) && revertable) {
            // Rien n'a été créé : aucun envoi antérieur pour cette révision, ou recherche Stripe
            // vide et dernier envoi antérieur trop ancien pour être en vol, puis refus 4xx.
            log.error("Versement du paiement {} refusé par Stripe : {}", intent.getPaymentId(), error, e);
            return revert(intent, error);
        }
        if (StripeFailures.definitiveRefusal(e)) {
            // Un essai précédent a pu créer le Transfer (issue inconnue) : jamais de retour en
            // séquestre. Réservation conservée, alerte immédiate, décision admin.
            log.error("Versement du paiement {} refusé par Stripe après un essai incertain : {}",
                    intent.getPaymentId(), error, e);
            return retryLater(intent, error, true);
        }
        return retryLater(intent, error, false);
    }

    /**
     * Une garde est apparue entre le claim et l'appel (aucun appel Stripe n'est parti). Premier
     * essai : rien n'a jamais été demandé à Stripe pour cette réservation, le claim est annulé
     * (ESCROW, la garde décide). Reprise : un essai précédent a pu créer un Transfer, le paiement
     * reste réservé et l'admin tranche (alerte).
     */
    private Execution blockedBeforeCall(StripeReleaseIntentEntity intent, String reason, boolean revertable) {
        String error = "garde apparue avant l'appel Stripe : " + reason;
        log.warn("Versement du paiement {} bloqué avant l'appel Stripe : {}", intent.getPaymentId(), reason);
        if (!revertable) {
            // Un envoi récent peut être en vol : jamais de retour en séquestre, l'admin tranche.
            raiseBlockedAlert(intent, reason, false);
            return retryLater(intent, error, true);
        }
        // Stripe vient de confirmer qu'aucun versement n'existe : le séquestre est rendu (la garde
        // décide ensuite : remboursement, litige…). Alerte levée après le résultat de l'annulation.
        Execution reverted = revert(intent, error, "ESCROW_RELEASE_BLOCKED_BEFORE_STRIPE");
        if (reverted.result() != Result.REVERTED) {
            return reverted;
        }
        raiseBlockedAlert(intent, reason, true);
        return new Execution(Result.BLOCKED, null, error);
    }

    /** Préfixe (16) + UUID (36) = 52 caractères. */
    public static final String BLOCKED_ALERT_PREFIX = "RELEASE_BLOCKED_";

    private void raiseBlockedAlert(StripeReleaseIntentEntity intent, String reason, boolean escrowRestored) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", String.valueOf(intent.getPaymentId()));
        context.put("bidId", String.valueOf(intent.getBidId()));
        context.put("source", intent.getSource());
        context.put("reason", reason);
        try {
            alertEscalator.raiseOnce(BLOCKED_ALERT_PREFIX + intent.getPaymentId(),
                    "Versement du paiement " + intent.getPaymentId() + " bloqué juste avant l'appel Stripe (" + reason
                            + ") : aucun Transfer n'est parti, "
                            + (escrowRestored ? "le paiement est de nouveau en séquestre, à trancher"
                                    : "le paiement reste réservé, à trancher (annulation de la réservation possible)"),
                    context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {}{} non levée", BLOCKED_ALERT_PREFIX, intent.getPaymentId(), alertFailure);
        }
    }

    /** Issue incertaine : la réservation reste PENDING (paiement RELEASED), prochaine reprise différée. */
    private Execution retryLater(StripeReleaseIntentEntity intent, String error) {
        return retryLater(intent, error, false);
    }

    /** @param needsAdmin issue que seule une décision admin tranchera : alerte immédiate, reprise espacée */
    private Execution retryLater(StripeReleaseIntentEntity intent, String error, boolean needsAdmin) {
        int attempts = Math.max(1, intent.getAttempts());
        LocalDateTime retryAt = now().plus(needsAdmin ? MAX_BACKOFF : backoff(attempts));
        Boolean mine = transaction.execute(tx ->
                intentRepository.recordRetry(intent.getId(), intent.getLeaseToken(), error, retryAt) == 1);
        if (!Boolean.TRUE.equals(mine)) {
            // Bail repris (ou réservation close) entre-temps : le nouveau détenteur décide.
            log.warn("Versement du paiement {} : bail repris, issue « {} » non enregistrée", intent.getPaymentId(), error);
            return new Execution(Result.NOT_OWNED, null, error);
        }
        log.warn("Versement du paiement {} incertain (essai {}) : {} — paiement RELEASED, reprise à {}",
                intent.getPaymentId(), attempts, error, retryAt);
        if (needsAdmin || attempts >= ALERT_AFTER_ATTEMPTS) {
            raisePendingAlert(intent, error, attempts);
        }
        return new Execution(Result.PENDING_RETRY, null, error);
    }

    /** Stripe a refusé sans rien créer : réservation REVERTED et claim annulé (RELEASED → ESCROW). */
    private Execution revert(StripeReleaseIntentEntity intent, String error) {
        return revert(intent, error, "ESCROW_RELEASE_REFUSED_BY_STRIPE");
    }

    private Execution revert(StripeReleaseIntentEntity intent, String error, String auditAction) {
        UUID paymentId = intent.getPaymentId();
        Boolean mine = transaction.execute(tx -> {
            guard.lockParcel(intent.getBidId());
            if (intentRepository.markReverted(intent.getId(), intent.getLeaseToken(), error) == 0) {
                return false;
            }
            int reverted = paymentRepository.revertUnpaidReleaseClaim(paymentId);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("paymentId", String.valueOf(paymentId));
            audit.put("bidId", String.valueOf(intent.getBidId()));
            audit.put("source", intent.getSource());
            audit.put("error", error);
            audit.put("claimReverted", String.valueOf(reverted == 1));
            auditService.log("PAYMENT", paymentId, auditAction, intent.getBidId(), audit);
            if (reverted == 0) {
                log.error("Versement du paiement {} refusé mais claim non annulé (statut changé entre-temps)",
                        paymentId);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(mine)) {
            return new Execution(Result.NOT_OWNED, null, error);
        }
        resolvePendingAlert(paymentId);
        resolveBlockedAlert(paymentId);
        return new Execution(Result.REVERTED, null, error);
    }

    static Duration backoff(int attempts) {
        long minutes = 5L << Math.min(Math.max(attempts - 1, 0), 4);
        Duration wait = Duration.ofMinutes(minutes);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    private void raisePendingAlert(StripeReleaseIntentEntity intent, String error, int attempts) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", String.valueOf(intent.getPaymentId()));
        context.put("bidId", String.valueOf(intent.getBidId()));
        context.put("source", intent.getSource());
        context.put("attempts", String.valueOf(attempts));
        context.put("error", error);
        try {
            alertEscalator.raiseOnce(PENDING_ALERT_PREFIX + intent.getPaymentId(),
                    "Versement du paiement " + intent.getPaymentId() + " non confirmé par Stripe après " + attempts
                            + " essais (" + error + ") : le paiement est réservé (RELEASED), la reprise automatique "
                            + "continue avec la même clé ; vérifier le Transfer dans Stripe, ne pas forcer un second versement",
                    context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {}{} non levée", PENDING_ALERT_PREFIX, intent.getPaymentId(), alertFailure);
        }
    }

    private void resolvePendingAlert(UUID paymentId) {
        try {
            alertEscalator.resolveOpen(PENDING_ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close : {}", PENDING_ALERT_PREFIX, paymentId, e.getMessage());
        }
    }

    private void resolveBlockedAlert(UUID paymentId) {
        try {
            alertEscalator.resolveOpen(BLOCKED_ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close : {}", BLOCKED_ALERT_PREFIX, paymentId, e.getMessage());
        }
    }

    // ── Annulation par un super-admin ────────────────────────────────────────

    /** Issue de l'annulation admin d'une réservation. */
    public enum CancelResult {
        /** Aucun Transfer chez Stripe : réservation CANCELLED, paiement de nouveau ESCROW. */
        CANCELLED,
        /** Un Transfer existe : réservation réalignée DONE, le voyageur est payé. */
        REALIGNED,
        /** Recherche Stripe impossible : rien n'a changé. */
        LOOKUP_FAILED,
        /** Aucune réservation en attente pour ce paiement. */
        NOT_PENDING,
        /** Un appel Stripe est en vol (bail actif) : réessayer plus tard. */
        IN_PROGRESS,
        /** Réservé : type de versement non pris en charge par l'annulation (plus renvoyé depuis la capture legacy). */
        UNSUPPORTED
    }

    public record Cancellation(CancelResult result, String transferId, String error) {}

    /**
     * Annulation d'une réservation PENDING par un super-admin (paiement bloqué en RELEASED sans
     * versement). Bail pris d'abord sous un nouveau jeton (refusé si un appel est en vol ou si un
     * envoi date de moins de {@link #SEND_SAFETY}), puis vérification chez Stripe hors transaction :
     * <ul>
     *   <li>Transfer : recherche par {@code transfer_group} ; trouvé → réaligné DONE ; aucun → claim
     *       annulé (RELEASED → ESCROW, paiement remboursable) et réservation CANCELLED ;</li>
     *   <li>capture legacy : PaymentIntent relu ; {@code succeeded} → DONE (le voyageur est payé) ;
     *       sinon → CANCELLED et ESCROW ;</li>
     *   <li>vérification impossible → rien ne change.</li>
     * </ul>
     * Chaque issue est auditée au nom de l'admin, avec son motif.
     */
    public Cancellation cancelReservation(UUID paymentId, UUID adminId, String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Annulation de la réservation " + paymentId + " sous transaction : refusée");
        }
        StripeReleaseIntentEntity intent = intentRepository.findByPaymentId(paymentId).orElse(null);
        if (intent == null || intent.getStatus() != StripeReleaseIntentEntity.Status.PENDING) {
            return new Cancellation(CancelResult.NOT_PENDING, null, null);
        }
        LocalDateTime now = now();
        UUID token = UUID.randomUUID();
        Boolean leased = transaction.execute(tx ->
                intentRepository.claimForAdmin(intent.getId(), now, now.plus(LEASE), token) == 1);
        if (!Boolean.TRUE.equals(leased)) {
            auditAdmin(intent, adminId, reason, "ESCROW_RELEASE_CANCEL_REFUSED_IN_PROGRESS", "appel Stripe en vol");
            return new Cancellation(CancelResult.IN_PROGRESS, null, null);
        }
        // Relu après la prise de bail : plus aucun envoi possible, sauf un envoi antérieur en vol.
        StripeReleaseIntentEntity held = intentRepository.findById(intent.getId()).orElse(null);
        if (held == null || !token.equals(held.getLeaseToken())) {
            auditAdmin(intent, adminId, reason, "ESCROW_RELEASE_CANCEL_REFUSED_IN_PROGRESS", "bail repris");
            return new Cancellation(CancelResult.IN_PROGRESS, null, null);
        }
        if (!sendSettled(held.getCreateSentAt())) {
            // Envoi de moins de SEND_SAFETY : une vérification Stripe vide ne prouve rien.
            String error = "annulation admin refusée : appel Stripe envoyé à " + held.getCreateSentAt()
                    + ", issue pas encore certaine";
            transaction.executeWithoutResult(tx -> intentRepository.releaseAdminLease(held.getId(), token, now(), error));
            auditAdmin(held, adminId, reason, "ESCROW_RELEASE_CANCEL_REFUSED_IN_PROGRESS", error);
            return new Cancellation(CancelResult.IN_PROGRESS, null, error);
        }
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        Optional<String> paidWith;
        try {
            paidWith = held.getKind() == StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE
                    ? legacyCaptured(payment)
                    : transferLookup.findExistingTransfer(paymentId, held.getDestination(),
                            payment != null ? payment.getCreatedAt() : null);
        } catch (StripeException e) {
            String error = "annulation admin : vérification Stripe impossible : " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            transaction.executeWithoutResult(tx -> intentRepository.releaseAdminLease(held.getId(), token, now(), error));
            auditAdmin(held, adminId, reason, "ESCROW_RELEASE_CANCEL_LOOKUP_FAILED", error);
            return new Cancellation(CancelResult.LOOKUP_FAILED, null, error);
        }
        if (paidWith.isPresent()) {
            Execution done = finalizeDone(held, payment, paidWith.get(), true);
            boolean realigned = done.result() == Result.REALIGNED;
            if (realigned) {
                auditAdmin(held, adminId, reason, "ESCROW_RELEASE_CANCEL_REALIGNED_BY_ADMIN",
                        "versement trouvé chez Stripe : " + paidWith.get());
            }
            return new Cancellation(realigned ? CancelResult.REALIGNED : CancelResult.NOT_PENDING,
                    held.getKind() == StripeReleaseIntentEntity.Kind.TRANSFER ? paidWith.get() : null, null);
        }
        Boolean cancelled = transaction.execute(tx -> {
            guard.lockParcel(held.getBidId());
            if (intentRepository.markCancelled(held.getId(), token, "annulée par l'admin : " + reason) == 0) {
                return false;
            }
            int reverted = paymentRepository.revertUnpaidReleaseClaim(paymentId);
            Map<String, Object> audit = adminAudit(held, reason);
            audit.put("claimReverted", String.valueOf(reverted == 1));
            auditService.log("PAYMENT", paymentId, "ESCROW_RELEASE_CANCELLED_BY_ADMIN", adminId, audit);
            return true;
        });
        if (!Boolean.TRUE.equals(cancelled)) {
            return new Cancellation(CancelResult.NOT_PENDING, null, null);
        }
        resolvePendingAlert(paymentId);
        resolveBlockedAlert(paymentId);
        log.warn("Réservation du versement {} annulée par l'admin {} : aucun versement chez Stripe", paymentId, adminId);
        return new Cancellation(CancelResult.CANCELLED, null, null);
    }

    /** Capture legacy : le PaymentIntent relu dit seul si le voyageur a été payé. */
    private Optional<String> legacyCaptured(PaymentEntity payment) throws StripeException {
        if (payment == null || payment.getStripePaymentIntentId() == null) {
            return Optional.empty();
        }
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        return pi != null && EscrowCaptureService.STATUS_SUCCEEDED.equals(pi.getStatus())
                ? Optional.of(payment.getStripePaymentIntentId()) : Optional.empty();
    }

    private Map<String, Object> adminAudit(StripeReleaseIntentEntity intent, String reason) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("paymentId", String.valueOf(intent.getPaymentId()));
        audit.put("bidId", String.valueOf(intent.getBidId()));
        audit.put("kind", String.valueOf(intent.getKind()));
        audit.put("source", intent.getSource());
        audit.put("revision", String.valueOf(intent.getRevision()));
        audit.put("attempts", String.valueOf(intent.getAttempts()));
        audit.put("reason", reason);
        return audit;
    }

    /** Trace, au nom de l'admin et avec son motif, une demande d'annulation qui n'a pas annulé. */
    private void auditAdmin(StripeReleaseIntentEntity intent, UUID adminId, String reason, String action, String outcome) {
        try {
            Map<String, Object> audit = adminAudit(intent, reason);
            audit.put("outcome", outcome);
            transaction.executeWithoutResult(tx ->
                    auditService.log("PAYMENT", intent.getPaymentId(), action, adminId, audit));
        } catch (RuntimeException e) {
            log.error("Audit {} du paiement {} non écrit", action, intent.getPaymentId(), e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
