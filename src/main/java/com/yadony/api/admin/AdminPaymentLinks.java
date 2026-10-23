package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPaymentDetailResponse;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ajouts de la fiche paiement : alerte la plus urgente, n° de suivi, identifiants des fiches liées, alertes
 * ouvertes et dernier litige bancaire.
 *
 * <p>Le blocage par permission se fait ICI, jamais dans le front : chaque identifiant n'est renvoyé que si
 * l'admin détient la permission de sa destination, sinon il n'est même pas lu (champ absent du JSON, jamais un
 * objet vide qui trahirait son existence). Le colis est le colis effectif du paiement (direct, ou lié au fil de
 * négociation, comme la liste) ; ses litige, annulation et conversation viennent de la même définition que la
 * fiche colis ({@link AdminBidDetailAssembler#incidentLinksOf}).
 */
@Component
public class AdminPaymentLinks {

    /** Alertes ouvertes renvoyées au plus (les plus récentes) : la fiche en montre un aperçu, pas un journal. */
    public static final int OPEN_ALERTS_LIMIT = 20;

    private final AdminPaymentInsights insights;
    private final AdminBidDetailAssembler bidDetail;
    private final NegotiationThreadRepository threads;
    private final ChargebackRepository chargebacks;
    private final AdminAlertRepository alerts;

    public AdminPaymentLinks(AdminPaymentInsights insights, AdminBidDetailAssembler bidDetail,
                             NegotiationThreadRepository threads, ChargebackRepository chargebacks,
                             AdminAlertRepository alerts) {
        this.insights = insights;
        this.bidDetail = bidDetail;
        this.threads = threads;
        this.chargebacks = chargebacks;
        this.alerts = alerts;
    }

    /** Ajouts de la fiche, déjà filtrés : un champ {@code null} est omis du JSON. */
    public record Result(String alert, String trackingNumber, AdminPaymentDetailResponse.Links links,
                         List<AdminPaymentDetailResponse.OpenAlert> openAlerts,
                         AdminPaymentDetailResponse.Chargeback chargeback) {
        public static final Result EMPTY = new Result(null, null, null, null, null);
    }

    public Result of(PaymentEntity payment, LocalDateTime now, Authentication authentication) {
        UUID paymentId = payment.getId();
        if (paymentId == null) return Result.EMPTY;
        Set<String> granted = authentication == null ? Set.of()
                : authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
        AdminBidDetailAssembler.Access access = AdminBidDetailAssembler.Access.of(authentication);
        boolean canViewBids = granted.contains("BID_VIEW");
        boolean canViewAlerts = granted.contains("ALERT_VIEW");

        AdminPaymentInsights.Context context = insights.contextsOf(List.of(payment), now).get(paymentId);
        UUID bidId = context != null && context.insight() != null ? context.insight().bidId() : null;
        String alert = AdminPaymentAlert.ofRank(insights.alertRanks(List.of(paymentId), now).getOrDefault(paymentId, 0))
                .map(AdminPaymentAlert::name).orElse(null);

        AdminBidDetailAssembler.IncidentLinks incident = bidId != null && (access.disputes() || access.moderation())
                ? bidDetail.incidentLinksOf(bidId, access)
                : new AdminBidDetailAssembler.IncidentLinks(null, null, null, null);
        ChargebackEntity chargeback = access.payments()
                ? chargebacks.findOfPayment(paymentId, bidId, PageRequest.of(0, 1)).stream().findFirst().orElse(null)
                : null;
        UUID packageRequestId = canViewBids && payment.getNegotiationThreadId() != null
                ? threads.findById(payment.getNegotiationThreadId())
                        .map(NegotiationThreadEntity::getPackageRequestId).orElse(null)
                : null;

        AdminPaymentDetailResponse.Links links = new AdminPaymentDetailResponse.Links(
                canViewBids && context != null ? context.announcementId() : null,
                access.disputes() ? incident.disputeId() : null,
                access.disputes() ? incident.disputeStatus() : null,
                access.disputes() ? incident.cancellationId() : null,
                access.moderation() ? incident.conversationId() : null,
                chargeback != null ? chargeback.getId() : null,
                packageRequestId);
        List<AdminPaymentDetailResponse.OpenAlert> open = canViewAlerts
                ? alerts.findOpenReferencing("%" + paymentId + "%", OPEN_ALERTS_LIMIT).stream()
                        .map(a -> new AdminPaymentDetailResponse.OpenAlert(a.getId(), a.getType(), a.getSeverity()))
                        .toList()
                : null;
        return new Result(alert, context != null ? context.trackingNumber() : null, links, open,
                chargeback == null ? null : new AdminPaymentDetailResponse.Chargeback(chargeback.getId(),
                        chargeback.getStatus() != null ? chargeback.getStatus().name() : null,
                        chargeback.getReason(), chargeback.getOutcome(),
                        chargeback.getOpenedAt() == null ? null
                                : LocalDateTime.ofInstant(chargeback.getOpenedAt(), ZoneOffset.UTC)));
    }
}
