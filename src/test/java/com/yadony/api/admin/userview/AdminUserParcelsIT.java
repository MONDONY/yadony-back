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
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Colis, trajets et bloc « en cours / livrés depuis 3 jours » de la fiche utilisateur. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserParcelsIT {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.yadony.api.matching.BidGridItemRepository gridItemRepository;
    @Autowired AdminUserOverviewService overviewService;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        overviewService.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        traveler = saveUser();
        sender = saveUser();
    }

    @AfterEach
    void tearDown() {
        overviewService.setClock(Clock.systemUTC());
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(AdminRole role, String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", role, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith(AdminRole.ADMIN, "USER_VIEW", "BID_VIEW", "PAYMENT_VIEW", "DISPUTE_VIEW", "REPORT_VIEW");
    }

    private AnnouncementEntity announcement(UserEntity owner, AnnouncementStatus status, Instant updatedAt) {
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
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.CASH)));
        a.setStatus(status);
        a = announcementRepository.save(a);
        if (updatedAt != null) {
            jdbc.update("update announcements set updated_at = ? where id = ?", ts(updatedAt), a.getId());
        }
        return a;
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity from, BidStatus status,
                          Instant deliveredAt, Instant updatedAt) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(from.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8));
        b = bidRepository.save(b);
        if (deliveredAt != null) {
            jdbc.update("update bids set delivered_at = ? where id = ?", ts(deliveredAt), b.getId());
        }
        if (updatedAt != null) {
            jdbc.update("update bids set updated_at = ? where id = ?", ts(updatedAt), b.getId());
        }
        return b;
    }

    /**
     * Valeur brute à écrire en JDBC pour que Hibernate (jdbc.time_zone=UTC) relise l'instant
     * {@code i} en LocalDateTime UTC, quel que soit le fuseau de la JVM de test.
     */
    private static LocalDateTime ts(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC).atZone(java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private org.springframework.test.web.servlet.ResultActions overview(UUID userId) throws Exception {
        return mockMvc.perform(get("/admin/users/{id}/overview", userId)
                .with(authentication(fullAuth())));
    }

    @Test
    void parcels_listsBothRolesWithRole() throws Exception {
        AnnouncementEntity tripOfTraveler = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        AnnouncementEntity tripOfOther = announcement(sender, AnnouncementStatus.ACTIVE, null);
        bid(tripOfTraveler, sender, BidStatus.PENDING, null, null);        // traveler = TRAVELER
        BidEntity asSender = bid(tripOfOther, traveler, BidStatus.ACCEPTED, null, null); // traveler = SENDER

        mockMvc.perform(get("/admin/users/{id}/parcels", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id=='" + asSender.getId() + "')].role").value("SENDER"))
                .andExpect(jsonPath("$.content[?(@.role=='TRAVELER')].corridor").isNotEmpty());
    }

    @Test
    void parcels_isPaginatedAndSizeCapped() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        for (int i = 0; i < 5; i++) bid(trip, sender, BidStatus.PENDING, null, null);

        mockMvc.perform(get("/admin/users/{id}/parcels?page=1&size=2", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.number").value(1));

        mockMvc.perform(get("/admin/users/{id}/parcels?size=100000", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void overview_inProgress_includesActiveAndDeliveredWithin72h() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.ACTIVE, NOW.minusSeconds(60));
        bid(trip, sender, BidStatus.IN_TRANSIT, null, NOW.minusSeconds(30));
        bid(trip, sender, BidStatus.COMPLETED, NOW.minus(Duration.ofHours(10)), NOW.minus(Duration.ofHours(10)));

        overview(traveler.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress.length()").value(3))
                .andExpect(jsonPath("$.inProgress[?(@.kind=='TRIP')].status").value("ACTIVE"))
                .andExpect(jsonPath("$.inProgress[?(@.status=='COMPLETED')].kind").value("PARCEL"));
    }

    @Test
    void overview_inProgress_excludesDeliveredOlderThan72h() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.CANCELLED, null);
        bid(trip, sender, BidStatus.COMPLETED, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)));

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress").isEmpty());
    }

    @Test
    void overview_inProgress_deliveredJustInsideAndJustOutsideBoundary() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.CANCELLED, null);
        Instant inside = NOW.minus(Duration.ofHours(71).plusMinutes(59));
        Instant outside = NOW.minus(Duration.ofHours(72).plusMinutes(1));
        BidEntity in = bid(trip, sender, BidStatus.COMPLETED, inside, inside);
        bid(trip, sender, BidStatus.COMPLETED, outside, outside);

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress.length()").value(1))
                .andExpect(jsonPath("$.inProgress[0].id").value(in.getId().toString()));
    }

    @Test
    void overview_inProgress_neverDeliveredCompletedParcelIsNotRecent() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.CANCELLED, null);
        bid(trip, sender, BidStatus.COMPLETED, null, NOW.minusSeconds(5));

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress").isEmpty());
    }

    @Test
    void overview_inProgress_hasNoDuplicateForSameBid() throws Exception {
        // Le voyageur est aussi l'expéditeur du même colis (cas limite) : une seule ligne.
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.CANCELLED, null);
        bid(trip, traveler, BidStatus.ACCEPTED, null, NOW.minusSeconds(5));

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress.length()").value(1))
                .andExpect(jsonPath("$.kpis.parcelsInProgress").value(1));
    }

    @Test
    void overview_inProgress_isCappedAt50MostRecent() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.CANCELLED, null);
        BidEntity newest = null;
        for (int i = 0; i < 60; i++) {
            BidEntity b = bid(trip, sender, BidStatus.IN_TRANSIT, null, NOW.minusSeconds(1000 - i));
            newest = b;
        }

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress.length()").value(50))
                .andExpect(jsonPath("$.inProgress[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.kpis.parcelsInProgress").value(60));
    }

    @Test
    void overview_kpis_countParcelsAndTrips() throws Exception {
        AnnouncementEntity active = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        announcement(traveler, AnnouncementStatus.FULL, null);
        announcement(traveler, AnnouncementStatus.IN_PROGRESS, null);
        announcement(traveler, AnnouncementStatus.DRAFT, null);
        announcement(traveler, AnnouncementStatus.CANCELLED, null);
        bid(active, sender, BidStatus.PENDING, null, null);
        bid(active, sender, BidStatus.ARRIVED, null, null);
        bid(active, sender, BidStatus.NEGOTIATING, null, null);
        bid(active, sender, BidStatus.REJECTED, null, null);

        overview(traveler.getId())
                .andExpect(jsonPath("$.kpis.parcelsInProgress").value(2))
                .andExpect(jsonPath("$.kpis.activeTrips").value(3));
    }

    @Test
    void parcels_withoutBidView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/parcels", traveler.getId())
                        .with(authentication(authWith(AdminRole.SUPPORT, "USER_VIEW"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users/{id}/trips", traveler.getId())
                        .with(authentication(authWith(AdminRole.SUPPORT, "USER_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_withoutBidView_omitsParcelAndTripFields() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        bid(trip, sender, BidStatus.PENDING, null, null);

        mockMvc.perform(get("/admin/users/{id}/overview", traveler.getId())
                        .with(authentication(authWith(AdminRole.SUPPORT, "USER_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress").doesNotExist())
                .andExpect(jsonPath("$.kpis.parcelsInProgress").doesNotExist())
                .andExpect(jsonPath("$.kpis.activeTrips").doesNotExist());
    }

    @Test
    void trips_returnsTravelerAnnouncements() throws Exception {
        AnnouncementEntity a = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        announcement(sender, AnnouncementStatus.ACTIVE, null);

        mockMvc.perform(get("/admin/users/{id}/trips", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a.getId().toString()))
                .andExpect(jsonPath("$.content[0].corridor").isNotEmpty());
    }

    @Test
    void parcels_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/parcels", UUID.randomUUID())
                        .with(authentication(fullAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }


    @Test
    void overview_inProgress_completedTripInsideWindowAppears() throws Exception {
        AnnouncementEntity inside = announcement(traveler, AnnouncementStatus.COMPLETED,
                NOW.minus(Duration.ofHours(71).plusMinutes(59)));
        announcement(traveler, AnnouncementStatus.COMPLETED, NOW.minus(Duration.ofHours(72).plusMinutes(1)));

        overview(traveler.getId())
                .andExpect(jsonPath("$.inProgress.length()").value(1))
                .andExpect(jsonPath("$.inProgress[0].kind").value("TRIP"))
                .andExpect(jsonPath("$.inProgress[0].id").value(inside.getId().toString()))
                .andExpect(jsonPath("$.kpis.activeTrips").value(0));
    }

    @Test
    void overview_inProgress_completedTripOutsideWindowIsExcluded() throws Exception {
        announcement(traveler, AnnouncementStatus.COMPLETED, NOW.minus(Duration.ofDays(9)));

        overview(traveler.getId()).andExpect(jsonPath("$.inProgress").isEmpty());
    }

    @Test
    void parcels_amount_negotiatedNetAndPerKgPlusGrid() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        BidEntity negotiated = bid(trip, sender, BidStatus.PENDING, null, null);
        jdbc.update("update bids set negotiated_net_eur = ? where id = ?", new BigDecimal("12.34"), negotiated.getId());
        BidEntity perKg = bid(trip, sender, BidStatus.ACCEPTED, null, null); // 3 kg x 8.00 = 24.00
        com.yadony.api.matching.BidGridItemEntity item = new com.yadony.api.matching.BidGridItemEntity();
        item.setBidId(perKg.getId());
        item.setAnnouncementGridItemId(UUID.randomUUID());
        item.setLabelSnapshot("Sac");
        item.setUnitPriceNetSnapshot(new BigDecimal("1.50"));
        item.setQuantity(2);
        gridItemRepository.save(item);

        mockMvc.perform(get("/admin/users/{id}/parcels", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + negotiated.getId() + "')].amount.amountCents").value(1234))
                .andExpect(jsonPath("$.content[?(@.id=='" + negotiated.getId() + "')].amount.currency").value("EUR"))
                // 24.00 + 2 x 1.50 = 27.00 -> 2700
                .andExpect(jsonPath("$.content[?(@.id=='" + perKg.getId() + "')].amount.amountCents").value(2700));
    }

    @Test
    void parcels_amount_xofHasNoMinorUnit() throws Exception {
        AnnouncementEntity trip = announcement(traveler, AnnouncementStatus.ACTIVE, null);
        BidEntity b = bid(trip, sender, BidStatus.PENDING, null, null);
        jdbc.update("update bids set currency = 'XOF', negotiated_net_eur = ? where id = ?",
                new BigDecimal("5000"), b.getId());

        mockMvc.perform(get("/admin/users/{id}/parcels", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content[0].amount.currency").value("XOF"))
                .andExpect(jsonPath("$.content[0].amount.amountCents").value(5000));
    }

    @Test
    void trips_andOverview_unknownUser_return404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/admin/users/{id}/trips", unknown).with(authentication(fullAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
        mockMvc.perform(get("/admin/users/{id}/overview", unknown).with(authentication(fullAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }
}
