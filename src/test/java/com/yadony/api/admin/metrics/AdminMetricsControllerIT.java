package com.yadony.api.admin.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.PaymentVolumeRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Premier test de la vue d'ensemble admin. Recette du 2026-09-09 : les volumes additionnaient
 * EUR, XOF et XAF dans un seul SUM et s'affichaient en euros. Le contrat sert désormais une
 * ventilation par devise en centièmes, et garde {@code gmv} (ligne EUR seule, en unités) pour
 * les back-offices pas encore mis à jour.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminMetricsControllerIT — /admin/metrics/overview")
class AdminMetricsControllerIT {

    /**
     * Horloge figée en 2030 : les fenêtres de période n'attrapent aucune donnée des autres tests,
     * et « aujourd'hui » ne dépend pas de l'heure du run (minuit Paris = 2030-01-14T23:00Z).
     * Posée sur le seul service, puis rendue : jamais un {@code Clock} global {@code @Primary},
     * qui remplacerait l'horloge des planificateurs du contexte Spring partagé.
     */
    static final Instant NOW = Instant.parse("2030-01-15T10:00:00Z");

    @Autowired AdminMetricsService metricsService;
    private Clock originalClock;

    @BeforeEach
    void fixClock() {
        originalClock = (Clock) ReflectionTestUtils.getField(metricsService, "clock");
        metricsService.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @MockitoBean PaymentRepository paymentRepository;

    private final List<UUID> seededUsers = new ArrayList<>();
    private final List<UUID> seededBids = new ArrayList<>();
    private final List<UUID> seededAnnouncements = new ArrayList<>();

    @AfterEach
    void cleanSeeded() {
        metricsService.setClock(originalClock);
        seededBids.forEach(id -> jdbc.update("DELETE FROM bids WHERE id = ?", id));
        seededAnnouncements.forEach(id -> jdbc.update("DELETE FROM announcements WHERE id = ?", id));
        seededUsers.forEach(id -> {
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        });
    }

    /**
     * Fixe les dates par Hibernate (meme liaison LocalDateTime que la requete du service) : un
     * UPDATE JDBC brut decalerait les valeurs du fuseau de la JVM (hibernate.jdbc.time_zone=UTC).
     */
    private void setDates(String table, UUID id, String column, String utc) {
        new TransactionTemplate(txManager).executeWithoutResult(t ->
                em.createNativeQuery("UPDATE " + table + " SET " + column + " = :v WHERE id = :id")
                        .setParameter("v", utc == null ? null : LocalDateTime.parse(utc))
                        .setParameter("id", id)
                        .executeUpdate());
    }

    /** Crée un utilisateur puis fixe ses dates en SQL (created_at n'est pas modifiable via JPA). */
    private UUID user(String createdAt, String deletedAt) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-period-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        u.setRoles(roles);
        UUID id = userRepository.save(u).getId();
        seededUsers.add(id);
        setDates("users", id, "created_at", createdAt);
        setDates("users", id, "updated_at", createdAt);
        if (deletedAt != null) {
            setDates("users", id, "deleted_at", deletedAt);
        }
        return id;
    }

