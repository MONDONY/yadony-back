package com.yadony.api.admin.metrics;

import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.kyc.KycVerificationStatus;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.support.SupportTicketStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comptage des files à traiter du panel, source unique de la vue d'ensemble
 * ({@code queues} de {@code /admin/metrics/overview}) et des compteurs du menu
 * ({@code /admin/notifications/counters}). Chaque définition reprend le filtre de la page admin
 * correspondante : un compteur qui ne tombe pas sur la liste qu'il annonce est pire que rien.
 *
 * <p>Lecture seule, en {@code COUNT} indexés. Lit les entités d'autres paquets en JPQL, comme la
 * vue d'ensemble l'a toujours fait : aucune injection de service d'un autre paquet.
 */
@Component
@Transactional(readOnly = true)
public class AdminQueueCounter {

    private final EntityManager em;

    public AdminQueueCounter(EntityManager em) {
        this.em = em;
    }

    public AdminQueueSnapshot snapshot() {
        long unassigned = 0;
        Map<UUID, Long> byAdmin = new HashMap<>();
        for (Object[] row : supportLoad()) {
            if (row[0] == null) {
                unassigned += (Long) row[1];
            } else {
                byAdmin.put((UUID) row[0], (Long) row[1]);
            }
        }
        return new AdminQueueSnapshot(
                countOpenReports(),
                unassigned,
                byAdmin,
                countOpenDisputes(),
                countPendingNoShows(),
                countKycInReview(),
                countHeldPayouts(),
                countPendingWalletRefunds(),
                countPendingGdpr(),
                countUnresolvedAlerts());
    }

    /** Onglet « Ouverts » de {@code /signalements}. */
    long countOpenReports() {
        return em.createQuery("SELECT COUNT(r) FROM ReportEntity r WHERE r.status = :open", Long.class)
                .setParameter("open", ReportStatus.OPEN)
                .getSingleResult();
    }

    /** Tickets non résolus, groupés par administrateur assigné (NULL = non assigné). */
    private List<Object[]> supportLoad() {
        return em.createQuery("SELECT t.assignedAdminId, COUNT(t) FROM SupportTicketEntity t "
                        + "WHERE t.status <> :resolved GROUP BY t.assignedAdminId", Object[].class)
                .setParameter("resolved", SupportTicketStatus.RESOLVED)
                .getResultList();
    }

    /** Meme definition que l'etat IN_REVIEW de la file KYC (KycAdminReviewService). */
    public long countKycInReview() {
        return em.createQuery(
                        "SELECT COUNT(k) FROM KycVerificationEntity k "
                                + "WHERE k.status = :pending AND k.submittedAt IS NOT NULL", Long.class)
                .setParameter("pending", KycVerificationStatus.PENDING)
                .getSingleResult();
    }

    /** Meme definition que le filtre {@code GET /admin/payments?held=true}. */
    public long countHeldPayouts() {
        return em.createQuery(
                        "SELECT COUNT(p) FROM PaymentEntity p WHERE p.status = :status AND p.payoutHeldAt IS NOT NULL",
                        Long.class)
                .setParameter("status", PaymentStatus.ESCROW)
                .getSingleResult();
    }

    public long countOpenDisputes() {
        return em.createQuery(
                        "SELECT COUNT(d) FROM DisputeEntity d WHERE d.status = 'OPEN'", Long.class)
                .getSingleResult();
    }

    public long countPendingNoShows() {
        return em.createQuery(
                        "SELECT COUNT(c) FROM CancellationEntity c WHERE c.noShowStatus = :status",
                        Long.class)
                .setParameter("status", CancellationStatus.PENDING_CONFIRMATION)
                .getSingleResult();
    }

    public long countUnresolvedAlerts() {
        return em.createQuery(
                        "SELECT COUNT(a) FROM AdminAlertEntity a WHERE a.resolved = false", Long.class)
                .getSingleResult();
    }

    /** Meme definition que {@code GET /admin/wallet-refund-requests} (statut PENDING, tous canaux). */
    long countPendingWalletRefunds() {
        return em.createQuery(
                        "SELECT COUNT(w) FROM WalletRefundRequestEntity w WHERE w.status = :pending", Long.class)
                .setParameter("pending", WalletRefundRequestStatus.PENDING)
                .getSingleResult();
    }

    /** Meme definition que {@code GET /admin/users/gdpr-requests}. */
    long countPendingGdpr() {
        return em.createQuery(
                        "SELECT COUNT(u) FROM UserEntity u WHERE u.deletionRequestedAt IS NOT NULL", Long.class)
                .getSingleResult();
    }
}
