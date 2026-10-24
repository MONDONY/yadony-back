package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentStaleCommissionEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.WalletCommissionCollector;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * États de la commission d'une modification espèces, prélevée AU VOYAGEUR quand il l'accepte : chaque méthode
 * publique est une transaction courte, sans aucun appel Stripe (ceux-ci vivent dans {@link BidAmendmentCashRail}
 * et {@link BidAmendmentCashRefunds}, entre deux de ces transactions). Toute transition relit l'état sous verrou :
 * un rejeu (webhook, confirmation de l'app, filet) ne produit aucun second effet.
 *
 * <p>Ordre des verrous : colis, puis trajet quand la transition peut clore la modification (kilos rendus), puis
 * modification. Une action qui ne verrouille que la modification créerait un interblocage avec les chemins du
 * colis ({@code BidAmendmentService}, remise).
 *
 * <p>La commission principale du colis ({@code bids.commission_*}) n'est JAMAIS touchée ici.
 */
@Component
public class BidAmendmentCashLedger {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCashLedger.class);

    /**
     * Modification dont la commission peut être rendue : close sans aboutir, remboursée, ou acceptée mais dont le
     * colis s'arrête là (fin de colis, « pas apportée » : l'appelant en a décidé, voir {@link BidAmendmentCashRefunds#settle}).
     */
    static final Set<BidAmendmentStatus> SETTLEABLE = EnumSet.of(BidAmendmentStatus.DECLINED,
            BidAmendmentStatus.EXPIRED, BidAmendmentStatus.CANCELLED, BidAmendmentStatus.ACCEPTED,
            BidAmendmentStatus.REFUNDED);

    /** Issue de l'enregistrement d'une commission prélevée par carte. */
    public enum ChargeOutcome {
        /** Modification en attente de sa commission : désormais acquise. */
        ACCEPTED,
        /** Commission arrivée sur une modification déjà close : elle sera rendue. */
        LATE_REFUND,
        /** Déjà enregistrée (rejeu) : aucun effet. */
        ALREADY,
        /** PaymentIntent qui n'est pas celle de l'essai courant : remboursée, la modification n'est pas touchée. */
        STALE,
        /** Modification introuvable, d'un autre rail, ou PaymentIntent qui n'est pas la sienne. */
        IGNORED
    }

    /** Ce qu'il faut pour rembourser la commission par carte, hors transaction. */
    public record CardRefundClaim(UUID amendmentId, UUID bidId, UUID travelerId, String paymentIntentId) {
    }

    private final BidAmendmentRepository amendments;
    private final BidAmendmentService service;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactions;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final BidAmendmentWindow window;
    private final AdminAlertEscalator alerts;

    public BidAmendmentCashLedger(BidAmendmentRepository amendments, BidAmendmentService service, BidRepository bids,
                                  AnnouncementRepository announcements, WalletService walletService,
                                  WalletTransactionRepository walletTransactions, AuditService auditService,
                                  ApplicationEventPublisher events, BidAmendmentWindow window,
                                  AdminAlertEscalator alerts) {
        this.amendments = amendments;
        this.service = service;
        this.bids = bids;
        this.announcements = announcements;
        this.walletService = walletService;
        this.walletTransactions = walletTransactions;
        this.auditService = auditService;
        this.events = events;
        this.window = window;
        this.alerts = alerts;
    }

    /** Lecture fraîche, sans verrou. */
    @Transactional(readOnly = true)
    public Optional<BidAmendmentEntity> find(UUID amendmentId) {
        return amendments.findById(amendmentId);
    }

    /**
     * Identifiant de l'essai d'acceptation par carte en cours : {@code respondedAt}, posé à chaque acceptation et
     * remis à zéro à chaque échec, en millisecondes. Porté par la PaymentIntent (métadonnée
     * {@code commission_attempt}) pour reconnaître celle de l'essai courant avant même de l'avoir enregistrée.
     * Nul hors d'un essai.
     */
    static String attemptOf(BidAmendmentEntity a) {
        return a.getRespondedAt() == null ? null
                : String.valueOf(a.getRespondedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * Un nouvel essai par carte est en cours après un essai échoué : {@code commission_status} reste {@code FAILED}
     * et {@code commission_payment_intent_id} garde la PaymentIntent de l'essai précédent jusqu'à ce que celle du
     * nouvel essai la remplace.
     */
    static boolean retryInProgress(BidAmendmentEntity a) {
        return a.getStatus() == BidAmendmentStatus.COMMISSION_PENDING
                && a.getCommissionStatus() == CommissionStatus.FAILED;
    }

    /**
     * Cette PaymentIntent est-elle celle de l'essai courant ? Celle déjà enregistrée, ou, tant que l'essai attend
     * sa commission, une PaymentIntent inconnue qui porte le numéro de l'essai courant (le webhook peut devancer
     * l'enregistrement). Jamais une PaymentIntent inconnue sans ce numéro.
     */
    private static boolean isCurrent(BidAmendmentEntity a, String paymentIntentId, String attempt) {
        if (paymentIntentId == null) {
            return false;
        }
        if (paymentIntentId.equals(a.getCommissionPaymentIntentId())) {
            return !retryInProgress(a);
        }
        return a.getStatus() == BidAmendmentStatus.COMMISSION_PENDING && attempt != null
                && attempt.equals(attemptOf(a))
                && (a.getCommissionPaymentIntentId() == null || retryInProgress(a));
    }

    /**
     * PaymentIntent de commission en attente de la 3DS du voyageur : enregistrée pour l'essai courant, en
     * remplacement de celle de l'essai précédent le cas échéant. Toute autre PaymentIntent n'est pas enregistrée
     * quand une autre est déjà posée.
     */
    @Transactional
    public BidAmendmentEntity commissionRequiresAction(UUID amendmentId, String paymentIntentId, String attempt) {
        BidAmendmentEntity a = lock(amendmentId, false).orElseThrow();
        if (retryInProgress(a) && !paymentIntentId.equals(a.getCommissionPaymentIntentId())
                && attempt != null && attempt.equals(attemptOf(a))) {
            a.setCommissionPaymentIntentId(paymentIntentId);
            a.setCommissionStatus(null);
        } else if (a.getCommissionPaymentIntentId() == null) {
            a.setCommissionPaymentIntentId(paymentIntentId);
        }
        if (a.getStatus() == BidAmendmentStatus.COMMISSION_PENDING && a.getCommissionStatus() == null
                && paymentIntentId.equals(a.getCommissionPaymentIntentId())) {
            a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
            audit(a, "BID_AMENDMENT_COMMISSION_3DS_REQUIRED", a.getTravelerId(), Map.of("piId", paymentIntentId));
        }
        amendments.save(a);
        return a;
    }

    /**
     * Commission prélevée sur la carte (débit, confirmation après 3DS, webhook, filet). Seule la PaymentIntent de
     * l'essai courant ({@link #isCurrent}) peut accepter la modification : jamais une PaymentIntent d'un essai
     * précédent, ni une inconnue.
     * <ul>
     *   <li>modification en attente de sa commission, dans les délais et colis encore accepté :
     *       {@code COMMISSION_PENDING → ACCEPTED} ({@link BidAmendmentService#markAccepted}) ;</li>
     *   <li>échéance de confirmation dépassée, ou colis terminé : la commission est enregistrée, la modification
     *       close ({@code COMMISSION_NOT_CONFIRMED}, {@code BID_ENDED}) et la commission rendue ;</li>
     *   <li>modification déjà close : commission enregistrée puis rendue après le commit ;</li>
     *   <li>toute autre PaymentIntent qui aboutit (essai précédent, commission déjà payée autrement, modification
     *       qui n'en attend plus) : {@code STALE}, remboursée par PaymentIntent
     *       ({@link BidAmendmentStaleCommissionEvent}), la modification n'est pas touchée.</li>
     * </ul>
     * Rejeu : aucun effet.
     *
     * @param attempt numéro d'essai porté par la PaymentIntent, nul si inconnu.
     */
    @Transactional
    public ChargeOutcome commissionCharged(UUID amendmentId, String paymentIntentId, String attempt, String source) {
        BidAmendmentEntity a = lock(amendmentId, true).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH) {
            log.error("Commission {} d'une modification inconnue ou hors rail espèces {} ({}) : à vérifier",
                    paymentIntentId, amendmentId, source);
            return ChargeOutcome.IGNORED;
        }
        boolean current = isCurrent(a, paymentIntentId, attempt);
        CommissionStatus status = a.getCommissionStatus();
        if (current && paymentIntentId.equals(a.getCommissionPaymentIntentId())
                && (status == CommissionStatus.CHARGED || status == CommissionStatus.REFUNDED
                || status == CommissionStatus.REFUND_FAILED)) {
            return ChargeOutcome.ALREADY;
        }
        BidAmendmentStatus state = a.getStatus();
        boolean closed = !state.isOpen() && !state.isSecured();
        if (!current) {
            return stale(a, paymentIntentId, source);
        }
        if (state != BidAmendmentStatus.COMMISSION_PENDING && !closed) {
            // Essai déclaré échoué (PROPOSED), ou modification déjà acquise : rien à faire de cette
            // PaymentIntent que la rendre.
            return stale(a, paymentIntentId, source);
        }
        a.setCommissionPaymentIntentId(paymentIntentId);
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("piId", String.valueOf(paymentIntentId));
        extra.put("source", source);
        if (state == BidAmendmentStatus.COMMISSION_PENDING) {
            if (window.isLapsed(a)) {
                amendments.save(a);
                audit(a, "BID_AMENDMENT_LATE_COMMISSION_REFUND", null, extra);
                service.expireUnderBidLock(a);
                return ChargeOutcome.LATE_REFUND;
            }
            BidEntity bid = bids.findById(a.getBidId()).orElse(null);
            if (bid == null || bid.getStatus() != BidStatus.ACCEPTED) {
                amendments.save(a);
                audit(a, "BID_AMENDMENT_LATE_COMMISSION_REFUND", null, extra);
                service.cancelOpen(a, BidAmendmentClosedReason.BID_ENDED);
                return ChargeOutcome.LATE_REFUND;
            }
            audit(a, "BID_AMENDMENT_COMMISSION_CHARGED_CARD", a.getTravelerId(), extra);
            service.markAccepted(a);
            return ChargeOutcome.ACCEPTED;
        }
        amendments.save(a);
        audit(a, "BID_AMENDMENT_LATE_COMMISSION_REFUND", null, extra);
        events.publishEvent(new BidAmendmentRefundRequestedEvent(a.getId(), a.getBidId(), a.getRail(),
                "LATE_COMMISSION"));
        return ChargeOutcome.LATE_REFUND;
    }

    /** PaymentIntent de commission aboutie qui n'accepte rien : à rendre, par son identifiant, hors transaction. */
    private ChargeOutcome stale(BidAmendmentEntity a, String paymentIntentId, String source) {
        audit(a, "BID_AMENDMENT_STALE_COMMISSION", null,
                Map.of("piId", String.valueOf(paymentIntentId), "source", String.valueOf(source)));
        if (paymentIntentId != null) {
            events.publishEvent(new BidAmendmentStaleCommissionEvent(a.getId(), a.getBidId(), paymentIntentId));
        }
        return ChargeOutcome.STALE;
    }

    /**
     * Signale une PaymentIntent de commission aboutie hors de l'essai courant, constatée par un appelant qui l'a
     * relue chez Stripe ({@code BidAmendmentCashRail}) : elle sera rendue.
     */
    @Transactional
    public void reportStale(UUID amendmentId, String paymentIntentId, String source) {
        amendments.findById(amendmentId).ifPresent(a -> stale(a, paymentIntentId, source));
    }

    /**
     * Abandon d'un nouvel essai refusé avant d'avoir créé sa PaymentIntent (la précédente est encore en cours) :
     * {@code COMMISSION_PENDING → PROPOSED}, {@code respondedAt} remis à zéro. Commission toujours {@code FAILED}.
     *
     * @return vrai si l'état a changé.
     */
    @Transactional
    public boolean abortRetry(UUID amendmentId) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || !retryInProgress(a)) {
            return false;
        }
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setRespondedAt(null);
        amendments.save(a);
        audit(a, "BID_AMENDMENT_COMMISSION_RETRY_ABORTED", a.getTravelerId(), Map.of());
        return true;
    }

    /**
     * Commission non prélevée : carte refusée, 3DS échouée ou abandonnée, PaymentIntent annulé. Modification en
     * attente de sa commission : elle repasse en {@code PROPOSED} ({@code respondedAt} remis à zéro, commission
     * {@code FAILED}), kilos gardés et rien débité : le voyageur peut réessayer d'accepter, avec le portefeuille
     * ou une autre carte, jusqu'à {@code expiresAt}. L'expéditeur n'en voit rien. Sur une modification déjà close,
     * seule la commission est marquée. Une commission déjà prélevée n'est jamais écrasée. Seul l'échec de
     * l'essai courant compte : le signal d'une autre PaymentIntent (essai précédent, inconnue) est ignoré.
     *
     * @param paymentIntentId nul quand l'échec n'a produit aucune PaymentIntent (carte refusée à la création).
     * @param attempt         numéro d'essai porté par la PaymentIntent, nul si inconnu.
     * @return vrai si l'état a changé.
     */
    @Transactional
    public boolean commissionFailed(UUID amendmentId, String paymentIntentId, String attempt, String reason) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH) {
            return false;
        }
        CommissionStatus current = a.getCommissionStatus();
        boolean retry = retryInProgress(a);
        if (current == CommissionStatus.CHARGED || current == CommissionStatus.REFUNDED
                || current == CommissionStatus.REFUND_FAILED || (current == CommissionStatus.FAILED && !retry)) {
            return false;
        }
        if (paymentIntentId != null) {
            boolean sameAsStored = paymentIntentId.equals(a.getCommissionPaymentIntentId());
            if (retry && sameAsStored) {
                // Signal tardif de la PaymentIntent de l'essai précédent : l'essai en cours n'est pas concerné.
                return false;
            }
            if (!sameAsStored && !isCurrent(a, paymentIntentId, attempt)) {
                return false;
            }
            a.setCommissionPaymentIntentId(paymentIntentId);
        }
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionStatus(CommissionStatus.FAILED);
        if (a.getStatus() == BidAmendmentStatus.COMMISSION_PENDING) {
            a.setStatus(BidAmendmentStatus.PROPOSED);
            a.setRespondedAt(null);
        }
        amendments.save(a);
        audit(a, "BID_AMENDMENT_COMMISSION_FAILED", a.getTravelerId(), Map.of("reason", String.valueOf(reason)));
        return true;
    }

    /**
     * Recrédite au voyageur la commission prélevée sur son solde, ligne par ligne (devise du colis, puis
     * complément converti), en {@code REFUND} avec {@code payment_ref = "amendment-refund-<id>"} : hors du préfixe
     * {@code refund-} de la commission principale (INV-09 {@code COMMISSION_RECREDIT_DOUBLE}). Idempotent par clé
     * et par statut. Une modification acceptée passe à {@code REFUNDED}.
     *
     * @return vrai si la commission a été rendue par cet appel.
     */
    @Transactional
    public boolean refundWallet(UUID amendmentId) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH
                || !SETTLEABLE.contains(a.getStatus())
                || a.getCommissionChargedVia() != CommissionChargedVia.WALLET
                || a.getCommissionStatus() != CommissionStatus.CHARGED) {
            return false;
        }
        List<WalletTransactionEntity> lines = walletTransactions.findAllByUserIdAndPaymentRefAndType(
                a.getTravelerId(), WalletCommissionCollector.amendmentPaymentRef(amendmentId),
                WalletTransactionType.AMENDMENT_COMMISSION_DEDUCTED);
        String paymentRef = refundPaymentRef(amendmentId);
        for (WalletTransactionEntity line : lines) {
            BigDecimal amount = line.getAmount().abs();
            if (amount.signum() == 0) {
                continue;
            }
            String key = lines.size() == 1 ? paymentRef : paymentRef + "-" + line.getCurrency();
            walletService.credit(a.getTravelerId(), line.getCurrency(), amount, WalletTransactionType.REFUND,
                    paymentRef, key, a.getBidId());
        }
        if (lines.isEmpty()) {
            // Commission marquée prélevée mais jamais débitée : rien à recréditer, la perte ne doit pas passer inaperçue.
            log.warn("Modification {} : commission prélevée sur le solde sans ligne au grand livre", amendmentId);
            alerts.raiseOnce("AMEND_COMM_NO_LEDGER_" + amendmentId,
                    "Commission de la modification " + amendmentId
                            + " prélevée sur le solde sans ligne au grand livre : rien n'a pu être recrédité",
                    Map.of("amendmentId", amendmentId.toString(), "bidId", a.getBidId().toString(),
                            "travelerId", a.getTravelerId().toString()));
        }
        commissionRefunded(a, Map.of("via", "WALLET"), true);
        return true;
    }

    /** Commission carte à rembourser : modification sans suite, commission {@code CHARGED} par carte. */
    @Transactional(readOnly = true)
    public Optional<CardRefundClaim> cardRefundClaim(UUID amendmentId) {
        return amendments.findById(amendmentId)
                .filter(a -> a.getRail() == BidSupplementRail.CASH
                        && SETTLEABLE.contains(a.getStatus())
                        && a.getCommissionChargedVia() == CommissionChargedVia.CARD
                        && a.getCommissionStatus() == CommissionStatus.CHARGED
                        && a.getCommissionPaymentIntentId() != null)
                .map(a -> new CardRefundClaim(a.getId(), a.getBidId(), a.getTravelerId(),
                        a.getCommissionPaymentIntentId()));
    }

    /**
     * Remboursement Stripe de la commission engagé (créé par nous, ou constaté par {@code charge.refunded}) :
     * {@code CHARGED → REFUNDED}. Sans effet si c'est déjà fait.
     *
     * @param settling vrai quand c'est nous qui rendons la commission d'une modification terminée : une
     *                 modification acceptée passe alors à {@code REFUNDED}. Faux pour un remboursement constaté
     *                 chez Stripe (Dashboard) : le statut de la modification n'est pas décidé par un webhook.
     */
    @Transactional
    public boolean cardRefunded(UUID amendmentId, String refundId, String source, boolean settling) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH
                || a.getCommissionStatus() != CommissionStatus.CHARGED) {
            return false;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("refundId", String.valueOf(refundId));
        extra.put("source", source);
        commissionRefunded(a, extra, settling);
        return true;
    }

    /**
     * Remboursement Stripe de la commission échoué après coup ({@code charge.refund.updated}) :
     * {@code REFUNDED → REFUND_FAILED}, à traiter par un admin (alerte levée par l'appelant).
     *
     * @return vrai si l'état a changé.
     */
    @Transactional
    public boolean commissionRefundFailed(UUID amendmentId, String refundId) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH
                || a.getCommissionStatus() != CommissionStatus.REFUNDED) {
            return false;
        }
        a.setCommissionStatus(CommissionStatus.REFUND_FAILED);
        amendments.save(a);
        audit(a, "BID_AMENDMENT_COMMISSION_REFUND_FAILED", null, Map.of("refundId", String.valueOf(refundId)));
        return true;
    }

    /**
     * Modification acceptée dont il n'y a plus rien à rendre (commission nulle, ou déjà rendue chez Stripe) et
     * dont le colis s'arrête : {@code ACCEPTED → REFUNDED}, pour qu'elle ne compte plus dans les totaux du colis.
     *
     * @return vrai si elle vient de passer à {@code REFUNDED}.
     */
    @Transactional
    public boolean closeAccepted(UUID amendmentId) {
        BidAmendmentEntity a = lock(amendmentId, false).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH
                || a.getStatus() != BidAmendmentStatus.ACCEPTED
                || (a.getCommissionStatus() != null && a.getCommissionStatus() != CommissionStatus.REFUNDED
                && a.getCommissionStatus() != CommissionStatus.REFUND_FAILED)) {
            return false;
        }
        markRefunded(a);
        audit(a, "BID_AMENDMENT_REFUNDED", null, Map.of("commissionRefunded", false));
        return true;
    }

    /** Trace d'un événement sans transition. */
    @Transactional
    public void auditOnly(UUID amendmentId, String action, Map<String, Object> extra) {
        amendments.findById(amendmentId).ifPresent(a -> audit(a, action, null, extra));
    }

    /** {@code payment_ref} (et clé de base) du recrédit de la commission d'une modification. */
    static String refundPaymentRef(UUID amendmentId) {
        return "amendment-refund-" + amendmentId;
    }

    /**
     * Commission rendue : {@code commission_status → REFUNDED}, {@code refundedAt} posé et événement publié.
     * Si {@code settling} et que la modification était acceptée, elle passe à {@code REFUNDED} (une seule
     * entrée d'audit : {@code BID_AMENDMENT_REFUNDED} pour la transition, sinon celle de la commission).
     */
    private void commissionRefunded(BidAmendmentEntity a, Map<String, Object> extra, boolean settling) {
        a.setCommissionStatus(CommissionStatus.REFUNDED);
        a.setRefundedAt(window.now());
        boolean transition = settling && a.getStatus() == BidAmendmentStatus.ACCEPTED;
        if (transition) {
            a.setStatus(BidAmendmentStatus.REFUNDED);
        }
        amendments.save(a);
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("commissionRefunded", true);
        audit(a, transition ? "BID_AMENDMENT_REFUNDED" : "BID_AMENDMENT_COMMISSION_REFUNDED", null, payload);
        events.publishEvent(new BidAmendmentRefundedEvent(a.getId(), a.getBidId(), a.getTravelerId(),
                a.getCommissionAmount(), a.getCurrency(), true));
    }

    private void markRefunded(BidAmendmentEntity a) {
        a.setStatus(BidAmendmentStatus.REFUNDED);
        a.setRefundedAt(window.now());
        amendments.save(a);
    }

    /**
     * Verrous dans l'ordre colis, puis trajet si {@code withTrip}, puis modification (jamais la modification seule :
     * interblocage avec les chemins du colis). Le trajet n'est verrouillé que par la transition qui peut clore la
     * modification et en rendre les kilos. Vide si la modification est introuvable.
     */
    private Optional<BidAmendmentEntity> lock(UUID amendmentId, boolean withTrip) {
        Optional<UUID> bidId = amendments.findBidIdById(amendmentId);
        if (bidId.isEmpty() || bids.lockForUpdate(bidId.get()) == 0) {
            return Optional.empty();
        }
        if (withTrip) {
            bids.findById(bidId.get()).map(BidEntity::getAnnouncementId).flatMap(announcements::findByIdForUpdate);
        }
        return amendments.findByIdAndBidIdForUpdate(amendmentId, bidId.get());
    }

    void audit(BidAmendmentEntity a, String action, UUID actorId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", a.getBidId().toString());
        payload.put("amendmentId", a.getId().toString());
        payload.put("rail", a.getRail().name());
        payload.put("currency", a.getCurrency());
        payload.put("status", a.getStatus().name());
        payload.put("commissionStatus", String.valueOf(a.getCommissionStatus()));
        payload.putAll(extra);
        auditService.log(BidAmendmentService.ENTITY, a.getId(), action, actorId, payload);
    }
}
