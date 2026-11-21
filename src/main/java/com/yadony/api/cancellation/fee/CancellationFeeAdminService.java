package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.dto.AdminCancellationFeeResponse;
import com.yadony.api.cancellation.events.CancellationFeePayoutRetryRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gestes de l'administration sur une retenue d'annulation ({@link CancellationFeeAdminController}). Chacun sous le
 * verrou de la ligne (le même que le règlement et le versement), audité avec l'administrateur pour acteur. Aucun ne
 * déplace d'argent.
 *
 * <ul>
 *   <li>{@link #waive} : une retenue encore {@code DUE} passe {@code NOT_APPLICABLE}, motif {@value #ADMIN_WAIVED}.
 *       Le remboursement suivant de l'expéditeur, par les chemins existants, n'en trouve plus : il est intégral, sur
 *       le rail carte comme sur le rail mobile money. Une retenue déjà réglée ne se renonce plus (409) : l'argent
 *       retenu est alors promis au voyageur.</li>
 *   <li>{@link #retryPayout} : un versement {@code PAYOUT_PENDING} est réarmé ({@code payout_rearmed_at}, le plafond
 *       d'essais mobile money repart de cette date), puis tenté aussitôt après le commit par le paquet
 *       {@code payments} ({@link CancellationFeePayoutRetryRequestedEvent}), avec toutes ses gardes.</li>
 * </ul>
 */
@Service
public class CancellationFeeAdminService {

    /** Motif de clôture d'une retenue à laquelle l'administration renonce. */
    static final String ADMIN_WAIVED = "ADMIN_WAIVED";
    static final String AUDIT_FEE_PAYOUT_RETRY_REQUESTED = "CANCELLATION_FEE_PAYOUT_RETRY_REQUESTED";

    private final CancellationFeeRepository fees;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SenderRefundInFlightPort senderRefunds;

    public CancellationFeeAdminService(CancellationFeeRepository fees, AuditService auditService,
                                       ApplicationEventPublisher events, Clock clock,
                                       SenderRefundInFlightPort senderRefunds) {
        this.fees = fees;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
        this.senderRefunds = senderRefunds;
    }

    /**
     * Renonce à la retenue du colis {@code bidId}, encore due. 404 sans retenue, 409 si elle n'est plus due, 409
     * {@code sender-refund-in-flight} si un remboursement de l'expéditeur est déjà vivant chez pawaPay. Sous le verrou
     * de la retenue : un règlement en cours (qui le tient depuis sa lecture du taux) termine d'abord, la renonciation
     * trouve alors la retenue réglée et répond 409.
     *
     * @param reason motif interne de l'administrateur, repris dans l'audit
     */
    @Transactional
    public AdminCancellationFeeResponse waive(UUID bidId, UUID adminId, String reason) {
        CancellationFeeEntity fee = fees.findByBidIdForUpdate(bidId).orElseThrow(() -> notFound(
                "Aucune retenue d'annulation pour le colis " + bidId));
        if (fee.getStatus() != CancellationFeeStatus.DUE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "cancellation-fee-not-due",
                    "Cancellation Fee Not Due", "La retenue du colis " + bidId + " est " + fee.getStatus().name()
                            + " : on ne renonce qu'à une retenue encore due, avant le remboursement de l'expéditeur");
        }
        // Sous le verrou de la retenue, lectures simples côté paiements (jamais le verrou du paiement : le remboursement
        // prend paiement puis retenue, la renonciation la retenue seule, aucun cycle).
        if (senderRefunds.senderRefundInFlight(bidId)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "sender-refund-in-flight",
                    "Sender Refund In Flight", "Un remboursement de l'expéditeur est déjà en cours ou abouti chez "
                            + "pawaPay pour le colis " + bidId + " (le dépôt moins la retenue). Renoncer maintenant le "
                            + "laisserait remboursé d'une partie seulement, sans dédommagement pour le voyageur. Attendre "
                            + "l'issue de ce remboursement : s'il échoue, la renonciation redevient possible.");
        }
        fee.setStatus(CancellationFeeStatus.NOT_APPLICABLE);
        fees.save(fee);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationFeeId", String.valueOf(fee.getId()));
        payload.put("reason", ADMIN_WAIVED);
        payload.put("adminReason", reason);
        auditService.log("BID", bidId, CancellationFeeRatePortAdapter.AUDIT_FEE_NOT_APPLICABLE, adminId, payload);
        return view(fee);
    }

    /**
     * Réarme le versement en attente de la retenue {@code feeId} et demande un essai immédiat, après le commit.
     * 404 sans retenue, 409 si elle n'est pas {@code PAYOUT_PENDING}.
     *
     * @param reason motif interne de l'administrateur, repris dans l'audit
     */
    @Transactional
    public void retryPayout(UUID feeId, UUID adminId, String reason) {
        CancellationFeeEntity fee = fees.findByIdForUpdate(feeId).orElseThrow(() -> notFound(
                "Retenue d'annulation " + feeId + " introuvable"));
        if (fee.getStatus() != CancellationFeeStatus.PAYOUT_PENDING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "cancellation-fee-not-pending",
                    "Cancellation Fee Payout Not Pending", "Le dédommagement " + feeId + " est "
                            + fee.getStatus().name() + " : seul un versement en attente se relance");
        }
        fee.setPayoutRearmedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        fees.save(fee);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationFeeId", String.valueOf(fee.getId()));
        payload.put("adminReason", reason);
        payload.put("previousReason", String.valueOf(fee.getPayoutPendingReason()));
        auditService.log("BID", fee.getBidId(), AUDIT_FEE_PAYOUT_RETRY_REQUESTED, adminId, payload);
        events.publishEvent(new CancellationFeePayoutRetryRequestedEvent(feeId, adminId));
    }

    /** État courant de la retenue {@code feeId} (404 si absente). */
    @Transactional(readOnly = true)
    public AdminCancellationFeeResponse describe(UUID feeId) {
        return fees.findActiveById(feeId).map(CancellationFeeAdminService::view)
                .orElseThrow(() -> notFound("Retenue d'annulation " + feeId + " introuvable"));
    }

    private static AdminCancellationFeeResponse view(CancellationFeeEntity fee) {
        return new AdminCancellationFeeResponse(fee.getId(), fee.getBidId(), fee.getStatus().name(),
                fee.getTravelerNet(), fee.getCurrency(), fee.getPayoutRef(), fee.getPayoutPendingReason());
    }

    private static YadonyBusinessException notFound(String detail) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "cancellation-fee-not-found", "Not Found", detail);
    }
}
