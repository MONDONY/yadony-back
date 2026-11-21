package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import com.yadony.api.payments.fee.CancellationFeeRatePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Réponse du paquet {@code cancellation} au port {@link CancellationFeeRatePort} du paquet
 * {@code payments} : taux de la retenue encore due, puis règlement de cette retenue. Le paquet
 * {@code cancellation} ne connaît que la décision (ligne {@code DUE}, {@link SenderCancellationFeeRecorder}),
 * jamais les montants, qu'il reçoit tout calculés.
 */
@Component
public class CancellationFeeRatePortAdapter implements CancellationFeeRatePort {

    static final String AUDIT_FEE_RETAINED = "CANCELLATION_FEE_RETAINED";

    private final CancellationFeeRepository fees;
    private final AuditService auditService;

    public CancellationFeeRatePortAdapter(CancellationFeeRepository fees, AuditService auditService) {
        this.fees = fees;
        this.auditService = auditService;
    }

    @Override
    public Optional<BigDecimal> dueRate(UUID bidId) {
        return fees.findByBidId(bidId)
                .filter(fee -> fee.getStatus() == CancellationFeeStatus.DUE)
                .map(CancellationFeeEntity::getRate);
    }

    /**
     * Sous le verrou de la ligne : deux règlements concurrents du même colis se sérialisent, le second
     * trouve la ligne déjà {@code RETAINED} et ne fait rien (ni écriture, ni second audit).
     */
    @Override
    @Transactional
    public void markRetained(UUID bidId, CancellationFeeBreakdown breakdown, String currency) {
        Objects.requireNonNull(breakdown, "breakdown");
        Objects.requireNonNull(currency, "currency");
        fees.findByBidIdForUpdate(bidId)
                .filter(fee -> fee.getStatus() == CancellationFeeStatus.DUE)
                .ifPresent(fee -> {
                    fee.setGrossFee(breakdown.grossFee());
                    fee.setRefundFees(breakdown.refundFees());
                    fee.setCommission(breakdown.commission());
                    fee.setTravelerNet(breakdown.travelerNet());
                    fee.setCurrency(currency);
                    fee.setStatus(CancellationFeeStatus.RETAINED);
                    fees.save(fee);
                    auditService.log("BID", bidId, AUDIT_FEE_RETAINED, null, Map.of(
                            "cancellationFeeId", String.valueOf(fee.getId()),
                            "grossFee", breakdown.grossFee().toPlainString(),
                            "refundFees", breakdown.refundFees().toPlainString(),
                            "commission", breakdown.commission().toPlainString(),
                            "travelerNet", breakdown.travelerNet().toPlainString(),
                            "senderRefund", breakdown.senderRefund().toPlainString(),
                            "currency", currency));
                });
    }
}
