package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.events.CancellationFeePaidOutEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.CancellationFeePayoutPort;
import com.yadony.api.payments.fee.PayableCancellationFee;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Réponse du paquet {@code cancellation} au port {@link CancellationFeePayoutPort} du paquet {@code payments} :
 * lecture de la retenue à verser et ses deux transitions de versement, chacune sous le verrou de la ligne
 * (même verrou que {@link CancellationFeeRatePortAdapter#markRetained}). Les montants ne changent plus ici : ils
 * ont été figés au règlement ({@code RETAINED}).
 */
@Component
public class CancellationFeePayoutPortAdapter implements CancellationFeePayoutPort {

    static final String AUDIT_FEE_PAID_OUT = "CANCELLATION_FEE_PAID_OUT";
    static final String AUDIT_FEE_PAYOUT_PENDING = "CANCELLATION_FEE_PAYOUT_PENDING";
    /** Note d'audit d'un dédommagement nul (petit colis) : versé sans aucun mouvement d'argent. */
    static final String NOTE_NOTHING_TO_TRANSFER = "NOTHING_TO_TRANSFER";

    private final CancellationFeeRepository fees;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public CancellationFeePayoutPortAdapter(CancellationFeeRepository fees, AuditService auditService,
                                            ApplicationEventPublisher events) {
        this.fees = fees;
        this.auditService = auditService;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableCancellationFee> findPayable(UUID feeId) {
        return fees.findActiveById(feeId).flatMap(CancellationFeePayoutPortAdapter::payable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableCancellationFee> findPayableByBid(UUID bidId) {
        return fees.findByBidId(bidId).flatMap(CancellationFeePayoutPortAdapter::payable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findPayoutRetries(Instant retainedBefore) {
        return fees.findPayoutRetryIds(CancellationFeeStatus.PAYOUT_PENDING, CancellationFeeStatus.RETAINED,
                LocalDateTime.ofInstant(retainedBefore, ZoneOffset.UTC));
    }

    /**
     * Sous le verrou de la ligne : un second appel (rejeu, essai concurrent) trouve la ligne déjà versée et ne
     * change rien, pas même la référence du premier versement. Refuse de marquer « versé » sans référence une
     * ligne qui porte un net à verser. Au passage, publie {@link CancellationFeePaidOutEvent}, écouté après commit
     * pour prévenir le voyageur.
     */
    @Override
    @Transactional
    public boolean markPaidOut(UUID feeId, String payoutRef) {
        CancellationFeeEntity fee = fees.findByIdForUpdate(feeId).orElse(null);
        if (fee == null) {
            return false;
        }
        if (fee.getStatus() == CancellationFeeStatus.PAID_OUT) {
            return true;
        }
        if (!isPayable(fee.getStatus())) {
            return false;
        }
        if (payoutRef == null && fee.getTravelerNet().signum() > 0) {
            throw new IllegalArgumentException("Dédommagement " + feeId + " de " + fee.getTravelerNet().toPlainString()
                    + " " + fee.getCurrency() + " : versement sans référence refusé");
        }
        fee.setStatus(CancellationFeeStatus.PAID_OUT);
        fee.setPayoutRef(payoutRef);
        fees.save(fee);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationFeeId", String.valueOf(fee.getId()));
        payload.put("payoutRef", payoutRef == null ? "" : payoutRef);
        payload.put("travelerId", String.valueOf(fee.getTravelerId()));
        payload.put("travelerNet", fee.getTravelerNet().toPlainString());
        payload.put("currency", fee.getCurrency());
        if (payoutRef == null) {
            payload.put("note", NOTE_NOTHING_TO_TRANSFER);
        }
        auditService.log("BID", fee.getBidId(), AUDIT_FEE_PAID_OUT, null, payload);
        // Au seul passage vers PAID_OUT : un rejeu (webhook, issue pawaPay, planificateur) trouve la ligne versée plus
        // haut et ne prévient jamais le voyageur deux fois.
        events.publishEvent(new CancellationFeePaidOutEvent(fee.getId(), fee.getBidId(), fee.getTravelerId(),
                fee.getTravelerNet(), fee.getCurrency()));
        return true;
    }

    /**
     * Sous le verrou de la ligne. Une ligne {@code RETAINED} passe en attente avec son motif (un audit). Une ligne déjà
     * en attente garde le même motif sans rien écrire à chaque essai horaire ; un motif DIFFÉRENT (l'arrêt des essais
     * automatiques, une autre cause) est enregistré et audité avec le précédent. Une ligne versée n'est jamais
     * rétrogradée.
     */
    @Override
    @Transactional
    public boolean markPayoutPending(UUID feeId, String reason) {
        CancellationFeeEntity fee = fees.findByIdForUpdate(feeId).orElse(null);
        if (fee == null) {
            return false;
        }
        String bounded = bounded(reason);
        if (fee.getStatus() == CancellationFeeStatus.PAYOUT_PENDING) {
            if (Objects.equals(fee.getPayoutPendingReason(), bounded)) {
                return true;
            }
            String previous = fee.getPayoutPendingReason();
            fee.setPayoutPendingReason(bounded);
            fees.save(fee);
            auditPending(fee, bounded, previous);
            return true;
        }
        if (fee.getStatus() != CancellationFeeStatus.RETAINED) {
            return false;
        }
        fee.setStatus(CancellationFeeStatus.PAYOUT_PENDING);
        fee.setPayoutPendingReason(bounded);
        fees.save(fee);
        auditPending(fee, bounded, null);
        return true;
    }

    private void auditPending(CancellationFeeEntity fee, String reason, String previousReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationFeeId", String.valueOf(fee.getId()));
        payload.put("reason", String.valueOf(reason));
        if (previousReason != null) {
            payload.put("previousReason", previousReason);
        }
        payload.put("travelerId", String.valueOf(fee.getTravelerId()));
        payload.put("travelerNet", fee.getTravelerNet().toPlainString());
        payload.put("currency", fee.getCurrency());
        auditService.log("BID", fee.getBidId(), AUDIT_FEE_PAYOUT_PENDING, null, payload);
    }

    private static String bounded(String reason) {
        if (reason == null || reason.length() <= CancellationFeeEntity.PAYOUT_PENDING_REASON_MAX) {
            return reason;
        }
        return reason.substring(0, CancellationFeeEntity.PAYOUT_PENDING_REASON_MAX);
    }

    private static boolean isPayable(CancellationFeeStatus status) {
        return status == CancellationFeeStatus.RETAINED || status == CancellationFeeStatus.PAYOUT_PENDING;
    }

    private static Optional<PayableCancellationFee> payable(CancellationFeeEntity fee) {
        PayableCancellationFee.Status status = switch (fee.getStatus()) {
            case RETAINED -> PayableCancellationFee.Status.RETAINED;
            case PAYOUT_PENDING -> PayableCancellationFee.Status.PAYOUT_PENDING;
            default -> null;
        };
        if (status == null) {
            return Optional.empty();
        }
        return Optional.of(new PayableCancellationFee(fee.getId(), fee.getBidId(), fee.getTravelerId(),
                fee.getTravelerNet(), fee.getCurrency(), status,
                fee.getPayoutRearmedAt() == null ? null : fee.getPayoutRearmedAt().toInstant(ZoneOffset.UTC)));
    }
}
