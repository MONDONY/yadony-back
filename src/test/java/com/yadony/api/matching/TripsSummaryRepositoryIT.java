package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.CashLineRow;
import com.yadony.api.matching.dto.KgSoldTripRow;
import com.yadony.api.matching.dto.PaymentLineRow;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TripsSummaryRepositoryIT {

    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired PackageRequestRepository packageRequestRepository;

    @Test
    void countByTravelerIdAndStatusIn_counts_active_full_and_in_progress() {
        UUID travelerId = persistTraveler().getId();
        persistAnnouncement(travelerId, AnnouncementStatus.ACTIVE);
        persistAnnouncement(travelerId, AnnouncementStatus.FULL);
        persistAnnouncement(travelerId, AnnouncementStatus.IN_PROGRESS);
        persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);

        long count = announcementRepository.countByTravelerIdAndStatusIn(
                travelerId,
                List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL,
                        AnnouncementStatus.IN_PROGRESS));

        assertThat(count).isEqualTo(3);
    }

    @Test
    void sumDeliveredKgForTraveler_sums_completed_bids_weight_in_period() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann =
                persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("4.50"));
        persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("2.50"));
        persistBid(ann.getId(), BidStatus.CANCELLED, new BigDecimal("9.00"));

        BigDecimal sum = bidRepository.sumDeliveredKgForTraveler(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(sum).isEqualByComparingTo("7.00");
    }

    @Test
    void sumDeliveredKgForTraveler_returns_zero_when_no_bids() {
        UUID travelerId = persistTraveler().getId();

        BigDecimal sum = bidRepository.sumDeliveredKgForTraveler(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void findReleasedLinesForTraveler_returns_card_and_mobile_money_lines_with_their_trip() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        BidEntity card = persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("4.00"));
        BidEntity mobile = persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("3.00"));
        BidEntity cardEscrowed = persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("1.00"));
        persistPayment(card.getId(), null, PaymentRail.STRIPE, "EUR", "500.00", "20.00", PaymentStatus.RELEASED);
        persistPayment(mobile.getId(), null, PaymentRail.PAWAPAY, "XOF", "130000", "10000", PaymentStatus.RELEASED);
        // Séquestre non libéré : jamais un revenu. Bid dédié — payments.bid_id est UNIQUE,
        // un deuxième paiement sur `card` violerait la contrainte (H2 23505).
        persistPayment(cardEscrowed.getId(), null, PaymentRail.STRIPE, "EUR", "99.00", "1.00", PaymentStatus.ESCROW);

        List<PaymentLineRow> lines = paymentRepository.findReleasedLinesForTraveler(
                travelerId, PaymentStatus.RELEASED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(PaymentLineRow::tripId).containsOnly(ann.getId());
        assertThat(lines).extracting(PaymentLineRow::departureCity).containsOnly("Paris");
        assertThat(lines).extracting(PaymentLineRow::departureDate).containsOnly(ann.getDepartureDate());
        assertThat(lines).extracting(PaymentLineRow::rail)
                .containsExactlyInAnyOrder(PaymentRail.STRIPE, PaymentRail.PAWAPAY);
        assertThat(lines).extracting(PaymentLineRow::currency).containsExactlyInAnyOrder("EUR", "XOF");
        assertThat(lines).extracting(l -> l.amount().stripTrailingZeros())
                .containsExactlyInAnyOrder(
                        new BigDecimal("480").stripTrailingZeros(),
                        new BigDecimal("120000").stripTrailingZeros());
        assertThat(lines).extracting(PaymentLineRow::weightKg)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("4.00"), new BigDecimal("3.00"));
        assertThat(lines).extracting(PaymentLineRow::paidAt).doesNotContainNull();
    }

    @Test
    void findReleasedLinesForTraveler_attributes_thread_payments_without_trip_to_the_package_request() {
        UUID travelerId = persistTraveler().getId();
        PackageRequestEntity colis = persistPackageRequest("Lyon", "Abidjan", new BigDecimal("2.50"));
        NegotiationThreadEntity thread = persistThread(colis.getId(), travelerId, null);
        persistPayment(null, thread.getId(), PaymentRail.STRIPE, "EUR", "200.00", "24.00", PaymentStatus.RELEASED);

        List<PaymentLineRow> lines = paymentRepository.findReleasedLinesForTraveler(
                travelerId, PaymentStatus.RELEASED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(lines).hasSize(1);
        PaymentLineRow line = lines.get(0);
        assertThat(line.tripId()).isNull();
        assertThat(line.departureDate()).isNull();
        assertThat(line.departureCity()).isEqualTo("Lyon");
        assertThat(line.arrivalCity()).isEqualTo("Abidjan");
        assertThat(line.weightKg()).isEqualByComparingTo("2.50");
        assertThat(line.amount()).isEqualByComparingTo("176.00");
    }

    @Test
    void findReleasedLinesForTraveler_uses_the_thread_trip_when_linked() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        PackageRequestEntity colis = persistPackageRequest("Lyon", "Abidjan", new BigDecimal("2.50"));
        NegotiationThreadEntity thread = persistThread(colis.getId(), travelerId, ann.getId());
        persistPayment(null, thread.getId(), PaymentRail.STRIPE, "EUR", "200.00", "24.00", PaymentStatus.RELEASED);

        List<PaymentLineRow> lines = paymentRepository.findReleasedLinesForTraveler(
                travelerId, PaymentStatus.RELEASED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).tripId()).isEqualTo(ann.getId());
        assertThat(lines.get(0).departureCity()).isEqualTo("Paris");
        assertThat(lines.get(0).departureDate()).isEqualTo(ann.getDepartureDate());
    }

    @Test
    void findCashLinesForTraveler_returns_only_cash_completed_bids() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        persistCashBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("2.00"), "EUR", "220.00");
        persistCashBid(ann.getId(), BidStatus.ACCEPTED, new BigDecimal("5.00"), "EUR", "500.00");
        BidEntity carte = persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("4.00"));
        carte.setNegotiatedNetEur(new BigDecimal("480.00"));
        bidRepository.save(carte);

        List<CashLineRow> lines = bidRepository.findCashLinesForTraveler(
                travelerId, BidStatus.COMPLETED, PaymentMethod.CASH,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(lines).hasSize(1);
        CashLineRow line = lines.get(0);
        assertThat(line.tripId()).isEqualTo(ann.getId());
        assertThat(line.arrivalCity()).isEqualTo("Dakar");
        assertThat(line.departureDate()).isEqualTo(ann.getDepartureDate());
        assertThat(line.weightKg()).isEqualByComparingTo("2.00");
        assertThat(line.currency()).isEqualTo("EUR");
        assertThat(line.amount()).isEqualByComparingTo("220.00");
    }

    @Test
    void findCashLinesForTraveler_defaults_amount_to_zero_when_negotiated_net_eur_is_null() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(ann.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.COMPLETED);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCurrency("EUR");
        // negotiatedNetEur volontairement non renseigné : bid CASH direct (BidService,
        // annonce acceptant les espèces), jamais passé par une négociation qui l'aurait figé.
        bidRepository.save(bid);

        List<CashLineRow> lines = bidRepository.findCashLinesForTraveler(
                travelerId, BidStatus.COMPLETED, PaymentMethod.CASH,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("0");
    }

    @Test
    void findDeliveredKgByTrip_groups_completed_bids_per_trip_most_recent_first() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity older = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        older.setDepartureDate(LocalDate.now().minusDays(20));
        announcementRepository.save(older);
        AnnouncementEntity recent = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        recent.setDepartureDate(LocalDate.now().minusDays(2));
        announcementRepository.save(recent);
        persistBid(recent.getId(), BidStatus.COMPLETED, new BigDecimal("4.00"));
        persistBid(recent.getId(), BidStatus.COMPLETED, new BigDecimal("2.00"));
        persistBid(recent.getId(), BidStatus.CANCELLED, new BigDecimal("9.00"));
        persistBid(older.getId(), BidStatus.COMPLETED, new BigDecimal("3.00"));

        List<KgSoldTripRow> rows = bidRepository.findDeliveredKgByTrip(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).tripId()).isEqualTo(recent.getId());
        assertThat(rows.get(0).parcels()).isEqualTo(2);
        assertThat(rows.get(0).kg()).isEqualByComparingTo("6.00");
        assertThat(rows.get(1).tripId()).isEqualTo(older.getId());
        assertThat(rows.get(1).parcels()).isEqualTo(1);
        assertThat(rows.get(1).kg()).isEqualByComparingTo("3.00");
    }

    @Test
    void findDeliveredKgByTrip_defaults_kg_to_zero_when_weight_is_null() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann = persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        // weightKg nul : bid en mode grille (BidPricingMode.GRID), pas de poids saisi.
        persistBid(ann.getId(), BidStatus.COMPLETED, null);

        List<KgSoldTripRow> rows = bidRepository.findDeliveredKgByTrip(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).parcels()).isEqualTo(1);
        assertThat(rows.get(0).kg()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // Helpers (mirrored from TravelerStatsListenerIT / adapted)
    // -------------------------------------------------------------------------

    private UserEntity persistTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId, AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(status);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UUID announcementId, BidStatus status, BigDecimal weightKg) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(weightKg);
        bid.setStatus(status);
        return bidRepository.save(bid);
    }

    private BidEntity persistCashBid(UUID announcementId, BidStatus status, BigDecimal weightKg,
                                     String currency, String netAmount) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(weightKg);
        bid.setStatus(status);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCurrency(currency);
        bid.setNegotiatedNetEur(new BigDecimal(netAmount));
        return bidRepository.save(bid);
    }

    private PaymentEntity persistPayment(UUID bidId, UUID threadId, PaymentRail rail, String currency,
                                         String amount, String commission, PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(rail);
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        return paymentRepository.save(p);
    }

    private PackageRequestEntity persistPackageRequest(String from, String to, BigDecimal weightKg) {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(UUID.randomUUID());
        colis.setDepartureCity(from);
        colis.setArrivalCity(to);
        colis.setDesiredDate(LocalDate.now().plusDays(10));
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(weightKg);
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setStatus(PackageRequestStatus.OPEN);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        return packageRequestRepository.save(colis);
    }

    private NegotiationThreadEntity persistThread(UUID packageRequestId, UUID travelerId,
                                                  UUID travelerAnnouncementId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(travelerId);
        t.setTravelerAnnouncementId(travelerAnnouncementId);
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }
}