    /** Bid aux dates fixées ; {@code cancelledAt} null : jamais annulé (ou avant V316). */
    private void bid(UUID announcementId, UUID senderId, BidStatus status, String cancelledAt, String updatedAt) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new java.math.BigDecimal("5.00"));
        bid.setStatus(status);
        UUID id = bidRepository.save(bid).getId();
        seededBids.add(id);
        if (cancelledAt != null) {
            setDates("bids", id, "cancelled_at", cancelledAt);
        }
        setDates("bids", id, "updated_at", updatedAt);
    }

    private UUID announcement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.of(2030, 2, 1));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new java.math.BigDecimal("48.860000"));
        a.setPickupLng(new java.math.BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new java.math.BigDecimal("14.693000"));
        a.setDeliveryLng(new java.math.BigDecimal("-17.447000"));
        a.setAvailableKg(new java.math.BigDecimal("10.00"));
        a.setTotalKg(new java.math.BigDecimal("10.00"));
        a.setPricePerKg(new java.math.BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        UUID id = announcementRepository.save(a).getId();
        seededAnnouncements.add(id);
        return id;
    }

    /**
     * Donnees (horloge figee 2030-01-15T10:00Z, minuit Paris = 2030-01-14T23:00Z) :
     * <ul>
     *   <li>A cree 15/01 00:30Z (aujourd'hui) ; B cree 14/01 22:30Z (hier, Paris) ; C cree 12/01 ;
     *       D cree 15/01 08:00Z puis supprime 15/01 09:00Z ; E ancien, supprime 14/01 22:30Z ;
     *       F cree 16/01 (apres « maintenant », exclu partout).</li>
     *   <li>Bids : 2 annules (15/01 07:00Z et 14/01 22:00Z), 1 en attente mis a jour aujourd'hui,
     *       1 annule le 01/11/2029 (hors de toute periode) mais modifie aujourd'hui : compte par
     *       {@code cancelled_at}, jamais par {@code updated_at}.</li>
     * </ul>
     */
    private void seedPeriodData() {
        UUID a = user("2030-01-15T00:30:00", null);
        user("2030-01-14T22:30:00", null);
        user("2030-01-12T10:00:00", null);
        user("2030-01-15T08:00:00", "2030-01-15T09:00:00");
        user("2029-01-01T10:00:00", "2030-01-14T22:30:00");
        user("2030-01-16T10:00:00", null);
        UUID ann = announcement(a);
        bid(ann, a, BidStatus.CANCELLED, "2030-01-15T07:00:00", "2030-01-15T07:00:00");
        bid(ann, a, BidStatus.CANCELLED, "2030-01-14T22:00:00", "2030-01-14T22:00:00");
        bid(ann, a, BidStatus.PENDING, null, "2030-01-15T07:30:00");
        bid(ann, a, BidStatus.CANCELLED, "2029-11-01T10:00:00", "2030-01-15T08:00:00");
    }

    private static UsernamePasswordAuthenticationToken auth(String... permissions) {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-metrics");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void overview_ventileLesVolumesParDevise_etGardeGmvEnEurosSeuls() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of(
                new PaymentVolumeRow("EUR", PaymentStatus.RELEASED,
                        new BigDecimal("100.00"), new BigDecimal("12.00"), BigDecimal.ZERO),
                new PaymentVolumeRow("XOF", PaymentStatus.ESCROW,
                        new BigDecimal("9900.00"), new BigDecimal("900.00"), BigDecimal.ZERO),
                new PaymentVolumeRow("XOF", PaymentStatus.RELEASED,
                        new BigDecimal("6600.00"), new BigDecimal("600.00"), BigDecimal.ZERO)));

        mockMvc.perform(get("/admin/metrics/overview").with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gmvByCurrency.length()").value(2))
                .andExpect(jsonPath("$.gmvByCurrency[0].currency").value("EUR"))
                .andExpect(jsonPath("$.gmvByCurrency[0].releasedCents").value(10000))
                .andExpect(jsonPath("$.gmvByCurrency[0].commissionCents").value(1200))
                .andExpect(jsonPath("$.gmvByCurrency[1].currency").value("XOF"))
                .andExpect(jsonPath("$.gmvByCurrency[1].escrowHeldCents").value(990000))
                .andExpect(jsonPath("$.gmvByCurrency[1].releasedCents").value(660000))
                .andExpect(jsonPath("$.gmvByCurrency[1].commissionCents").value(60000))
                .andExpect(jsonPath("$.gmvByCurrency[1].refundedCents").value(0))
                // Ancien contrat : la seule ligne EUR, en unités, jamais un mélange avec le XOF.
                .andExpect(jsonPath("$.gmv.released").value(100.0))
                .andExpect(jsonPath("$.gmv.commission").value(12.0))
                .andExpect(jsonPath("$.gmv.escrowHeld").value(0.0))
                // File KYC : demandes en revue, distinctes de pendingKyc (parcours en cours compris).
                .andExpect(jsonPath("$.queues.kycInReview").isNumber())
                .andExpect(jsonPath("$.queues.heldPayouts").isNumber())
                .andExpect(jsonPath("$.queues.pendingKyc").isNumber());
    }

    @Test
    void overview_sansMetricsView_est403_etNeLitAucunVolume() throws Exception {
        mockMvc.perform(get("/admin/metrics/overview").with(authentication(auth())))
                .andExpect(status().isForbidden());

        verify(paymentRepository, never()).sumVolumesByCurrencyAndStatus(any());
    }

    @Test
    void overview_withoutPeriod_keepsLegacyShape() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of());

        String body = mockMvc.perform(get("/admin/metrics/overview").with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        java.util.Set<String> keys = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).properties().stream()
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        org.assertj.core.api.Assertions.assertThat(keys).containsExactlyInAnyOrder(
                "users", "announcements", "bids", "gmv", "gmvByCurrency", "queues");
    }

    @Test
    void overview_period_TODAY_countsOnlySinceMidnightParis() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of());
        seedPeriodData();

        mockMvc.perform(get("/admin/metrics/overview").param("period", "TODAY")
                        .with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.name").value("TODAY"))
                // A et D seulement : B est d'hier a Paris (23h30), F est dans le futur.
                .andExpect(jsonPath("$.period.signups").value(2))
                .andExpect(jsonPath("$.period.deletedAccounts").value(1))
                .andExpect(jsonPath("$.period.parcelsCancelled").value(1))
                // Meme definition que queues.pendingKyc : utilisateurs en kyc_status PENDING.
                .andExpect(jsonPath("$.period.kycInProgress").isNumber());
    }

    @Test
    void overview_period_countsDeletedAccountsAndCancelledBids() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of());
        seedPeriodData();

        mockMvc.perform(get("/admin/metrics/overview").param("period", "D7")
                        .with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.name").value("D7"))
                .andExpect(jsonPath("$.period.signups").value(4))
                .andExpect(jsonPath("$.period.deletedAccounts").value(2))
                .andExpect(jsonPath("$.period.parcelsCancelled").value(2));

        mockMvc.perform(get("/admin/metrics/overview").param("period", "D30")
                        .with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.name").value("D30"))
                .andExpect(jsonPath("$.period.signups").value(4))
                .andExpect(jsonPath("$.period.deletedAccounts").value(2))
                .andExpect(jsonPath("$.period.parcelsCancelled").value(2));
    }

    @Test
    void overview_period_kycInProgress_reusesPendingKycDefinition() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of());
        String body = mockMvc.perform(get("/admin/metrics/overview").param("period", "D7")
                        .with(authentication(auth("METRICS_VIEW"))))
                .andReturn().getResponse().getContentAsString();
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        org.assertj.core.api.Assertions.assertThat(tree.at("/period/kycInProgress").asLong())
                .isEqualTo(tree.at("/queues/pendingKyc").asLong());
    }

    @Test
    void overview_unknownPeriod_returns400ProblemDetail() throws Exception {
        mockMvc.perform(get("/admin/metrics/overview").param("period", "YEAR")
                        .with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
