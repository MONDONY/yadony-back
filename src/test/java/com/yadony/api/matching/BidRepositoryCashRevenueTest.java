package com.yadony.api.matching;

import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.matching.dto.CashLineRow;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.dto.CurrencyAmountRow;
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
 * Le revenu des deals réglés en espèces ne passe par aucun PaymentEntity : il se
 * reconstitue depuis le bid livré ({@code negotiatedNetEur}, sinon poids × prix/kg
 * + grille pour une offre directe), filtré CASH pour ne pas doubler les deals carte.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BidRepositoryCashRevenueTest {

    @Autowired BidRepository bidRepository;
    @Autowired TestEntityManager em;

    private static final LocalDateTime FROM = LocalDateTime.now().minusDays(1);
    private static final LocalDateTime TO = LocalDateTime.now().plusDays(1);

    private UUID newAnnouncement(UUID travelerId) {
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
        return em.persistAndFlush(a).getId();
    }

    private void newBid(UUID announcementId, BidStatus status, PaymentMethod method, String net) {
        newBid(announcementId, status, method, net, "EUR");
    }

    private void newBid(UUID announcementId, BidStatus status, PaymentMethod method, String net,
                        String currency) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        b.setPaymentMethod(method);
        b.setNegotiatedNetEur(net == null ? null : new BigDecimal(net));
        b.setCurrency(currency);
        em.persistAndFlush(b);
    }

    /** Offre directe en espèces : pas de prix négocié, le net vient du poids et de la grille. */
    private UUID newDirectCashBid(UUID announcementId, String weightKg) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.COMPLETED);
        b.setPaymentMethod(PaymentMethod.CASH);
        b.setWeightKg(weightKg == null ? null : new BigDecimal(weightKg));
        b.setCurrency("EUR");
        return em.persistAndFlush(b).getId();
    }

    private void newGridItem(UUID bidId, String unitNet, int quantity) {
        BidGridItemEntity g = new BidGridItemEntity();
        g.setBidId(bidId);
        g.setAnnouncementGridItemId(UUID.randomUUID());
        g.setLabelSnapshot("Valise");
        g.setUnitPriceNetSnapshot(new BigDecimal(unitNet));
        g.setQuantity(quantity);
        em.persistAndFlush(g);
    }

    /** Total réduit d'une ventilation par devise — zéro quand elle est vide. */
    private static BigDecimal sumOf(List<CurrencyAmountRow> rows) {
        return rows.stream().map(CurrencyAmountRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void completedCashBid_isCounted() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("150.00");
    }

    @Test
    void cardBid_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        // Un deal carte est déjà compté côté PaymentEntity — ne pas le doubler ici.
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.STRIPE, "150.00");

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void nonCompletedCashBid_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.ACCEPTED, PaymentMethod.CASH, "150.00");

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void otherTravelerCashBid_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID otherAnn = newAnnouncement(UUID.randomUUID());
        newBid(otherAnn, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void multipleCashBids_areSummed() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "90.00");

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("240.00");
    }

    @Test
    void totalCashRevenue_ignoresPeriod() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");

        BigDecimal total = sumOf(bidRepository.sumTotalCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH));

        assertThat(total).isEqualByComparingTo("150.00");
    }

    @Test
    void cashByAnnouncement_groupsWithNetGrossAndZeroCommission() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "90.00");
        // Bruit à exclure : carte, non-livré, autre annonce.
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.STRIPE, "999.00");
        newBid(ann, BidStatus.ACCEPTED, PaymentMethod.CASH, "999.00");

        List<AnnouncementRevenueRow> rows = bidRepository.findCashRevenueByAnnouncement(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO);

        assertThat(rows).hasSize(1);
        AnnouncementRevenueRow row = rows.get(0);
        assertThat(row.announcementId()).isEqualTo(ann);
        assertThat(row.parcelCount()).isEqualTo(2);
        // gross = net (le voyageur encaisse le net en cash), commission = 0.
        assertThat(row.gross()).isEqualByComparingTo("240.00");
        assertThat(row.commission()).isEqualByComparingTo("0");
    }

    @Test
    void cashRevenue_isGroupedByBidCurrency_neverFlattened() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00", "EUR");
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "50.00", "EUR");
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "65596", "XOF");

        List<CurrencyAmountRow> rows = bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO);

        assertThat(rows).containsExactlyInAnyOrder(
                new CurrencyAmountRow("EUR", new BigDecimal("200.00")),
                new CurrencyAmountRow("XOF", new BigDecimal("65596.00")));
    }

    @Test
    void cashByAnnouncement_excludesOtherTraveler() {
        UUID traveler = UUID.randomUUID();
        UUID otherAnn = newAnnouncement(UUID.randomUUID());
        newBid(otherAnn, BidStatus.COMPLETED, PaymentMethod.CASH, "150.00");

        List<AnnouncementRevenueRow> rows = bidRepository.findCashRevenueByAnnouncement(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO);

        assertThat(rows).isEmpty();
    }

    // ── Offre directe (sans prix négocié) — Sentry FLUTTER-BS ────────────────

    @Test
    void directCashBid_isCountedFromWeightAndGrid_notZero() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler); // 8 €/kg
        UUID bid = newDirectCashBid(ann, "5.00"); // 40 €
        newGridItem(bid, "15.00", 2); // + 30 €

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("70.00");
    }

    @Test
    void directCashBid_withoutWeight_countsGridOnly() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        UUID bid = newDirectCashBid(ann, null);
        newGridItem(bid, "25.00", 1);

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("25.00");
    }

    @Test
    void negotiatedCashBid_keepsNegotiatedNet_gridNotAddedTwice() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ann);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.COMPLETED);
        b.setPaymentMethod(PaymentMethod.CASH);
        b.setWeightKg(new BigDecimal("5.00"));
        b.setNegotiatedNetEur(new BigDecimal("55.00"));
        b.setCurrency("EUR");
        UUID bid = em.persistAndFlush(b).getId();
        newGridItem(bid, "15.00", 2);

        BigDecimal revenue = sumOf(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO));

        assertThat(revenue).isEqualByComparingTo("55.00");
    }

    @Test
    void directCashBid_lines_totalAndByAnnouncement_reconcile() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler);
        UUID bid = newDirectCashBid(ann, "5.00");
        newGridItem(bid, "15.00", 2);
        newBid(ann, BidStatus.COMPLETED, PaymentMethod.CASH, "100.00");

        List<CashLineRow> lines = bidRepository.findCashLinesForTraveler(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO);
        assertThat(lines).extracting(CashLineRow::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyInAnyOrder(new BigDecimal("70.00"), new BigDecimal("100.00"));

        assertThat(sumOf(bidRepository.sumTotalCashNetRevenueForTravelerByCurrency(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH)))
                .isEqualByComparingTo("170.00");

        List<AnnouncementRevenueRow> rows = bidRepository.findCashRevenueByAnnouncement(
                traveler, BidStatus.COMPLETED, PaymentMethod.CASH, FROM, TO);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).gross()).isEqualByComparingTo("170.00");
        assertThat(rows.get(0).commission()).isEqualByComparingTo("0");
    }
}
