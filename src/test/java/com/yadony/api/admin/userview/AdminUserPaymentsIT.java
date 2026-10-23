package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Paiements d'un utilisateur et argent retenu par devise sur la fiche admin. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserPaymentsIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        traveler = saveUser();
        sender = saveUser();
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith("USER_VIEW", "BID_VIEW", "PAYMENT_VIEW");
    }

    private AnnouncementEntity announcement(UserEntity owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.STRIPE)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity from) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(from.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8));
        return bidRepository.save(b);
    }

    private PaymentEntity payment(UUID bidId, UUID threadId, String currency, String amount,
                                  PaymentStatus status, boolean held) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(BigDecimal.ZERO);
        p.setStatus(status);
        if (held) p.setPayoutHeldAt(LocalDateTime.now(ZoneOffset.UTC));
        return paymentRepository.save(p);
    }

    private PaymentEntity heldPaymentOf(UserEntity trav, UserEntity from, String currency, String amount) {
        return payment(bid(announcement(trav), from).getId(), null, currency, amount, PaymentStatus.ESCROW, true);
    }

    private NegotiationThreadEntity thread(UserEntity from, UserEntity trav) {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(from.getId());
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
        t.setTravelerId(trav.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }

    private ResultActions overview(UserEntity u, UsernamePasswordAuthenticationToken auth) throws Exception {
        return mockMvc.perform(get("/admin/users/{id}/overview", u.getId()).with(authentication(auth)));
    }

    @Test
    void payments_returnsOnlyPaymentsWhereUserIsAPartyViaBidOrThread() throws Exception {
        PaymentEntity viaBid = payment(bid(announcement(traveler), sender).getId(), null, "EUR", "50.00",
                PaymentStatus.RELEASED, false);
        PaymentEntity viaThread = payment(null, thread(traveler, sender).getId(), "EUR", "20.00",
                PaymentStatus.PENDING, false);
        UserEntity other = saveUser();
        payment(bid(announcement(other), saveUser()).getId(), null, "EUR", "99.00", PaymentStatus.ESCROW, false);

        mockMvc.perform(get("/admin/users/{id}/payments", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id=='" + viaBid.getId() + "')].role").value("BENEFICIARY"))
                .andExpect(jsonPath("$.content[?(@.id=='" + viaThread.getId() + "')].role").value("PAYER"))
                .andExpect(jsonPath("$.content[?(@.id=='" + viaBid.getId() + "')].amountCents").value(5000));

        mockMvc.perform(get("/admin/users/{id}/payments", sender.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id=='" + viaBid.getId() + "')].role").value("PAYER"))
                .andExpect(jsonPath("$.content[?(@.id=='" + viaThread.getId() + "')].role").value("BENEFICIARY"));
    }

    /** Valeur JDBC brute qu'Hibernate (jdbc.time_zone=UTC) relit comme {@code utc}, quel que soit le fuseau de la JVM. */
    private static LocalDateTime shifted(LocalDateTime utc) {
        return utc.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Test
    void payments_datesAreInstantsWithTrailingZ() throws Exception {
        PaymentEntity held = payment(bid(announcement(traveler), sender).getId(), null, "EUR", "30.00",
                PaymentStatus.ESCROW, true);
        jdbc.update("UPDATE payments SET created_at = ?, payout_held_at = ? WHERE id = ?",
                shifted(LocalDateTime.of(2026, 10, 20, 8, 30, 0)), shifted(LocalDateTime.of(2026, 10, 21, 9, 15, 0)),
                held.getId());

        mockMvc.perform(get("/admin/users/{id}/payments", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-20T08:30:00Z"))
                .andExpect(jsonPath("$.content[0].payoutHeldAt").value("2026-10-21T09:15:00Z"));
    }

    @Test
    void payments_xofAmountIsNominalCentsLikeAdminPaymentsList() throws Exception {
        PaymentEntity xof = payment(bid(announcement(traveler), sender).getId(), null, "XOF", "6000",
                PaymentStatus.RELEASED, false);

        mockMvc.perform(get("/admin/users/{id}/payments", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amountCents").value(600000));
        mockMvc.perform(get("/admin/payments").param("q", xof.getId().toString())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amountCents").value(600000));
    }

    @Test
    void overview_heldByCurrency_countsHeldPaymentLinkedOnlyThroughThread() throws Exception {
        payment(null, thread(sender, traveler).getId(), "EUR", "12.00", PaymentStatus.ESCROW, true);

        overview(traveler, fullAuth())
                .andExpect(jsonPath("$.kpis.heldByCurrency.length()").value(1))
                .andExpect(jsonPath("$.kpis.heldByCurrency[0].amountCents").value(1200));
        overview(sender, fullAuth())
                .andExpect(jsonPath("$.kpis.heldByCurrency.length()").value(0));
    }

    @Test
    void payments_isPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            payment(bid(announcement(traveler), sender).getId(), null, "EUR", "10.00", PaymentStatus.RELEASED, false);
        }
        mockMvc.perform(get("/admin/users/{id}/payments?page=1&size=2", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/admin/users/{id}/payments?size=100000", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void overview_heldByCurrency_groupsPerCurrencyNeverSums() throws Exception {
        heldPaymentOf(traveler, sender, "EUR", "10.50");
        heldPaymentOf(traveler, sender, "EUR", "4.50");
        heldPaymentOf(traveler, sender, "XOF", "6000");

        overview(traveler, fullAuth())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.heldByCurrency.length()").value(2))
                .andExpect(jsonPath("$.kpis.heldByCurrency[?(@.currency=='EUR')].amountCents").value(1500))
                .andExpect(jsonPath("$.kpis.heldByCurrency[?(@.currency=='XOF')].amountCents").value(600000));
    }

    @Test
    void overview_heldByCurrency_ignoresNonHeldAndNonBeneficiary() throws Exception {
        payment(bid(announcement(traveler), sender).getId(), null, "EUR", "10.00", PaymentStatus.ESCROW, false);
        payment(bid(announcement(traveler), sender).getId(), null, "EUR", "20.00", PaymentStatus.RELEASED, true);
        heldPaymentOf(saveUser(), traveler, "EUR", "30.00");

        overview(traveler, fullAuth())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.heldByCurrency.length()").value(0));
    }

    @Test
    void overview_heldByCurrency_emptyForNewUser() throws Exception {
        overview(saveUser(), fullAuth())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.heldByCurrency").isArray())
                .andExpect(jsonPath("$.kpis.heldByCurrency.length()").value(0));
    }

    @Test
    void payments_withoutPaymentView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/payments", traveler.getId())
                        .with(authentication(authWith("USER_VIEW", "BID_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_withoutPaymentView_omitsHeldByCurrency() throws Exception {
        heldPaymentOf(traveler, sender, "EUR", "10.00");
        overview(traveler, authWith("USER_VIEW", "BID_VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.heldByCurrency").doesNotExist());
    }
}
