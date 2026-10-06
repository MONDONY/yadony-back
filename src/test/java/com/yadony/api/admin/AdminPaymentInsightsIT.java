package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPaymentInsight;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le SQL de {@link AdminPaymentInsights} contre le schéma réel : colis classique, paiement de
 * négociation sans colis, puis avec colis lié, recherche, checkout abandonné, totaux.
 * Chaque test a son propre pseudo d'expéditeur : la recherche par nom isole ses données.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminPaymentInsightsIT {

    @Autowired AdminPaymentInsights insights;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        tag = "t" + UUID.randomUUID().toString().substring(0, 8);
        sender = persistUser(tag + "snd", "Awa", "Diallo");
        traveler = persistUser(tag + "trv", "Moussa", "Keita");
    }

    @Test
    void colisClassique_partiesTrajetEtLienStripe() {
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        BidEntity bid = persistBid(ann.getId(), sender.getId(), null);
        PaymentEntity p = persistPayment(bid.getId(), null, "EUR", "50.00", "5.00", PaymentStatus.ESCROW);

        AdminPaymentInsight i = insights.insightOf(p);

        assertThat(i.kind()).isEqualTo("BID");
        assertThat(i.bidId()).isEqualTo(bid.getId());
        assertThat(i.sender()).isEqualTo(new AdminPaymentInsight.Party(sender.getId(), "Awa Diallo (@" + tag + "snd)"));
        assertThat(i.traveler().id()).isEqualTo(traveler.getId());
        assertThat(i.departureCity()).isEqualTo("Paris");
        assertThat(i.arrivalCity()).isEqualTo("Dakar");
        assertThat(i.bidStatus()).isEqualTo("ACCEPTED");
        assertThat(i.netTravelerCents()).isEqualTo(4500L);
        assertThat(i.abandoned()).isFalse();
        assertThat(i.stripeDashboardUrl()).isEqualTo("https://dashboard.stripe.com/test/payments/" + p.getStripePaymentIntentId());
    }

    @Test
    void negociationSansColis_partiesDuFilEtTrajetDeLaDemande() {
        NegotiationThreadEntity thread = persistThread(sender.getId(), traveler.getId());
        PaymentEntity p = persistPayment(null, thread.getId(), "EUR", "20.00", "0.95", PaymentStatus.PENDING);

        AdminPaymentInsight i = insights.insightOf(p);

        assertThat(i.kind()).isEqualTo("NEGOTIATION");
        assertThat(i.bidId()).isNull();
        assertThat(i.negotiationThreadId()).isEqualTo(thread.getId());
        assertThat(i.sender().id()).isEqualTo(sender.getId());
        assertThat(i.traveler().id()).isEqualTo(traveler.getId());
        assertThat(i.departureCity()).isEqualTo("Lyon");
        assertThat(i.arrivalCity()).isEqualTo("Abidjan");
    }

    @Test
    void negociationAvecColisLie_leColisEstResoluEtTrouvableParSonId() {
        NegotiationThreadEntity thread = persistThread(sender.getId(), traveler.getId());
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        BidEntity linked = persistBid(ann.getId(), sender.getId(), thread.getId());
        PaymentEntity p = persistPayment(null, thread.getId(), "EUR", "20.00", "0.95", PaymentStatus.ESCROW);

        assertThat(insights.insightOf(p).bidId()).isEqualTo(linked.getId());
        assertThat(search(linked.getId().toString(), false)).containsExactly(p.getId());
        assertThat(search(thread.getId().toString(), false)).containsExactly(p.getId());
        assertThat(search(p.getId().toString(), false)).containsExactly(p.getId());
    }

    @Test
    void recherche_parNomPseudoOuReferenceStripe() {
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        PaymentEntity classic = persistPayment(persistBid(ann.getId(), sender.getId(), null).getId(), null,
                "EUR", "10.00", "1.00", PaymentStatus.RELEASED);
        PaymentEntity nego = persistPayment(null, persistThread(sender.getId(), traveler.getId()).getId(),
                "EUR", "20.00", "2.00", PaymentStatus.ESCROW);

        assertThat(search("@" + tag + "SND", false)).containsExactlyInAnyOrder(classic.getId(), nego.getId());
        assertThat(search(tag + "trv", false)).containsExactlyInAnyOrder(classic.getId(), nego.getId());
        assertThat(search(classic.getStripePaymentIntentId(), false)).containsExactly(classic.getId());
        assertThat(search(tag + "inconnu", false)).isEmpty();
    }

    @Test
    void checkoutAbandonne_marqueEtMasquable() {
        NegotiationThreadEntity thread = persistThread(sender.getId(), traveler.getId());
        PaymentEntity old = persistPayment(null, thread.getId(), "EUR", "20.00", "0.95", PaymentStatus.PENDING);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), old.getId());
        PaymentEntity fresh = persistPayment(null, persistThread(sender.getId(), traveler.getId()).getId(),
                "EUR", "20.00", "0.95", PaymentStatus.PENDING);

        PaymentEntity reloaded = paymentRepository.findById(old.getId()).orElseThrow();
        assertThat(insights.insightOf(reloaded).abandoned()).isTrue();
        assertThat(insights.insightOf(fresh).abandoned()).isFalse();
        assertThat(search(tag + "snd", false)).containsExactlyInAnyOrder(old.getId(), fresh.getId());
        assertThat(search(tag + "snd", true)).containsExactly(fresh.getId());
    }

    @Test
    void totaux_parDevise() {
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        persistPayment(persistBid(ann.getId(), sender.getId(), null).getId(), null, "EUR", "50.00", "5.00", PaymentStatus.ESCROW);
        PaymentEntity released = persistPayment(persistBid(ann.getId(), sender.getId(), null).getId(), null,
                "EUR", "30.00", "3.00", PaymentStatus.RELEASED);
        released.setRefundedAmount(new BigDecimal("4.00"));
        paymentRepository.save(released);
        persistPayment(persistBid(ann.getId(), sender.getId(), null).getId(), null, "XOF", "6600.00", "600.00", PaymentStatus.PENDING);

        List<AdminPaymentInsights.CurrencyTotals> totals =
                insights.totals(AdminPaymentFilter.of(null, null, null, null, null, null, tag + "snd", null));

        assertThat(totals).containsExactly(
                new AdminPaymentInsights.CurrencyTotals("EUR", 2, 5000, 3000, 400, 800, 0),
                new AdminPaymentInsights.CurrencyTotals("XOF", 1, 0, 0, 0, 0, 1));
        assertThat(insights.exportRows(AdminPaymentFilter.of(null, null, null, null, "eur", null, tag + "snd", null)))
                .hasSize(2);
    }

    private List<UUID> search(String q, boolean hideAbandoned) {
        return insights.search(AdminPaymentFilter.of(null, null, null, null, null, null, q, hideAbandoned),
                PageRequest.of(0, 50)).getContent().stream().map(PaymentEntity::getId).toList();
    }

    private UserEntity persistUser(String username, String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
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
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UUID announcementId, UUID senderId, UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        return bidRepository.save(bid);
    }

    private PaymentEntity persistPayment(UUID bidId, UUID threadId, String currency, String amount,
                                         String commission, PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        return paymentRepository.save(p);
    }

    private NegotiationThreadEntity persistThread(UUID senderId, UUID travelerId) {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(senderId);
        colis.setDepartureCity("Lyon");
        colis.setArrivalCity("Abidjan");
        colis.setDesiredDate(LocalDate.now().plusDays(10));
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(new BigDecimal("2.50"));
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setStatus(PackageRequestStatus.OPEN);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        packageRequestRepository.save(colis);

        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(colis.getId());
        t.setTravelerId(travelerId);
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
