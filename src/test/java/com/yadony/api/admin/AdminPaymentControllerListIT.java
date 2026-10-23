package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
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
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.chargeback.ChargebackStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lignes de la liste des paiements : étiquette d'alerte exclusive, n° de suivi (colis direct ou lié au fil),
 * annonce, et contrat historique inchangé. Chaque test se restreint à ses paiements par le pseudo de son expéditeur.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentControllerListIT {

    @Autowired AdminPaymentInsights insights;
    @Autowired MockMvc mockMvc;
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
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        tag = "l" + UUID.randomUUID().toString().substring(0, 8);
        sender = persistUser(tag + "snd", "Awa", "Diallo");
        traveler = persistUser(tag + "trv", "Moussa", "Keita");
        trip = persistAnnouncement(traveler.getId());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private org.springframework.test.web.servlet.ResultActions list(String... extra) throws Exception {
        var req = get("/admin/payments").with(authentication(paymentViewer())).param("q", tag + "snd");
        for (int i = 0; i < extra.length; i += 2) {
            req = req.param(extra[i], extra[i + 1]);
        }
        return mockMvc.perform(req);
    }

    @Test
    void alert_exclusiveRank_heldWinsOverLateEscrow_absentWithoutAlert() throws Exception {
        PaymentEntity both = paymentOn(persistBid(trip.getId(), null, "DON-" + tag.substring(1, 7) + "A").getId(), null,
                PaymentStatus.ESCROW, "10.00", now().minusDays(3));
        hold(both);
        PaymentEntity calm = paymentOn(persistBid(trip.getId(), null, null).getId(), null,
                PaymentStatus.ESCROW, "10.00", now().minusHours(1));

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id=='" + calm.getId() + "')].alert").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id=='" + both.getId() + "')].alert").value("HELD"));
        list().andExpect(jsonPath("$.content[0].id").value(calm.getId().toString()))
                .andExpect(jsonPath("$.content[0].alert").doesNotExist());
    }

    @Test
    void trackingNumber_directBid_linkedBid_absentWithoutParcel() throws Exception {
        String direct = "DON-" + tag.substring(1, 7) + "D";
        String linkedNo = "DON-" + tag.substring(1, 7) + "L";
        PaymentEntity pd = paymentOn(persistBid(trip.getId(), null, direct).getId(), null,
                PaymentStatus.ESCROW, "10.00", now().minusHours(3));
        NegotiationThreadEntity linkedThread = persistThread();
        persistBid(trip.getId(), linkedThread.getId(), linkedNo);
        PaymentEntity pl = paymentOn(null, linkedThread.getId(), PaymentStatus.ESCROW, "10.00", now().minusHours(2));
        PaymentEntity pn = paymentOn(null, persistThread().getId(), PaymentStatus.ESCROW, "10.00", now().minusHours(1));

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id=='" + pd.getId() + "')].trackingNumber").value(direct))
                .andExpect(jsonPath("$.content[?(@.id=='" + pd.getId() + "')].announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.content[?(@.id=='" + pl.getId() + "')].trackingNumber").value(linkedNo))
                .andExpect(jsonPath("$.content[?(@.id=='" + pl.getId() + "')].announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.content[?(@.id=='" + pn.getId() + "')].trackingNumber").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id=='" + pn.getId() + "')].announcementId").isEmpty());
        // Absent (pas null explicite) pour la négociation sans colis : première ligne = la plus récente.
        list().andExpect(jsonPath("$.content[0].id").value(pn.getId().toString()))
                .andExpect(jsonPath("$.content[0].trackingNumber").doesNotExist())
                .andExpect(jsonPath("$.content[0].announcementId").doesNotExist());
    }

    @Test
    void attentionView_ordersByUrgency() throws Exception {
        PaymentEntity late = paymentOn(persistBid(trip.getId(), null, null).getId(), null,
                PaymentStatus.ESCROW, "10.00", now().minusDays(3));
        PaymentEntity held = paymentOn(persistBid(trip.getId(), null, null).getId(), null,
                PaymentStatus.ESCROW, "10.00", now().minusHours(1));
        hold(held);

        list("view", "ATTENTION").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(held.getId().toString()))
                .andExpect(jsonPath("$.content[0].alert").value("HELD"))
                .andExpect(jsonPath("$.content[1].id").value(late.getId().toString()))
                .andExpect(jsonPath("$.content[1].alert").value("ESCROW_J48"));
    }

    @Test
    void withoutView_behavesAsAll_andLegacyFieldsUnchanged() throws Exception {
        PaymentEntity refunded = paymentOn(persistBid(trip.getId(), null, null).getId(), null,
                PaymentStatus.REFUNDED, "10.00", now().minusHours(1));

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(refunded.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.content[0].amountCents").value(1000))
                .andExpect(jsonPath("$.content[0].beneficiaryHeld").value(false))
                .andExpect(jsonPath("$.content[0].insight.kind").value("BID"))
                .andExpect(jsonPath("$.content[0].insight.sender.id").value(sender.getId().toString()))
                .andExpect(jsonPath("$.content[0].insight.departureCity").value("Paris"));
    }

    @Test
    void unknownView_400_andNoPermission_403() throws Exception {
        list("view", "nimporte").andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/payments").with(authentication(new UsernamePasswordAuthenticationToken(
                        new AdminPrincipal(UUID.randomUUID(), "n@yadony.test", AdminRole.SUPPORT, false, "uid-n"), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void abandoned_followsInjectedNow() {
        PaymentEntity pending = paymentOn(persistBid(trip.getId(), null, null).getId(), null,
                PaymentStatus.PENDING, "10.00", now().minusHours(1));
        assertThat(insights.insightsOf(List.of(pending), now()).get(pending.getId()).abandoned()).isFalse();
        assertThat(insights.insightsOf(List.of(pending), now().plusDays(2)).get(pending.getId()).abandoned()).isTrue();
    }

    // ── Données ─────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken paymentViewer() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "f@yadony.test", AdminRole.SUPPORT, false, "uid-f"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(AdminPermission.PAYMENT_VIEW.name())));
    }

    // ── Données ─────────────────────────────────────────────────────────────────

    private PaymentEntity paymentOn(UUID bidId, UUID threadId, PaymentStatus status, String amount,
                                    LocalDateTime createdAt) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(amount).divide(BigDecimal.TEN));
        p.setStatus(status);
        p = paymentRepository.save(p);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), p.getId());
        return p;
    }

    private void hold(PaymentEntity p) {
        jdbc.update("UPDATE payments SET payout_held_at = ? WHERE id = ?", Timestamp.valueOf(now()), p.getId());
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

    private BidEntity persistBid(UUID announcementId, UUID linkedThreadId, String trackingNumber) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        bid.setTrackingNumber(trackingNumber);
        return bidRepository.save(bid);
    }

    private NegotiationThreadEntity persistThread() {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(sender.getId());
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
        t.setTravelerId(traveler.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setCurrency("EUR");
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }
}
