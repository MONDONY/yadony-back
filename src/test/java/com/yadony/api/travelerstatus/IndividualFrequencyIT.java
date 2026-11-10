package com.yadony.api.travelerstatus;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.gppro.GpProApplicationEntity;
import com.yadony.api.gppro.GpProApplicationRepository;
import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 4 : détection des « faux particuliers » sur des données réelles (H2), contrôle quotidien et file admin.
 * Chaque test crée ses propres utilisateurs et ne regarde que ceux-là : la base est partagée avec les autres tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class IndividualFrequencyIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final int THRESHOLD = 4;

    @Autowired IndividualFrequencyEvaluator evaluator;
    @Autowired IndividualFrequencyScheduler scheduler;
    @Autowired IndividualFrequencyFlagRepository flags;
    @Autowired UserRepository users;
    @Autowired AnnouncementRepository announcements;
    @Autowired BidRepository bids;
    @Autowired GpProApplicationRepository applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired MockMvc mockMvc;
    /** Même jeu de doublures que AdminUserMonthlyTripLimitIT : le contexte Spring est partagé. */
    @MockBean FirebaseContactService firebaseContact;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        when(firebaseContact.getContact(any())).thenReturn(FirebaseContactService.Contact.EMPTY);
        flags.deleteAll();
        now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    // ── Données ──────────────────────────────────────────────────────────────

    private UserEntity user(TravelerStatus status, boolean gpPro) {
        UserEntity u = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setFirebaseUid("uid-freq-" + suffix);
        u.setUsername("freq-" + suffix);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        u.setTravelerStatus(status);
        u.setGpPro(gpPro);
        if (status == TravelerStatus.PROFESSIONAL) {
            u.setBusinessName("Transports Diallo");
            u.setBusinessRegistrationNumber("123456789");
            u.setBusinessCountry("FR");
        }
        return users.saveAndFlush(u);
    }

    private UserEntity sender() {
        return user(null, false);
    }

    private UUID trip(UserEntity traveler, AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(20));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(status);
        return announcements.saveAndFlush(a).getId();
    }

    private UUID bid(UUID tripId, UserEntity sender, BidStatus status, LocalDateTime createdAt) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(tripId);
        b.setSenderId(sender.getId());
        b.setStatus(status);
        b.setWeightKg(new BigDecimal("2.00"));
        UUID id = bids.saveAndFlush(b).getId();
        backdate("bids", id, createdAt);
        return id;
    }

    /**
     * Antidate une ligne en passant par Hibernate, qui convertit les dates avec {@code hibernate.jdbc.time_zone=UTC}
     * comme pour les écritures de l'application. Un JdbcTemplate écrirait dans le fuseau de la JVM : décalage d'une
     * heure ou deux, fatal à un test de frontière à la minute.
     */
    private void backdate(String table, UUID id, LocalDateTime createdAt) {
        tx.executeWithoutResult(s -> entityManager
                .createNativeQuery("UPDATE " + table + " SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate());
    }

    /** {@code count} trajets distincts, chacun avec une réservation acceptée créée il y a {@code daysAgo} jours. */
    private void acceptedTrips(UserEntity traveler, int count, int daysAgo) {
        UserEntity s = sender();
        for (int i = 0; i < count; i++) {
            bid(trip(traveler, AnnouncementStatus.ACTIVE), s, BidStatus.ACCEPTED, now.minusDays(daysAgo));
        }
    }

    private Map<UUID, IndividualFrequencyEvaluator.Detection> detect() {
        return evaluator.detect(now, THRESHOLD);
    }

    // ── Règle de détection ───────────────────────────────────────────────────

    @Test
    void particulier_avec5Trajets_estSignale_avec4_nonSignale() {
        UserEntity five = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity four = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(five, 5, 3);
        acceptedTrips(four, 4, 3);

        Map<UUID, IndividualFrequencyEvaluator.Detection> result = detect();

        assertThat(result).containsKey(five.getId()).doesNotContainKey(four.getId());
        assertThat(result.get(five.getId()).tripsCount()).isEqualTo(5);
        assertThat(result.get(five.getId()).weeklyApplication()).isFalse();
    }

    @Test
    void gpProValide_professionnel_etSansStatut_neSontJamaisSignales() {
        UserEntity gpPro = user(TravelerStatus.INDIVIDUAL, true);
        UserEntity pro = user(TravelerStatus.PROFESSIONAL, true);
        UserEntity undeclared = user(null, false);
        acceptedTrips(gpPro, 8, 2);
        acceptedTrips(pro, 8, 2);
        acceptedTrips(undeclared, 8, 2);

        assertThat(detect()).doesNotContainKeys(gpPro.getId(), pro.getId(), undeclared.getId());
    }

    @Test
    void reservationsRefuseesAnnuleesExpireesOuNonAcceptees_neComptentPas() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity s = sender();
        acceptedTrips(traveler, 4, 1);
        for (BidStatus st : List.of(BidStatus.REJECTED, BidStatus.CANCELLED, BidStatus.EXPIRED,
                BidStatus.PENDING, BidStatus.AWAITING_PAYMENT, BidStatus.PAYMENT_ESCROWED,
                BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED, BidStatus.NEGOTIATING,
                BidStatus.NEGOTIATION_CLOSED)) {
            bid(trip(traveler, AnnouncementStatus.ACTIVE), s, st, now.minusDays(1));
        }

        assertThat(detect()).doesNotContainKey(traveler.getId());
    }

    @Test
    void reservationsAcceptees_ouPlusAvancees_comptent() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity s = sender();
        for (BidStatus st : List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
                BidStatus.ARRIVED, BidStatus.COMPLETED)) {
            bid(trip(traveler, AnnouncementStatus.ACTIVE), s, st, now.minusDays(1));
        }

        assertThat(detect()).containsKey(traveler.getId());
        assertThat(detect().get(traveler.getId()).tripsCount()).isEqualTo(5);
    }

    @Test
    void trajetAnnuleOuRetire_neComptePas() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity s = sender();
        acceptedTrips(traveler, 4, 1);
        bid(trip(traveler, AnnouncementStatus.CANCELLED), s, BidStatus.ACCEPTED, now.minusDays(1));
        bid(trip(traveler, AnnouncementStatus.REMOVED_BY_ADMIN), s, BidStatus.ACCEPTED, now.minusDays(1));

        assertThat(detect()).doesNotContainKey(traveler.getId());
    }

    @Test
    void unTrajetAvecTroisReservations_comptePourUn() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity s = sender();
        acceptedTrips(traveler, 3, 1);
        UUID busy = trip(traveler, AnnouncementStatus.FULL);
        bid(busy, s, BidStatus.ACCEPTED, now.minusDays(1));
        bid(busy, s, BidStatus.COMPLETED, now.minusDays(2));
        bid(busy, s, BidStatus.IN_TRANSIT, now.minusDays(3));

        assertThat(detect()).doesNotContainKey(traveler.getId());

        acceptedTrips(traveler, 1, 1);
        assertThat(detect().get(traveler.getId()).tripsCount()).isEqualTo(5);
    }

    @Test
    void fenetre_exactement30Jours_compte_auDela_neComptePas() {
        UserEntity inside = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity outside = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(inside, 4, 1);
        acceptedTrips(outside, 4, 1);
        UserEntity s = sender();
        bid(trip(inside, AnnouncementStatus.COMPLETED), s, BidStatus.COMPLETED, now.minusDays(30));
        bid(trip(outside, AnnouncementStatus.COMPLETED), s, BidStatus.COMPLETED, now.minusDays(30).minusMinutes(1));

        Map<UUID, IndividualFrequencyEvaluator.Detection> result = detect();

        assertThat(result).containsKey(inside.getId()).doesNotContainKey(outside.getId());
    }

    @Test
    void demandeGpProHebdomadaireRefusee_dansLaFenetre_signaleMemeSousLeSeuil() {
        UserEntity weekly = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity monthly = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity pending = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(weekly, 2, 1);
        application(weekly, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.REJECTED, now.minusDays(5));
        application(monthly, GpProTravelFrequency.MONTHLY, GpProApplicationStatus.REJECTED, now.minusDays(5));
        // Demande encore ouverte : l'équipe l'examine déjà, aucune question au voyageur.
        application(pending, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.PENDING, now.minusDays(5));

        Map<UUID, IndividualFrequencyEvaluator.Detection> result = detect();

        assertThat(result).containsKey(weekly.getId()).doesNotContainKeys(monthly.getId(), pending.getId());
        assertThat(result.get(weekly.getId()).weeklyApplication()).isTrue();
        assertThat(result.get(weekly.getId()).tripsCount()).isEqualTo(2);
    }

    @Test
    void demandeGpProHebdomadaireAncienne_horsFenetre_nesignalePas() {
        UserEntity weekly = user(TravelerStatus.INDIVIDUAL, false);
        application(weekly, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.CANCELLED, now.minusDays(31));

        assertThat(detect()).doesNotContainKey(weekly.getId());
    }

    private void application(UserEntity u, GpProTravelFrequency frequency, GpProApplicationStatus status,
                             LocalDateTime createdAt) {
        GpProApplicationEntity a = new GpProApplicationEntity();
        a.setUserId(u.getId());
        a.setStatus(status);
        a.setSource(GpProApplicationSource.PROFILE);
        a.setTravelFrequency(frequency);
        UUID id = applications.saveAndFlush(a).getId();
        backdate("gp_pro_applications", id, createdAt);
    }

    // ── Contrôle quotidien ───────────────────────────────────────────────────

    @Test
    void doubleExecution_unSeulDrapeau_etAucuneModificationDesDonnees() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);
        List<Map<String, Object>> before = snapshot(traveler);

        scheduler.run();
        scheduler.run();

        List<IndividualFrequencyFlagEntity> mine = flags.findAll().stream()
                .filter(f -> f.getUserId().equals(traveler.getId())).toList();
        assertThat(mine).hasSize(1);
        IndividualFrequencyFlagEntity flag = mine.get(0);
        assertThat(flag.getTripsCount()).isEqualTo(5);
        assertThat(flag.getWindowDays()).isEqualTo(30);
        assertThat(flag.getThreshold()).isEqualTo(THRESHOLD);
        assertThat(flag.getResolvedAt()).isNull();
        // Anti-objectif : ni le compte, ni les trajets, ni les réservations ne bougent.
        assertThat(snapshot(traveler)).isEqualTo(before);
    }

    @Test
    void drapeauResolu_quandLeVoyageurDevientGpPro() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);
        scheduler.run();

        jdbc.update("UPDATE users SET gp_pro = TRUE WHERE id = ?", traveler.getId());
        scheduler.run();

        assertThat(flags.findAll().stream().filter(f -> f.getUserId().equals(traveler.getId())))
                .singleElement().satisfies(f -> assertThat(f.getResolvedAt()).isNotNull());
    }

    /** Lignes du voyageur, de ses trajets et de leurs réservations, toutes colonnes comprises (updated_at inclus). */
    private List<Map<String, Object>> snapshot(UserEntity traveler) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT * FROM users WHERE id = ?", traveler.getId()));
        rows.addAll(jdbc.queryForList("SELECT * FROM announcements WHERE traveler_id = ? ORDER BY id",
                traveler.getId()));
        rows.addAll(jdbc.queryForList("SELECT b.* FROM bids b JOIN announcements a ON a.id = b.announcement_id "
                + "WHERE a.traveler_id = ? ORDER BY b.id", traveler.getId()));
        return rows;
    }

    // ── File admin ───────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-freq");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private IndividualFrequencyFlagEntity openFlag(UserEntity u) {
        IndividualFrequencyFlagEntity f = new IndividualFrequencyFlagEntity();
        f.setUserId(u.getId());
        f.setTripsCount(6);
        f.setWindowDays(30);
        f.setThreshold(THRESHOLD);
        f.setDetectedAt(now.minusHours(2));
        return flags.saveAndFlush(f);
    }

    @Test
    void liste_montreLesDrapeauxOuverts_etDismiss_lesRetire() throws Exception {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        IndividualFrequencyFlagEntity flag = openFlag(traveler);
        var admin = authentication(adminAuth(AdminRole.ADMIN));

        mockMvc.perform(get("/admin/individual-frequency-flags").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flagId").value(flag.getId().toString()))
                .andExpect(jsonPath("$[0].userId").value(traveler.getId().toString()))
                .andExpect(jsonPath("$[0].displayName").value("Awa Diallo"))
                .andExpect(jsonPath("$[0].tripsCount").value(6))
                .andExpect(jsonPath("$[0].windowDays").value(30))
                .andExpect(jsonPath("$[0].threshold").value(THRESHOLD))
                .andExpect(jsonPath("$[0].detectedAt").exists());

        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", flag.getId()).with(admin))
                .andExpect(status().isNoContent());
        // Idempotent.
        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", flag.getId()).with(admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/individual-frequency-flags").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        IndividualFrequencyFlagEntity dismissed = flags.findById(flag.getId()).orElseThrow();
        assertThat(dismissed.getDismissedAt()).isNotNull();
        assertThat(dismissed.getDismissedBy()).isEqualTo(ADMIN_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, flag.getId(), "INDIVIDUAL_FREQUENCY_FLAG_DISMISSED")).isEqualTo(1L);
    }

    @Test
    void drapeauEcarte_nEstPasRecree_tantQueLaConditionNeCessePas() throws Exception {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);
        scheduler.run();
        IndividualFrequencyFlagEntity flag = flags.findAll().stream()
                .filter(f -> f.getUserId().equals(traveler.getId())).findFirst().orElseThrow();
        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", flag.getId())
                .with(authentication(adminAuth(AdminRole.ADMIN)))).andExpect(status().isNoContent());

        scheduler.run();

        assertThat(flags.findAll().stream().filter(f -> f.getUserId().equals(traveler.getId()))).hasSize(1);
    }

    @Test
    void dismiss_drapeauInconnu_404() throws Exception {
        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", UUID.randomUUID())
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminSansDroitDeRevueGpPro_403() throws Exception {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        IndividualFrequencyFlagEntity flag = openFlag(traveler);
        var support = authentication(adminAuth(AdminRole.SUPPORT));

        mockMvc.perform(get("/admin/individual-frequency-flags").with(support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", flag.getId()).with(support))
                .andExpect(status().isForbidden());
        assertThat(flags.findById(flag.getId()).orElseThrow().getDismissedAt()).isNull();
    }

    // ── Fiche utilisateur admin ──────────────────────────────────────────────

    @Test
    void ficheUtilisateur_porteLAlerte_siDrapeauOuvert_etLOmetSinon() throws Exception {
        UserEntity flagged = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity quiet = user(TravelerStatus.INDIVIDUAL, false);
        IndividualFrequencyFlagEntity flag = openFlag(flagged);
        var admin = authentication(adminAuth(AdminRole.ADMIN));

        mockMvc.perform(get("/admin/users/{id}", flagged.getId()).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.individualFrequencyAlert.flagId").value(flag.getId().toString()))
                .andExpect(jsonPath("$.individualFrequencyAlert.tripsCount").value(6))
                .andExpect(jsonPath("$.individualFrequencyAlert.windowDays").value(30))
                .andExpect(jsonPath("$.individualFrequencyAlert.threshold").value(THRESHOLD))
                .andExpect(jsonPath("$.individualFrequencyAlert.detectedAt").exists());

        mockMvc.perform(get("/admin/users/{id}", quiet.getId()).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.individualFrequencyAlert").doesNotExist());

        // Écarté : plus de bandeau sur la fiche.
        mockMvc.perform(post("/admin/individual-frequency-flags/{id}/dismiss", flag.getId()).with(admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/admin/users/{id}", flagged.getId()).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.individualFrequencyAlert").doesNotExist());
    }
}
