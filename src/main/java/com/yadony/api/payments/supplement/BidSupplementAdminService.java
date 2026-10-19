package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.supplement.dto.AdminSupplementDecisionResponse;
import com.yadony.api.payments.supplement.dto.AdminSupplementRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Décision manuelle sur un supplément carte ou mobile money resté payé (décision n° 3 du
 * propriétaire, plan §5.2 point 4 et §10) : le verser au voyageur, ou le rembourser à
 * l'expéditeur. Sert aussi de relance quand un versement ou un remboursement est resté en attente.
 * Les gardes des rails restent en place (retenue du voyageur, litige admin ouvert, compte Connect,
 * charge contestée, versement déjà parti : jamais d'inversion automatique). Chaque décision est
 * journalisée (audit, raison obligatoire) et clôt l'alerte {@code SUPP_UNSETTLED_} quand l'argent
 * est parti. Un supplément espèces n'a rien à verser ni à rembourser : l'argent n'est jamais passé
 * par l'app.
 */
@Service
public class BidSupplementAdminService {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementAdminService.class);

    private static final Set<BidSupplementStatus> PAYABLE = EnumSet.of(BidSupplementStatus.PAID,
            BidSupplementStatus.PAYOUT_PENDING);
    private static final Set<BidSupplementStatus> REFUNDABLE = EnumSet.of(BidSupplementStatus.PAID,
            BidSupplementStatus.REFUND_PENDING);

    private final BidSupplementRepository supplements;
    private final BidSupplementCardPayouts cardPayouts;
    private final BidSupplementCardRefunds cardRefunds;
    private final BidSupplementMobileMoneyPayouts mobileMoneyPayouts;
    private final BidSupplementMobileMoneyRefunds mobileMoneyRefunds;
    private final AuditService auditService;
    private final AdminAlertEscalator alerts;
    private final StorageService storage;

    public BidSupplementAdminService(BidSupplementRepository supplements, BidSupplementCardPayouts cardPayouts,
                                     BidSupplementCardRefunds cardRefunds,
                                     BidSupplementMobileMoneyPayouts mobileMoneyPayouts,
                                     BidSupplementMobileMoneyRefunds mobileMoneyRefunds, AuditService auditService,
                                     AdminAlertEscalator alerts, StorageService storage) {
        this.supplements = supplements;
        this.cardPayouts = cardPayouts;
        this.cardRefunds = cardRefunds;
        this.mobileMoneyPayouts = mobileMoneyPayouts;
        this.mobileMoneyRefunds = mobileMoneyRefunds;
        this.auditService = auditService;
        this.alerts = alerts;
        this.storage = storage;
    }

    /** Suppléments d'un colis, du plus récent au plus ancien. */
    public List<AdminSupplementRow> listForBid(UUID bidId) {
        return supplements.findByBidIdOrderByCreatedAtDesc(bidId).stream().map(this::row).toList();
    }

    /**
     * Verse le supplément au voyageur ({@code PAID}), ou relance un versement resté
     * {@code PAYOUT_PENDING}. Appel du prestataire pendant la requête, hors transaction.
     */
    public AdminSupplementDecisionResponse payout(UUID supplementId, UUID adminId, String reason) {
        BidSupplementEntity s = requireMoneyHeld(supplementId);
        if (!PAYABLE.contains(s.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-not-payable", "Supplement Not Payable",
                    "Ce supplément ne peut pas être versé (statut " + s.getStatus() + ")");
        }
        audit(s, "BID_SUPPLEMENT_ADMIN_PAYOUT_DECIDED", adminId, reason);
        boolean resume = s.getStatus() == BidSupplementStatus.PAYOUT_PENDING;
        boolean acted = s.getRail() == BidSupplementRail.STRIPE
                ? (resume ? cardPayouts.resume(supplementId) : cardPayouts.payout(supplementId))
                : (resume ? mobileMoneyPayouts.resume(supplementId) : mobileMoneyPayouts.payout(supplementId));
        return outcome(supplementId, acted, "versement");
    }

    /**
     * Rembourse le supplément à l'expéditeur ({@code PAID}), ou relance un remboursement resté
     * {@code REFUND_PENDING}. Refusé après un versement : pas d'inversion de Transfer ni de payout.
     */
    public AdminSupplementDecisionResponse refund(UUID supplementId, UUID adminId, String reason) {
        BidSupplementEntity s = requireMoneyHeld(supplementId);
        if (s.getStatus() == BidSupplementStatus.PAYOUT_PENDING || s.getStatus() == BidSupplementStatus.PAID_OUT) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-already-paid-out",
                    "Supplement Already Paid Out",
                    "Ce supplément est déjà versé au voyageur : pas de remboursement automatique, à traiter "
                            + "directement chez le prestataire");
        }
        if (!REFUNDABLE.contains(s.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-not-refundable",
                    "Supplement Not Refundable",
                    "Ce supplément ne peut pas être remboursé (statut " + s.getStatus() + ")");
        }
        audit(s, "BID_SUPPLEMENT_ADMIN_REFUND_DECIDED", adminId, reason);
        boolean acted = s.getRail() == BidSupplementRail.STRIPE
                ? cardRefunds.refund(supplementId)
                : mobileMoneyRefunds.refund(supplementId);
        return outcome(supplementId, acted, "remboursement");
    }

    private AdminSupplementDecisionResponse outcome(UUID supplementId, boolean acted, String label) {
        if (acted) {
            alerts.resolveOpen(BidSupplementReconciliationJob.UNSETTLED_ALERT_PREFIX + supplementId);
            log.info("Supplément {} : {} décidé par l'administration", supplementId, label);
        } else {
            log.warn("Supplément {} : {} décidé par l'administration mais retenu (voir les alertes SUPP_*)",
                    supplementId, label);
        }
        return new AdminSupplementDecisionResponse(acted, row(require(supplementId)));
    }

    private BidSupplementEntity requireMoneyHeld(UUID supplementId) {
        BidSupplementEntity s = require(supplementId);
        if (s.getRail() == BidSupplementRail.CASH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-cash-no-money-held",
                    "Cash Supplement",
                    "Supplément en espèces : l'argent n'est jamais passé par Yadony, rien à verser ni à rembourser");
        }
        return s;
    }

    private BidSupplementEntity require(UUID supplementId) {
        return supplements.findById(supplementId).orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "supplement-not-found", "Not Found", "Supplément introuvable"));
    }

    private void audit(BidSupplementEntity s, String action, UUID adminId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", s.getBidId().toString());
        payload.put("rail", s.getRail().name());
        payload.put("currency", s.getCurrency());
        payload.put("net", s.getNetAmount());
        payload.put("commission", s.getCommissionAmount());
        payload.put("gross", s.getGrossAmount());
        payload.put("status", s.getStatus().name());
        payload.put("adminReason", reason);
        auditService.log(BidSupplementService.ENTITY, s.getId(), action, adminId, payload);
    }

    private AdminSupplementRow row(BidSupplementEntity s) {
        return new AdminSupplementRow(s.getId(), s.getBidId(), s.getTravelerId(), s.getSenderId(), s.getRail().name(),
                s.getStatus().name(), s.getCurrency(), s.getNetAmount(), s.getCommissionAmount(), s.getGrossAmount(),
                s.getReasonCode() == null ? null : s.getReasonCode().name(), s.getClosedReason(),
                s.getCommissionStatus() == null ? null : s.getCommissionStatus().name(),
                s.getCommissionChargedVia() == null ? null : s.getCommissionChargedVia().name(),
                s.getPhotoPurgedAt() != null || s.getPhotoKey() == null ? null
                        : storage.generatePresignedUrl(s.getPhotoKey(), BidSupplementViews.PHOTO_URL_TTL),
                utc(s.getCreatedAt()), utc(s.getExpiresAt()), utc(s.getPaidAt()), utc(s.getPaidOutAt()),
                utc(s.getRefundedAt()), s.getStripePaymentIntentId(), s.getStripeChargeId(), s.getStripeTransferId(),
                s.getStripeRefundId(), s.getCommissionPaymentIntentId());
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
