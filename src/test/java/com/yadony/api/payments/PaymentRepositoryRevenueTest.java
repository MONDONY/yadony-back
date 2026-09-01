package com.yadony.api.payments;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.payments.dto.CurrencyAmountRow;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Régression sur l'agrégation revenu voyageur : un paiement issu du flux
 * négociation / trajet dédié a {@code bidId = NULL} (keyé sur le thread). Les
 * requêtes ne doivent pas le laisser tomber à cause d'un INNER JOIN sur le bid.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PaymentRepositoryRevenueTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired TestEntityManager em;

    private static final LocalDateTime FROM = LocalDateTime.now().minusDays(1);
    private static final LocalDateTime TO = LocalDateTime.now().plusDays(1);

    private int piSeq = 0;

    private AnnouncementEntity newAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.of(2026, 8, 15));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.COMPLETED);
        return em.persistAndFlush(a);
    }

    private BidEntity newBid(UUID announcementId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        return em.persistAndFlush(b);
    }

    private NegotiationThreadEntity newThread(UUID travelerId, UUID travelerAnnouncementId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(travelerId);
        t.setTravelerAnnouncementId(travelerAnnouncementId);
        t.setTravelerTravelDate(LocalDate.of(2026, 8, 15));
        t.setTravelerAvailableKg(new BigDecimal("15.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return em.persistAndFlush(t);
    }

    /** Total réduit d'une ventilation par devise — zéro quand elle est vide. */
    private static BigDecimal sumOf(List<CurrencyAmountRow> rows) {
        return rows.stream().map(CurrencyAmountRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PaymentEntity newPayment(
            UUID bidId, UUID threadId, String amount, String commission, PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setStripePaymentIntentId("pi_test_" + (piSeq++));
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        return em.persistAndFlush(p);
    }

    @Test
    void threadPayment_isCountedInPeriodRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        // bidId NULL : paiement keyé sur le thread (flux négociation).
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = sumOf(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO));

        // net = amount - commission = 176. Avant fix : 0 (ligne jetée par l'INNER JOIN).
        assertThat(revenue).isEqualByComparingTo("176.00");
    }

    @Test
    void bidPayment_stillCountedInPeriodRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID bid = newBid(ann).getId();
        newPayment(bid, null, "100.00", "12.00", PaymentStatus.RELEASED);

        BigDecimal revenue = sumOf(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("88.00");
    }

    @Test
    void bidAndThreadPayments_areSummedTogether() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID bid = newBid(ann).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(bid, null, "100.00", "12.00", PaymentStatus.RELEASED);
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = sumOf(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("264.00");
    }

    @Test
    void otherTravelerThreadPayment_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID otherTraveler = UUID.randomUUID();
        UUID otherAnn = newAnnouncement(otherTraveler).getId();
        UUID otherThread = newThread(otherTraveler, otherAnn).getId();
        newPayment(null, otherThread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = sumOf(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void nonReleasedThreadPayment_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.ESCROW);

        BigDecimal revenue = sumOf(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void threadPayment_isCountedInTotalRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal total = sumOf(paymentRepository.sumTotalCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED));

        assertThat(total).isEqualByComparingTo("176.00");
    }

    @Test
    void threadPayment_isAttributedToTravelerAnnouncement() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        List<AnnouncementRevenueRow> rows = paymentRepository.findReleasedRevenueByAnnouncement(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).announcementId()).isEqualTo(ann);
        assertThat(rows.get(0).gross()).isEqualByComparingTo("200.00");
        assertThat(rows.get(0).commission()).isEqualByComparingTo("24.00");
    }

    @Test
    void revenue_isGroupedByCurrency_neverFlattened() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID bid = newBid(ann).getId();
        UUID bid2 = newBid(ann).getId();
        UUID bid3 = newBid(ann).getId();
        // Un voyageur payé en EUR et en XOF : deux groupes, jamais une somme à plat
        // (l'ancien SUM sans GROUP BY rendait 65 738,71 « euros »).
        PaymentEntity p1 = newPayment(bid, null, "100.00", "12.00", PaymentStatus.RELEASED);
        p1.setCurrency("EUR");
        PaymentEntity p2 = newPayment(bid2, null, "50.00", "6.00", PaymentStatus.RELEASED);
        p2.setCurrency("EUR");
        PaymentEntity p3 = newPayment(bid3, null, "65596", "7871", PaymentStatus.RELEASED);
        p3.setCurrency("XOF");
        em.flush();

        List<CurrencyAmountRow> rows = paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(rows).containsExactlyInAnyOrder(
                new CurrencyAmountRow("EUR", new BigDecimal("132.00")),
                new CurrencyAmountRow("XOF", new BigDecimal("57725.00")));
    }

    // ── Tests hasActiveEscrowForUser ──────────────────────────────────────────
    // Constat 1 : la requête précédente ne couvrait pas le flux négociation
    // (paiements avec bidId=NULL, keyés sur negotiationThreadId). Un escrow de
    // négociation devait produire un constat bloquant et l'anonymisation devait
    // être refusée — elle ne l'était pas.

    private PackageRequestEntity newPackageRequest(UUID senderId) {
        PackageRequestEntity pr = new PackageRequestEntity();
        pr.setSenderId(senderId);
        pr.setDepartureCity("Paris");
        pr.setArrivalCity("Dakar");
        pr.setDesiredDate(LocalDate.of(2026, 9, 1));
        pr.setDateToleranceDays((short) 3);
        pr.setWeightKg(new BigDecimal("5.00"));
        pr.setParcelSize(ParcelSize.SMALL);
        pr.setTransportMode(TransportMode.PLANE);
        pr.setContentCategory("VETEMENTS");
        pr.setStatus(PackageRequestStatus.OPEN);
        return em.persistAndFlush(pr);
    }

    private NegotiationThreadEntity newThreadWithRequest(UUID travelerId, UUID packageRequestId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(travelerId);
        t.setTravelerAnnouncementId(null);
        t.setTravelerTravelDate(LocalDate.of(2026, 9, 1));
        t.setTravelerAvailableKg(new BigDecimal("10.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("150.00"));
        t.setRoundsCount((short) 2);
        t.setLastActivityAt(LocalDateTime.now());
        return em.persistAndFlush(t);
    }

    @Test
    @DisplayName("un escrow de négociation (bidId=NULL) est détecté pour le voyageur")
    void negotiationEscrow_detectedForTraveler() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID requestId = newPackageRequest(sender).getId();
        UUID threadId = newThreadWithRequest(traveler, requestId).getId();
        newPayment(null, threadId, "150.00", "18.00", PaymentStatus.ESCROW);

        assertThat(paymentRepository.hasActiveEscrowForUser(traveler)).isTrue();
    }

    @Test
    @DisplayName("un escrow de négociation (bidId=NULL) est détecté pour l'expéditeur")
    void negotiationEscrow_detectedForSender() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID requestId = newPackageRequest(sender).getId();
        UUID threadId = newThreadWithRequest(traveler, requestId).getId();
        newPayment(null, threadId, "150.00", "18.00", PaymentStatus.ESCROW);

        assertThat(paymentRepository.hasActiveEscrowForUser(sender)).isTrue();
    }

    @Test
    @DisplayName("un escrow de bid direct (negotiationThreadId=NULL) reste détecté")
    void bidEscrow_stillDetected() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        BidEntity bid = newBid(ann);
        newPayment(bid.getId(), null, "100.00", "12.00", PaymentStatus.ESCROW);

        assertThat(paymentRepository.hasActiveEscrowForUser(traveler)).isTrue();
        assertThat(paymentRepository.hasActiveEscrowForUser(bid.getSenderId())).isTrue();
    }

    @Test
    @DisplayName("un paiement de négociation non-ESCROW ne produit pas de blocage")
    void releasedNegotiationPayment_doesNotBlock() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID requestId = newPackageRequest(sender).getId();
        UUID threadId = newThreadWithRequest(traveler, requestId).getId();
        newPayment(null, threadId, "150.00", "18.00", PaymentStatus.RELEASED);

        assertThat(paymentRepository.hasActiveEscrowForUser(traveler)).isFalse();
        assertThat(paymentRepository.hasActiveEscrowForUser(sender)).isFalse();
    }

    @Test
    @DisplayName("un utilisateur sans paiement en séquestre ne produit pas de blocage")
    void noEscrow_returnsFalse() {
        UUID randomUser = UUID.randomUUID();
        assertThat(paymentRepository.hasActiveEscrowForUser(randomUser)).isFalse();
    }

    // ── Tests discriminants angle mort soft-delete ────────────────────────────
    // Ces deux tests échouent si l'on utilise la requête JPQL (LEFT JOIN filtré
    // par @Where/@SQLRestriction) et passent avec la requête native SQL.

    /**
     * Cas 1 — flux bid : le bid et l'annonce sont soft-deleted mais le paiement
     * reste en ESCROW. La détection doit retourner true.
     *
     * Avec la requête JPQL précédente : Hibernate injectait
     * "AND b.deleted_at IS NULL" dans le ON du LEFT JOIN, rendant b NULL, donc
     * aucun des critères (b.sender_id, a.traveler_id) n'était satisfait → false.
     */
    @Test
    @DisplayName("escrow détecté même si le bid et l'annonce sont soft-deleted")
    void escrow_detectedWhenBidAndAnnouncementSoftDeleted() {
        UUID traveler = UUID.randomUUID();
        AnnouncementEntity ann = newAnnouncement(traveler);
        BidEntity bid = newBid(ann.getId());

        // Soft-delete bid et annonce directement en base (contourne les filtres JPA)
        em.getEntityManager().createNativeQuery(
                "UPDATE bids SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", bid.getId())
                .executeUpdate();
        em.getEntityManager().createNativeQuery(
                "UPDATE announcements SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", ann.getId())
                .executeUpdate();

        newPayment(bid.getId(), null, "100.00", "12.00", PaymentStatus.ESCROW);
        em.flush();
        em.clear();

        // Le paiement reste en séquestre — le compte ne peut pas être anonymisé
        assertThat(paymentRepository.hasActiveEscrowForUser(traveler)).isTrue();
        assertThat(paymentRepository.hasActiveEscrowForUser(bid.getSenderId())).isTrue();
    }

    /**
     * Cas 2 — flux négociation : le fil de négociation est soft-deleted mais le
     * paiement reste en ESCROW. La détection doit retourner true.
     *
     * Avec la requête JPQL précédente : Hibernate injectait
     * "AND t.deleted_at IS NULL" dans le ON du LEFT JOIN, rendant t NULL, donc
     * ni t.traveler_id ni pr.sender_id n'était satisfait → false.
     */
    @Test
    @DisplayName("escrow détecté même si le fil de négociation est soft-deleted")
    void escrow_detectedWhenNegotiationThreadSoftDeleted() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID requestId = newPackageRequest(sender).getId();
        NegotiationThreadEntity thread = newThreadWithRequest(traveler, requestId);

        // Soft-delete le fil de négociation directement en base
        em.getEntityManager().createNativeQuery(
                "UPDATE negotiation_threads SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", thread.getId())
                .executeUpdate();

        newPayment(null, thread.getId(), "150.00", "18.00", PaymentStatus.ESCROW);
        em.flush();
        em.clear();

        // Le paiement reste en séquestre — le compte ne peut pas être anonymisé
        assertThat(paymentRepository.hasActiveEscrowForUser(traveler)).isTrue();
        assertThat(paymentRepository.hasActiveEscrowForUser(sender)).isTrue();
    }
}
