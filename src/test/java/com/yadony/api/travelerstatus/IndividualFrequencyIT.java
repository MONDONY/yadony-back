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
    /** Le bean du contexte : éteint par défaut (application.yml). */
    @Autowired IndividualFrequencyScheduler contextScheduler;
    @Autowired com.yadony.api.config.PlatformSettingsService settings;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;
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
    /** Allumé explicitement, seuil réel (4 par défaut), vraies transactions et vrai écouteur de notification. */
    private IndividualFrequencyScheduler scheduler;
    /** Chaque trajet créé part un jour différent, sauf demande explicite. */
    private int nextDay = 10;

    @BeforeEach
    void setUp() {
        when(firebaseContact.getContact(any())).thenReturn(FirebaseContactService.Contact.EMPTY);
        flags.deleteAll();
        now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        scheduler = enabledScheduler(settings);
    }

    private IndividualFrequencyScheduler enabledScheduler(com.yadony.api.config.PlatformSettingsService s) {
        return new IndividualFrequencyScheduler(flags, evaluator, s, publisher,
                new IndividualFrequencyProperties(null, true), java.time.Clock.systemUTC(), tx);
    }

    private IndividualFrequencyScheduler schedulerWithThreshold(int threshold) {
        com.yadony.api.config.PlatformSettingsService s =
                org.mockito.Mockito.mock(com.yadony.api.config.PlatformSettingsService.class);
        when(s.individualTripsAlertThreshold()).thenReturn(threshold);
        return enabledScheduler(s);
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
        return trip(traveler, status, LocalDate.now().plusDays(nextDay++));
    }

    private UUID trip(UserEntity traveler, AnnouncementStatus status, LocalDate departureDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(departureDate);
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
        backdate(table, "created_at", id, createdAt);
    }

    private void backdate(String table, String column, UUID id, LocalDateTime value) {
        tx.executeWithoutResult(s -> entityManager
                .createNativeQuery("UPDATE " + table + " SET " + column + " = :value WHERE id = :id")
                .setParameter("value", value)
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
        // Données d'avant le GP Pro unifié (#721) : professionnel sans badge.
        UserEntity proWithoutBadge = user(TravelerStatus.PROFESSIONAL, false);
        UserEntity undeclared = user(null, false);
        acceptedTrips(gpPro, 8, 2);
        acceptedTrips(pro, 8, 2);
        acceptedTrips(proWithoutBadge, 8, 2);
        acceptedTrips(undeclared, 8, 2);

        assertThat(detect()).doesNotContainKeys(gpPro.getId(), pro.getId(), proWithoutBadge.getId(),
                undeclared.getId());
    }

    @Test
    void comptesSuspendusBannisEnSuppressionOuSupprimes_neSontJamaisSignales() {
        UserEntity suspended = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity banned = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity pendingDeletion = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity deletionRequested = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity softDeleted = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity active = user(TravelerStatus.INDIVIDUAL, false);
        for (UserEntity u : List.of(suspended, banned, pendingDeletion, deletionRequested, softDeleted, active)) {
            acceptedTrips(u, 6, 2);
            application(u, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.REJECTED, now.minusDays(3));
        }
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspended.getId());
        jdbc.update("UPDATE users SET status = 'BANNED' WHERE id = ?", banned.getId());
        jdbc.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", pendingDeletion.getId());
        jdbc.update("UPDATE users SET deletion_requested_at = CURRENT_TIMESTAMP WHERE id = ?",
                deletionRequested.getId());
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", softDeleted.getId());

        Map<UUID, IndividualFrequencyEvaluator.Detection> result = detect();

        assertThat(result).containsKey(active.getId()).doesNotContainKeys(suspended.getId(), banned.getId(),
                pendingDeletion.getId(), deletionRequested.getId(), softDeleted.getId());
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

    /** La fenêtre porte sur la décision (refus) ou l'annulation, pas sur le dépôt de la demande. */
    @Test
    void demandeGpProHebdomadaire_dateeSurLaDecision_pasSurLeDepot() {
        UserEntity decidedRecently = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity cancelledRecently = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity createdRecentlyDecidedLongAgo = user(TravelerStatus.INDIVIDUAL, false);
        UUID a1 = application(decidedRecently, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.REJECTED,
                now.minusDays(2));
        backdate("gp_pro_applications", a1, now.minusDays(45));
        UUID a2 = application(cancelledRecently, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.CANCELLED,
                now.minusDays(2));
        backdate("gp_pro_applications", a2, now.minusDays(45));
        UUID a3 = application(createdRecentlyDecidedLongAgo, GpProTravelFrequency.WEEKLY,
                GpProApplicationStatus.REJECTED, now.minusDays(31));
        backdate("gp_pro_applications", a3, now.minusDays(1));

        assertThat(detect()).containsKeys(decidedRecently.getId(), cancelledRecently.getId())
                .doesNotContainKey(createdRecentlyDecidedLongAgo.getId());
    }

    @Test
    void demandeGpProEnCours_jamaisSignale_niParLeCompteur_etSonDrapeauEstResolu() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 6, 2);
        scheduler.run();
        assertThat(openFlags(traveler)).hasSize(1);

        UUID open = application(traveler, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.PENDING, now);
        assertThat(detect()).doesNotContainKey(traveler.getId());
        scheduler.run();
        assertThat(openFlags(traveler)).isEmpty();

        jdbc.update("UPDATE gp_pro_applications SET status = 'AWAITING_KYC' WHERE id = ?", open);
        assertThat(detect()).doesNotContainKey(traveler.getId());
    }

    @Test
    void deuxAnnoncesLeMemeJour_unSeulVoyage() {
        UserEntity dedicated = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity sameDayBelow = user(TravelerStatus.INDIVIDUAL, false);
        UserEntity s = sender();
        // 4 voyages + 3 trajets dédiés du même vol : 5 voyages, pas 7.
        acceptedTrips(dedicated, 4, 1);
        LocalDate flight = LocalDate.now().plusDays(60);
        for (int i = 0; i < 3; i++) {
            bid(trip(dedicated, AnnouncementStatus.ACTIVE, flight), s, BidStatus.ACCEPTED, now.minusDays(1));
        }
        // 3 voyages + 2 annonces le même jour : 4 voyages, pas 5, sous le seuil.
        acceptedTrips(sameDayBelow, 3, 1);
        bid(trip(sameDayBelow, AnnouncementStatus.ACTIVE, flight), s, BidStatus.ACCEPTED, now.minusDays(1));
        bid(trip(sameDayBelow, AnnouncementStatus.ACTIVE, flight), s, BidStatus.COMPLETED, now.minusDays(1));

        Map<UUID, IndividualFrequencyEvaluator.Detection> result = detect();

        assertThat(result.get(dedicated.getId()).tripsCount()).isEqualTo(5);
        assertThat(result).doesNotContainKey(sameDayBelow.getId());
    }

    /**
     * Demande GP Pro dont la dernière décision (refus : {@code decided_at}) ou modification (annulation, demande
     * ouverte : {@code updated_at}) date de {@code closedAt}. Déposée la même date, sauf antidatage explicite.
     */
    private UUID application(UserEntity u, GpProTravelFrequency frequency, GpProApplicationStatus status,
                             LocalDateTime closedAt) {
        GpProApplicationEntity a = new GpProApplicationEntity();
        a.setUserId(u.getId());
        a.setStatus(status);
        a.setSource(GpProApplicationSource.PROFILE);
        a.setTravelFrequency(frequency);
        UUID id = applications.saveAndFlush(a).getId();
        backdate("gp_pro_applications", "created_at", id, closedAt);
        backdate("gp_pro_applications", "updated_at", id, closedAt);
        if (status == GpProApplicationStatus.REJECTED) {
            backdate("gp_pro_applications", "decided_at", id, closedAt);
        }
        return id;
    }

    private List<IndividualFrequencyFlagEntity> openFlags(UserEntity u) {
        return flags.findAll().stream()
                .filter(f -> f.getUserId().equals(u.getId()) && f.getResolvedAt() == null).toList();
    }

    private long questions(UserEntity u) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
                Long.class, u.getId(), "TRAVELER_STATUS_CONFIRMATION");
    }

    /** L'écouteur est asynchrone (après validation) : attend que {@code expected} questions soient enregistrées. */
    private void awaitQuestions(UserEntity u, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (questions(u) < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(questions(u)).isEqualTo(expected);
    }

    // ── Contrôle quotidien ───────────────────────────────────────────────────

    @Test
    void beanDuContexte_eteintParDefaut_neFaitRien() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 6, 2);
        application(traveler, GpProTravelFrequency.WEEKLY, GpProApplicationStatus.REJECTED, now.minusDays(1));

        assertThat(contextScheduler.run()).isEqualTo(new IndividualFrequencyScheduler.Outcome(0, 0, 0, 0));
        assertThat(openFlags(traveler)).isEmpty();
    }

    @Test
    void doubleExecution_unSeulDrapeau_uneSeuleQuestion_etAucuneModificationDesDonnees() throws Exception {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);
        List<Map<String, Object>> before = snapshot(traveler);

        scheduler.run();
        scheduler.run();
        // L'écouteur asynchrone a fini (sa ligne est écrite) avant de comparer : l'instantané couvre aussi l'envoi.
        awaitQuestions(traveler, 1);
        Thread.sleep(500);
        assertThat(questions(traveler)).isEqualTo(1);

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

    @Test
    void seuilReleve_resout_puisSeuilRebaisse_nouveauDrapeauSansSecondeQuestion() throws Exception {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);

        schedulerWithThreshold(4).run();
        awaitQuestions(traveler, 1);
        schedulerWithThreshold(10).run();
        assertThat(openFlags(traveler)).isEmpty();
        schedulerWithThreshold(4).run();

        assertThat(openFlags(traveler)).hasSize(1);
        assertThat(flags.findAll().stream().filter(f -> f.getUserId().equals(traveler.getId()))).hasSize(2);
        Thread.sleep(500);
        assertThat(questions(traveler)).isEqualTo(1);
    }

    @Test
    void drapeauResolu_quandLeCompteEstSuspendu() {
        UserEntity traveler = user(TravelerStatus.INDIVIDUAL, false);
        acceptedTrips(traveler, 5, 2);
        scheduler.run();

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", traveler.getId());
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
        // Double clic : 204 aussi, et une seule ligne d'audit (écriture conditionnelle).
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
