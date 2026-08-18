package com.yadony.api.admin.metrics;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Transactional(readOnly = true)
public class AdminMetricsService {

    private final EntityManager em;

    public AdminMetricsService(EntityManager em) {
        this.em = em;
    }

    public AdminOverviewResponse buildOverview() {
        return new AdminOverviewResponse(
                buildUsers(),
                buildAnnouncements(),
                buildBids(),
                buildGmv(),
                buildQueues()
        );
    }

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    private AdminOverviewResponse.Users buildUsers() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new AdminOverviewResponse.Users(
                countUsers(null, null),
                countUsers("status", UserStatus.ACTIVE),
                countUsers("status", UserStatus.SUSPENDED),
                countUsers("status", UserStatus.BANNED),
                countUsers("status", UserStatus.PENDING_DELETION),
                countUsers("kycStatus", KycStatus.VERIFIED),
                countUsers("kycStatus", KycStatus.PENDING),
                countByFlag("u.isProAccount = true"),
                countUsersCreatedAfter(now.minusDays(7)),
                countUsersCreatedAfter(now.minusDays(30))
        );
    }

    private long countUsers(String field, Object value) {
        if (field == null) {
            return em.createQuery("SELECT COUNT(u) FROM UserEntity u", Long.class)
                    .getSingleResult();
        }
        return em.createQuery(
                        "SELECT COUNT(u) FROM UserEntity u WHERE u." + field + " = :val", Long.class)
                .setParameter("val", value)
                .getSingleResult();
    }

    private long countByFlag(String condition) {
        return em.createQuery(
                        "SELECT COUNT(u) FROM UserEntity u WHERE " + condition, Long.class)
                .getSingleResult();
    }

    private long countUsersCreatedAfter(LocalDateTime since) {
        return em.createQuery(
                        "SELECT COUNT(u) FROM UserEntity u WHERE u.createdAt >= :since", Long.class)
                .setParameter("since", since)
                .getSingleResult();
    }

    // -------------------------------------------------------------------------
    // Announcements
    // -------------------------------------------------------------------------

    private AdminOverviewResponse.Announcements buildAnnouncements() {
        return new AdminOverviewResponse.Announcements(
                countAnnouncements(AnnouncementStatus.ACTIVE),
                countAnnouncements(AnnouncementStatus.FULL),
                countAnnouncements(AnnouncementStatus.IN_PROGRESS),
                countAnnouncements(AnnouncementStatus.COMPLETED),
                countAnnouncements(AnnouncementStatus.CANCELLED)
        );
    }

    private long countAnnouncements(AnnouncementStatus status) {
        return em.createQuery(
                        "SELECT COUNT(a) FROM AnnouncementEntity a WHERE a.status = :status", Long.class)
                .setParameter("status", status)
                .getSingleResult();
    }

    // -------------------------------------------------------------------------
    // Bids
    // -------------------------------------------------------------------------

    private AdminOverviewResponse.Bids buildBids() {
        return new AdminOverviewResponse.Bids(
                countBids(BidStatus.PENDING),
                countBids(BidStatus.ACCEPTED),
                countBids(BidStatus.IN_TRANSIT),
                countBids(BidStatus.COMPLETED),
                countBids(BidStatus.CANCELLED),
                countRealBids()
        );
    }

    /**
     * Total des colis, discussions de prix exclues.
     *
     * <p>Un bid en {@link BidStatus#NEGOTIATING} ou {@link BidStatus#NEGOTIATION_CLOSED}
     * n'est pas un colis : le compter gonflait le total du tableau de bord de fils de
     * négociation, dont aucun n'apparaît pourtant dans les cinq compteurs par statut
     * juste au-dessus. Le total ne serait alors comparable à aucun d'entre eux.
     */
    private long countRealBids() {
        return em.createQuery(
                        "SELECT COUNT(b) FROM BidEntity b WHERE b.status NOT IN :excluded", Long.class)
                .setParameter("excluded", BidStatus.NEGOTIATION_STATUSES)
                .getSingleResult();
    }

    private long countBids(BidStatus status) {
        return em.createQuery(
                        "SELECT COUNT(b) FROM BidEntity b WHERE b.status = :status", Long.class)
                .setParameter("status", status)
                .getSingleResult();
    }

    // -------------------------------------------------------------------------
    // GMV
    // -------------------------------------------------------------------------

    private AdminOverviewResponse.Gmv buildGmv() {
        return new AdminOverviewResponse.Gmv(
                sumPayments("amount", PaymentStatus.ESCROW),
                sumPayments("amount", PaymentStatus.RELEASED),
                sumPayments("refundedAmount", PaymentStatus.REFUNDED),
                sumPayments("commissionAmount", PaymentStatus.RELEASED)
        );
    }

    private BigDecimal sumPayments(String field, PaymentStatus status) {
        BigDecimal result = em.createQuery(
                        "SELECT COALESCE(SUM(p." + field + "), 0) FROM PaymentEntity p WHERE p.status = :status",
                        BigDecimal.class)
                .setParameter("status", status)
                .getSingleResult();
        return result != null ? result : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // Queues
    // -------------------------------------------------------------------------

    private AdminOverviewResponse.Queues buildQueues() {
        LocalDateTime j48threshold = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
        return new AdminOverviewResponse.Queues(
                countOpenDisputes(),
                countPendingNoShows(),
                countUnresolvedAlerts(),
                countUsers("kycStatus", KycStatus.PENDING),
                countEscrowJ48(j48threshold)
        );
    }

    private long countOpenDisputes() {
        return em.createQuery(
                        "SELECT COUNT(d) FROM DisputeEntity d WHERE d.status = 'OPEN'", Long.class)
                .getSingleResult();
    }

    private long countPendingNoShows() {
        return em.createQuery(
                        "SELECT COUNT(c) FROM CancellationEntity c WHERE c.noShowStatus = :status",
                        Long.class)
                .setParameter("status", CancellationStatus.PENDING_CONFIRMATION)
                .getSingleResult();
    }

    private long countUnresolvedAlerts() {
        return em.createQuery(
                        "SELECT COUNT(a) FROM AdminAlertEntity a WHERE a.resolved = false", Long.class)
                .getSingleResult();
    }

    private long countEscrowJ48(LocalDateTime threshold) {
        return em.createQuery(
                        "SELECT COUNT(p) FROM PaymentEntity p WHERE p.status = :status AND p.createdAt < :threshold",
                        Long.class)
                .setParameter("status", PaymentStatus.ESCROW)
                .setParameter("threshold", threshold)
                .getSingleResult();
    }
}
