package com.yadony.api.admin.notifications;

import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.SupportPriority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Une requête par source du fil : projection minimale, bornée par la fenêtre ({@code from}), le
 * curseur ({@code before}, exclusif) et la limite, triée par date décroissante. Chacune s'appuie
 * sur un index de V271 (ou de V270 pour les versements retenus).
 *
 * <p>JPQL sur les entités des autres paquets, en lecture seule, comme la vue d'ensemble : aucune
 * injection de service d'un autre paquet. Les dates sont stockées en UTC sans fuseau.
 */
@Component
@Transactional(readOnly = true)
public class AdminNotificationQueries {

    private final EntityManager em;

    public AdminNotificationQueries(EntityManager em) {
        this.em = em;
    }

    public List<AdminNotificationItem> find(AdminNotificationType type, Instant from, Instant before, int limit) {
        return switch (type) {
            case REPORT_CREATED -> run(
                    "SELECT r.id, r.createdAt, r.reason, r.targetType FROM ReportEntity r WHERE %s",
                    "r.createdAt", Map.of(), from, before, limit,
                    row -> AdminNotificationItems.report((UUID) row[0], utc(row[1]),
                            (ReportReason) row[2], (ReportTargetType) row[3]));
            case SUPPORT_TICKET_CREATED -> run(
                    "SELECT t.id, t.createdAt, t.category, t.priority, u.firstName, u.lastName "
                            + "FROM SupportTicketEntity t LEFT JOIN UserEntity u ON u.id = t.userId WHERE %s",
                    "t.createdAt", Map.of(), from, before, limit,
                    row -> AdminNotificationItems.supportTicket((UUID) row[0], utc(row[1]), (String) row[2],
                            (SupportPriority) row[3], (String) row[4], (String) row[5]));
            // Le premier message d'un ticket naît avec lui (SUPPORT_TICKET_CREATED) : seules les
            // réponses suivantes de l'utilisateur sont une nouveauté.
            case SUPPORT_MESSAGE_RECEIVED -> run(
                    "SELECT m.id, m.createdAt, m.ticketId, t.category, u.firstName, u.lastName "
                            + "FROM SupportMessageEntity m JOIN SupportTicketEntity t ON t.id = m.ticketId "
                            + "LEFT JOIN UserEntity u ON u.id = m.authorId "
                            + "WHERE m.authorType = :userAuthor AND EXISTS (SELECT p.id FROM SupportMessageEntity p "
                            + "WHERE p.ticketId = m.ticketId AND p.createdAt < m.createdAt) AND %s",
                    "m.createdAt", Map.of("userAuthor", SupportMessageAuthorType.USER), from, before, limit,
                    row -> AdminNotificationItems.supportMessage((UUID) row[0], (UUID) row[2], utc(row[1]),
                            (String) row[3], (String) row[4], (String) row[5]));
            case DISPUTE_OPENED -> run(
                    "SELECT d.id, d.createdAt FROM DisputeEntity d WHERE %s",
                    "d.createdAt", Map.of(), from, before, limit,
                    row -> AdminNotificationItems.dispute((UUID) row[0], utc(row[1])));
            case NOSHOW_PENDING -> run(
                    "SELECT c.id, c.createdAt FROM CancellationEntity c WHERE c.noShowStatus = :pending AND %s",
                    "c.createdAt", Map.of("pending", CancellationStatus.PENDING_CONFIRMATION), from, before, limit,
                    row -> AdminNotificationItems.noShow((UUID) row[0], utc(row[1])));
            case KYC_IN_REVIEW -> run(
                    "SELECT k.id, k.submittedAt, k.userId, u.firstName, u.lastName "
                            + "FROM KycVerificationEntity k LEFT JOIN UserEntity u ON u.id = k.userId WHERE %s",
                    "k.submittedAt", Map.of(), from, before, limit,
                    row -> AdminNotificationItems.kycInReview((UUID) row[0], (UUID) row[2], utc(row[1]),
                            (String) row[3], (String) row[4]));
            // Versements encore retenus : même périmètre que /admin/payments?held=true.
            case PAYOUT_HELD -> run(
                    "SELECT p.id, p.payoutHeldAt, p.amount, p.currency FROM PaymentEntity p "
                            + "WHERE p.status = :escrow AND %s",
                    "p.payoutHeldAt", Map.of("escrow", PaymentStatus.ESCROW), from, before, limit,
                    row -> AdminNotificationItems.payoutHeld((UUID) row[0], utc(row[1]),
                            (BigDecimal) row[2], (String) row[3]));
            // Tickets manuels : les demandes automatiques (Stripe, pawaPay) se traitent seules.
            case WALLET_REFUND_REQUESTED -> run(
                    "SELECT w.id, w.requestedAt, w.amount, w.currency, u.firstName, u.lastName "
                            + "FROM WalletRefundRequestEntity w LEFT JOIN UserEntity u ON u.id = w.userId "
                            + "WHERE w.channel = :manual AND %s",
                    "w.requestedAt", Map.of("manual", WalletRefundChannel.MANUAL_ADMIN), from, before, limit,
                    row -> AdminNotificationItems.walletRefund((UUID) row[0], utc(row[1]),
                            (BigDecimal) row[2], (String) row[3], (String) row[4], (String) row[5]));
            case GDPR_REQUESTED -> runInstant(
                    "SELECT u.id, u.deletionRequestedAt, u.firstName, u.lastName FROM UserEntity u WHERE %s",
                    "u.deletionRequestedAt", from, before, limit,
                    row -> AdminNotificationItems.gdpr((UUID) row[0], (Instant) row[1],
                            (String) row[2], (String) row[3]));
            case ADMIN_ALERT -> run(
                    "SELECT a.id, a.createdAt, a.type, a.severity FROM AdminAlertEntity a "
                            + "WHERE a.resolved = false AND %s",
                    "a.createdAt", Map.of(), from, before, limit,
                    row -> AdminNotificationItems.alert((UUID) row[0], utc(row[1]),
                            (String) row[2], (String) row[3]));
        };
    }

    /** Source datée en {@link LocalDateTime} UTC (la plupart des colonnes {@code TIMESTAMP}). */
    private List<AdminNotificationItem> run(String jpql, String dateField, Map<String, Object> params,
                                            Instant from, Instant before, int limit,
                                            Function<Object[], AdminNotificationItem> mapper) {
        TypedQuery<Object[]> query = em.createQuery(
                String.format(jpql, window(dateField, before)) + " ORDER BY " + dateField + " DESC", Object[].class);
        params.forEach(query::setParameter);
        query.setParameter("from", LocalDateTime.ofInstant(from, ZoneOffset.UTC));
        if (before != null) {
            query.setParameter("before", LocalDateTime.ofInstant(before, ZoneOffset.UTC));
        }
        return query.setMaxResults(limit).getResultList().stream().map(mapper).toList();
    }

    /** Source datée en {@link Instant} ({@code users.deletion_requested_at}). */
    private List<AdminNotificationItem> runInstant(String jpql, String dateField, Instant from, Instant before,
                                                   int limit, Function<Object[], AdminNotificationItem> mapper) {
        TypedQuery<Object[]> query = em.createQuery(
                String.format(jpql, window(dateField, before)) + " ORDER BY " + dateField + " DESC", Object[].class);
        query.setParameter("from", from);
        if (before != null) {
            query.setParameter("before", before);
        }
        return query.setMaxResults(limit).getResultList().stream().map(mapper).toList();
    }

    private static String window(String dateField, Instant before) {
        String clause = dateField + " >= :from";
        return before == null ? clause : clause + " AND " + dateField + " < :before";
    }

    private static Instant utc(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
