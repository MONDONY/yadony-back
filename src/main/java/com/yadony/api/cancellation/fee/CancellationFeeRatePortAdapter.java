package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import com.yadony.api.payments.fee.CancellationFeeRatePort;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Réponse du paquet {@code cancellation} au port {@link CancellationFeeRatePort} du paquet
 * {@code payments} : taux de la retenue encore due et plafond du barème, puis règlement de cette retenue
 * (ou sa clôture quand le remboursement n'a pas pu la porter). Le paquet
 * {@code cancellation} ne connaît que la décision (ligne {@code DUE}, {@link SenderCancellationFeeRecorder}),
 * jamais les montants, qu'il reçoit tout calculés.
 */
@Component
public class CancellationFeeRatePortAdapter implements CancellationFeeRatePort {

    static final String AUDIT_FEE_RETAINED = "CANCELLATION_FEE_RETAINED";
    static final String AUDIT_FEE_NOT_APPLICABLE = "CANCELLATION_FEE_NOT_APPLICABLE";

    private final CancellationFeeRepository fees;
    private final AuditService auditService;
    private final CancellationFeeProperties properties;
    private final EntityManager entityManager;

    public CancellationFeeRatePortAdapter(CancellationFeeRepository fees, AuditService auditService,
                                          CancellationFeeProperties properties, EntityManager entityManager) {
        this.fees = fees;
        this.auditService = auditService;
        this.properties = properties;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<BigDecimal> dueRate(UUID bidId) {
        return fees.findByBidId(bidId)
                .filter(fee -> fee.getStatus() == CancellationFeeStatus.DUE)
                .map(CancellationFeeEntity::getRate);
    }

    /**
     * Taux dû lu SOUS LE VERROU de la ligne, tenu jusqu'à la fin de la transaction de l'appelant (celle du claim du
     * remboursement) : une renonciation de l'administration ({@code CancellationFeeAdminService#waive}) attend la fin
     * du règlement, ou le règlement attend la fin de la renonciation et voit alors la ligne close.
     *
     * <p>Lecture simple d'abord : sans retenue due, aucun verrou (le remboursement ordinaire n'en prend pas de plus,
     * et le verrou JPQL ne s'exécute jamais sous H2). Une ligne close ne redevient jamais due, ce premier tri ne perd
     * donc aucun cas. Ordre des verrous du remboursement : paiement (claim) puis retenue.
     */
    @Override
    @Transactional
    public Optional<BigDecimal> dueRateForUpdate(UUID bidId) {
        if (dueRate(bidId).isEmpty()) {
            return Optional.empty();
        }
        return lockedFresh(bidId)
                .filter(fee -> fee.getStatus() == CancellationFeeStatus.DUE)
                .map(CancellationFeeEntity::getRate);
    }

    /**
     * La ligne du colis, verrouillée et RELUE : une lecture simple antérieure dans la même transaction a pu la mettre
     * en cache avec un état périmé (DUE), que la requête verrouillée rendrait tel quel. Sans {@code @Version} sur
     * l'entité, écrire sur cet état périmé écraserait une renonciation commitée pendant l'attente du verrou.
     */
    private Optional<CancellationFeeEntity> lockedFresh(UUID bidId) {
        Optional<CancellationFeeEntity> fee = fees.findByBidIdForUpdate(bidId);
        fee.ifPresent(entityManager::refresh);
        return fee;
    }

    @Override
    public BigDecimal capEur() {
        return properties.capEur();
    }

    /**
     * Même verrou que {@link #markRetained} : la retenue est réglée ou close, jamais les deux. Les montants
     * restent à zéro, rien n'ayant été retenu.
     */
    @Override
    @Transactional
    public void markNotApplicable(UUID bidId, String reason) {
        lockedFresh(bidId)
                .filter(fee -> fee.getStatus() == CancellationFeeStatus.DUE)
                .ifPresent(fee -> {
                    fee.setStatus(CancellationFeeStatus.NOT_APPLICABLE);
                    fees.save(fee);
                    auditService.log("BID", bidId, AUDIT_FEE_NOT_APPLICABLE, null, Map.of(
                            "cancellationFeeId", String.valueOf(fee.getId()),
                            "reason", String.valueOf(reason)));
                });
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
        lockedFresh(bidId)
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
