package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.WalletCommissionCollector;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * États de la commission d'un supplément espèces (plan §4.4), prélevée AU VOYAGEUR à sa demande :
 * chaque méthode publique est une transaction courte, sous le verrou de la ligne (et du colis
 * quand l'annonce à l'expéditeur en dépend), sans aucun appel Stripe. Les appels réseau vivent
 * dans {@link BidSupplementCashRail} et {@link BidSupplementCashRefunds}, entre deux de ces
 * transactions. Toute transition relit l'état sous verrou : un rejeu (webhook, confirmation de
 * l'app, filet) ne produit aucun second effet.
 *
 * <p>La commission principale du colis ({@code bids.commission_*}) n'est JAMAIS touchée ici.
 */
@Component
public class BidSupplementCashLedger {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCashLedger.class);

    /** Carte commission refusée, ou 3DS échouée : la demande n'a jamais atteint l'expéditeur. */
    public static final String COMMISSION_FAILED = "COMMISSION_FAILED";

    /** 3DS de la commission jamais confirmée (filet, ou échéance) : jamais vue de l'expéditeur. */
    public static final String COMMISSION_NOT_CONFIRMED = "COMMISSION_NOT_CONFIRMED";

    /** Demande close (ou supplément espèces terminé) : sa commission prélevée est à rendre. */
    static final Set<BidSupplementStatus> SETTLEABLE = EnumSet.of(BidSupplementStatus.DECLINED,
            BidSupplementStatus.EXPIRED, BidSupplementStatus.WITHDRAWN, BidSupplementStatus.CANCELLED,
            BidSupplementStatus.REFUNDED);

    /** Issue de l'enregistrement d'une commission prélevée par carte. */
    public enum ChargeOutcome {
        /** Demande en attente de sa commission : désormais proposée à l'expéditeur. */
        PROPOSED,
        /** Commission arrivée sur une demande déjà close : elle sera rendue. */
        LATE_REFUND,
        /** Déjà enregistrée (rejeu) : aucun effet. */
        ALREADY,
        /** Supplément introuvable, d'un autre rail, ou PaymentIntent qui n'est pas le sien. */
        IGNORED
    }

    /** Ce qu'il faut pour rembourser la commission par carte, hors transaction. */
    public record CardRefundClaim(UUID supplementId, UUID bidId, UUID travelerId, String paymentIntentId) {
    }

    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactions;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final BidSupplementWindow window;

    public BidSupplementCashLedger(BidSupplementRepository supplements, BidRepository bids,
                                   AnnouncementRepository announcements, WalletService walletService,
                                   WalletTransactionRepository walletTransactions, AuditService auditService,
                                   ApplicationEventPublisher events, BidSupplementWindow window) {
        this.supplements = supplements;
        this.bids = bids;
        this.announcements = announcements;
        this.walletService = walletService;
        this.walletTransactions = walletTransactions;
        this.auditService = auditService;
        this.events = events;
        this.window = window;
    }

    /** Lecture fraîche, sans verrou. */
    @Transactional(readOnly = true)
    public Optional<BidSupplementEntity> find(UUID supplementId) {
        return supplements.findById(supplementId);
    }

    /**
     * Acceptation espèces par l'expéditeur (sous les verrous pris par
     * {@code BidSupplementService.accept}) : il s'engage à remettre le complément en main propre.
     * {@code PROPOSED → PAID}, sans aucun versement : l'argent ne passe pas par l'app.
     */
    public void acceptCash(BidSupplementEntity s) {
        if (s.getStatus() != BidSupplementStatus.PROPOSED) {
            return;
        }
        s.setStatus(BidSupplementStatus.PAID);
        s.setPaidAt(window.now());
        s.setRespondedAt(window.now());
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_CASH_ACCEPTED", s.getSenderId(), Map.of());
        events.publishEvent(new BidSupplementPaidEvent(s.getId(), s.getBidId(), s.getSenderId(), s.getTravelerId(),
                s.getRail(), s.getGrossAmount(), s.getCurrency()));
    }

    /** PaymentIntent de commission en attente de la 3DS du voyageur. */
    @Transactional
    public BidSupplementEntity commissionRequiresAction(UUID supplementId, String paymentIntentId) {
        BidSupplementEntity s = lock(supplementId).orElseThrow();
        if (s.getCommissionPaymentIntentId() == null) {
            s.setCommissionPaymentIntentId(paymentIntentId);
        }
        if (s.getStatus() == BidSupplementStatus.COMMISSION_PENDING && s.getCommissionStatus() == null) {
            s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
            audit(s, "BID_SUPPLEMENT_COMMISSION_3DS_REQUIRED", s.getTravelerId(), Map.of("piId", paymentIntentId));
        }
        supplements.save(s);
        return s;
    }

    /**
     * Commission prélevée sur la carte (création, confirmation après 3DS, webhook, filet).
     * Demande en attente : {@code COMMISSION_PENDING → PROPOSED}, l'expéditeur est prévenu. Demande
     * close entre-temps (retrait, échéance, colis terminé) : la commission est rendue après le
     * commit. Rejeu : aucun effet.
     */
    @Transactional
    public ChargeOutcome commissionCharged(UUID supplementId, String paymentIntentId, String source) {
        Optional<UUID> bidId = supplements.findBidIdById(supplementId);
        if (bidId.isEmpty()) {
            log.warn("Commission {} d'un supplément inconnu {} ({})", paymentIntentId, supplementId, source);
            return ChargeOutcome.IGNORED;
        }
        // Colis puis demande : l'annonce à l'expéditeur se sérialise avec le scan et la fin du colis.
        bids.lockForUpdate(bidId.get());
        BidSupplementEntity s = supplements.findByIdAndBidIdForUpdate(supplementId, bidId.get()).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH) {
            return ChargeOutcome.IGNORED;
        }
        if (s.getCommissionPaymentIntentId() != null && paymentIntentId != null
                && !s.getCommissionPaymentIntentId().equals(paymentIntentId)) {
            log.error("Supplément {} : commission {} réussie alors que le supplément porte {}", supplementId,
                    paymentIntentId, s.getCommissionPaymentIntentId());
            return ChargeOutcome.IGNORED;
        }
        if (s.getCommissionStatus() == CommissionStatus.CHARGED || s.getCommissionStatus() == CommissionStatus.REFUNDED
                || s.getCommissionStatus() == CommissionStatus.REFUND_FAILED) {
            return ChargeOutcome.ALREADY;
        }
        if (s.getCommissionPaymentIntentId() == null) {
            // Le webhook peut arriver avant que la création n'ait enregistré le PaymentIntent.
            s.setCommissionPaymentIntentId(paymentIntentId);
        }
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("piId", String.valueOf(paymentIntentId));
        extra.put("source", source);
        if (s.getStatus() == BidSupplementStatus.COMMISSION_PENDING) {
            s.setStatus(BidSupplementStatus.PROPOSED);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_COMMISSION_CHARGED_CARD", s.getTravelerId(), extra);
            publishRequested(s);
            return ChargeOutcome.PROPOSED;
        }
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_LATE_COMMISSION_REFUND", null, extra);
        events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), s.getBidId(), s.getRail(),
                s.getStatus(), "LATE_COMMISSION"));
        return ChargeOutcome.LATE_REFUND;
    }

    /**
     * Commission non prélevée : carte refusée, 3DS échouée ou abandonnée, PaymentIntent annulé.
     * Demande en attente : {@code CANCELLED} ({@link #COMMISSION_FAILED}), rien n'a été prélevé et
     * l'expéditeur n'a rien vu. Une commission déjà prélevée n'est jamais écrasée.
     *
     * @return vrai si l'état a changé.
     */
    @Transactional
    public boolean commissionFailed(UUID supplementId, String paymentIntentId, String reason) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH) {
            return false;
        }
        CommissionStatus current = s.getCommissionStatus();
        if (current == CommissionStatus.CHARGED || current == CommissionStatus.REFUNDED
                || current == CommissionStatus.REFUND_FAILED || current == CommissionStatus.FAILED) {
            return false;
        }
        if (s.getCommissionPaymentIntentId() == null && paymentIntentId != null) {
            s.setCommissionPaymentIntentId(paymentIntentId);
        }
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.FAILED);
        if (s.getStatus() == BidSupplementStatus.COMMISSION_PENDING) {
            s.setStatus(BidSupplementStatus.CANCELLED);
            s.setRespondedAt(window.now());
            s.setClosedReason(COMMISSION_FAILED);
        }
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_COMMISSION_FAILED", s.getTravelerId(), Map.of("reason", String.valueOf(reason)));
        return true;
    }

    /**
     * Recrédite au voyageur la commission prélevée sur son solde, ligne par ligne (devise du
     * colis, puis complément converti), en {@code REFUND} avec
     * {@code payment_ref = "supplement-refund-<id>"} : hors du préfixe {@code refund-} de la
     * commission principale (INV-09 {@code COMMISSION_RECREDIT_DOUBLE}). Idempotent par clé et
     * par statut.
     *
     * @return vrai si la commission a été rendue par cet appel.
     */
    @Transactional
    public boolean refundWallet(UUID supplementId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH || !SETTLEABLE.contains(s.getStatus())
                || s.getCommissionChargedVia() != CommissionChargedVia.WALLET
                || s.getCommissionStatus() != CommissionStatus.CHARGED) {
            return false;
        }
        List<WalletTransactionEntity> lines = walletTransactions.findAllByUserIdAndPaymentRefAndType(
                s.getTravelerId(), WalletCommissionCollector.supplementPaymentRef(supplementId),
                WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED);
        String paymentRef = refundPaymentRef(supplementId);
        Map<String, Object> extra = new LinkedHashMap<>();
        for (WalletTransactionEntity line : lines) {
            BigDecimal amount = line.getAmount().abs();
            if (amount.signum() == 0) {
                continue;
            }
            String key = lines.size() == 1 ? paymentRef : paymentRef + "-" + line.getCurrency();
            walletService.credit(s.getTravelerId(), line.getCurrency(), amount, WalletTransactionType.REFUND,
                    paymentRef, key, s.getBidId());
            extra.put("amount-" + line.getCurrency(), amount.toPlainString());
        }
        if (lines.isEmpty()) {
            log.warn("Supplément {} : commission prélevée sur le solde sans ligne au grand livre", supplementId);
        }
        s.setCommissionStatus(CommissionStatus.REFUNDED);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_COMMISSION_REFUNDED", null, extra);
        events.publishEvent(new BidSupplementRefundedEvent(s.getId(), s.getBidId(), s.getTravelerId(),
                s.getCommissionAmount(), s.getCurrency(), true));
        return true;
    }

    /** Commission carte à rembourser : demande close, commission {@code CHARGED} par carte. */
    @Transactional(readOnly = true)
    public Optional<CardRefundClaim> cardRefundClaim(UUID supplementId) {
        return supplements.findById(supplementId)
                .filter(s -> s.getRail() == BidSupplementRail.CASH && SETTLEABLE.contains(s.getStatus())
                        && s.getCommissionChargedVia() == CommissionChargedVia.CARD
                        && s.getCommissionStatus() == CommissionStatus.CHARGED
                        && s.getCommissionPaymentIntentId() != null)
                .map(s -> new CardRefundClaim(s.getId(), s.getBidId(), s.getTravelerId(),
                        s.getCommissionPaymentIntentId()));
    }

    /**
     * Remboursement Stripe de la commission engagé (créé par nous, ou constaté par
     * {@code charge.refunded}) : {@code CHARGED → REFUNDED}. Sans effet si c'est déjà fait.
     */
    @Transactional
    public boolean cardRefunded(UUID supplementId, String refundId, String source) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH
                || s.getCommissionStatus() != CommissionStatus.CHARGED) {
            return false;
        }
        s.setCommissionStatus(CommissionStatus.REFUNDED);
        supplements.save(s);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("refundId", String.valueOf(refundId));
        extra.put("source", source);
        audit(s, "BID_SUPPLEMENT_COMMISSION_REFUNDED", null, extra);
        events.publishEvent(new BidSupplementRefundedEvent(s.getId(), s.getBidId(), s.getTravelerId(),
                s.getCommissionAmount(), s.getCurrency(), true));
        return true;
    }

    /**
     * Remboursement Stripe de la commission échoué après coup ({@code charge.refund.updated}) :
     * {@code REFUNDED → REFUND_FAILED}, à traiter par un admin (alerte levée par l'appelant).
     *
     * @return vrai si l'état a changé.
     */
    @Transactional
    public boolean commissionRefundFailed(UUID supplementId, String refundId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH
                || s.getCommissionStatus() != CommissionStatus.REFUNDED) {
            return false;
        }
        s.setCommissionStatus(CommissionStatus.REFUND_FAILED);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_COMMISSION_REFUND_FAILED", null, Map.of("refundId", String.valueOf(refundId)));
        return true;
    }

    /**
     * Tentative du filet sans issue (Stripe en échec, PaymentIntent pas encore tranchée) : la demande
     * repasse en fin de file ({@code ORDER BY updated_at}), pour qu'une centaine d'échecs ne bloque
     * pas indéfiniment les suivantes.
     */
    @Transactional
    public void touch(UUID supplementId) {
        supplements.touch(supplementId, window.now());
    }

    /** Trace d'un événement sans transition. */
    @Transactional
    public void auditOnly(UUID supplementId, String action, Map<String, Object> extra) {
        supplements.findById(supplementId).ifPresent(s -> audit(s, action, null, extra));
    }

    /** {@code payment_ref} (et clé de base) du recrédit de la commission d'un supplément. */
    static String refundPaymentRef(UUID supplementId) {
        return "supplement-refund-" + supplementId;
    }

    /** La demande atteint enfin l'expéditeur : même annonce que la création ({@code BidSupplementService}). */
    private void publishRequested(BidSupplementEntity s) {
        BidEntity bid = bids.findById(s.getBidId()).orElse(null);
        String timezone = bid == null ? null : announcements.findById(bid.getAnnouncementId())
                .map(com.yadony.api.matching.AnnouncementEntity::getTimezone).orElse(null);
        events.publishEvent(new BidSupplementRequestedEvent(s.getId(), s.getBidId(), s.getSenderId(),
                s.getTravelerId(), s.getGrossAmount(), s.getCurrency(), s.getExpiresAt(), timezone));
    }

    private Optional<BidSupplementEntity> lock(UUID supplementId) {
        return supplements.findBidIdById(supplementId)
                .flatMap(bidId -> supplements.findByIdAndBidIdForUpdate(supplementId, bidId));
    }

    void audit(BidSupplementEntity s, String action, UUID actorId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", s.getBidId().toString());
        payload.put("rail", s.getRail().name());
        payload.put("currency", s.getCurrency());
        payload.put("net", s.getNetAmount());
        payload.put("commission", s.getCommissionAmount());
        payload.put("gross", s.getGrossAmount());
        payload.put("status", s.getStatus().name());
        payload.put("commissionStatus", String.valueOf(s.getCommissionStatus()));
        payload.putAll(extra);
        auditService.log(BidSupplementService.ENTITY, s.getId(), action, actorId, payload);
    }
}
