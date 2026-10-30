package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationService;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.cancellation.NoShowContestationTimeoutJob;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.dto.QrScanRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scan de remise (DEPART) face à une déclaration d'absence de l'expéditeur, de bout en bout
 * (MockMvc, portes de remise, vraie base PostgreSQL : les portes verrouillent en {@code FOR UPDATE},
 * que H2 ne sait pas lire). Décision du propriétaire :
 * <ul>
 *   <li>déclaration en attente ({@code PENDING_CONFIRMATION}) : le scan la lève (RESOLVED, audit
 *       {@code SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER} au nom du voyageur) et la remise passe ;</li>
 *   <li>contestée ou confirmée : 409 {@code handover-sender-noshow-pending}, rien n'est écrit.</li>
 * </ul>
 * Sans cela, le colis partait puis l'échéance confirmait l'absence : séquestre rendu à l'expéditeur
 * dont le colis voyageait. Tous les chemins de l'app passent par {@code POST /tracking/events} : scan
 * direct, scan hors ligne rejoué ({@code offlineTimestamp}), numéro saisi ({@code scanMethod = MANUAL}).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class DepartScanSenderNoShowIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired CancellationRepository cancellationRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired TrackingService trackingService;
    @Autowired CancellationService cancellationService;
    @Autowired NoShowContestationTimeoutJob contestationTimeoutJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessagesResolver messagesResolver;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    private static final String BLOCKED_FR = "Votre déclaration d'absence de l'expéditeur est examinée par l'équipe"
            + " Yadony : la remise est bloquée jusqu'à sa décision. Contactez le support si l'expéditeur est venu.";
    private static final String BLOCKED_EN = "Your report of the sender's absence is being reviewed by the Yadony"
            + " team: the handover is blocked until they decide. Contact support if the sender did show up.";

    private String travelerUid;
    private UUID travelerId;

    @BeforeEach
    void seedTraveler() {
        org.mockito.Mockito.when(notificationDispatcher.messagesFor(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> messagesResolver.forUser(inv.getArgument(0)));
        UserEntity traveler = persistUser(Role.TRAVELER);
        travelerUid = traveler.getFirebaseUid();
        travelerId = traveler.getId();
    }

    // ── Déclaration en attente : levée par le scan ───────────────────────────

    @Test
    void declarationEnAttente_scanDepart_201_declarationLeveeAuNomDuVoyageur() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"QR\"}", "fr")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("DEPART"));

        assertHandedOverAndWithdrawn(bid, c);
    }

    @Test
    void declarationEnAttente_scanHorsLigneRejoue_memeRegle() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        LocalDateTime offline = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).withNano(0);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"QR\","
                + "\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isCreated());

        assertHandedOverAndWithdrawn(bid, c);
    }

    @Test
    void declarationEnAttente_scanEnsuiteRefuse_laLeveeEstAnnuleeAvecLui() throws Exception {
        // La photo invalide est refusée APRÈS les portes : la levée, dans la même transaction, est annulée.
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"photoUrl\":\"https://ailleurs.example/x.jpg\"}", "fr")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-photo-url"));

        assertNothingRecorded(bid);
        assertThat(statusOf(c)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(auditCount(c, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isZero();
    }

    // ── Déclaration contestée ou confirmée : 409 ─────────────────────────────

    @ParameterizedTest
    @EnumSource(value = CancellationStatus.class, names = {"CONTESTED", "CONFIRMED"})
    void declarationContesteeOuConfirmee_scanDepart_409_rienNEstEcrit(CancellationStatus blocking) throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, blocking);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"QR\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/handover-sender-noshow-pending"))
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.detail").value(BLOCKED_FR));

        assertNothingRecorded(bid);
        assertThat(statusOf(c)).isEqualTo(blocking.name());
    }

    @Test
    void declarationContestee_scanHorsLigneRejoue_409() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.CONTESTED);
        LocalDateTime offline = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).withNano(0);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"));

        assertNothingRecorded(bid);
    }

    @Test
    void declarationContestee_saisieDuNumeroDeSuivi_409() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.CONTESTED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"MANUAL\","
                + "\"trackingNumber\":\"" + bid.getTrackingNumber() + "\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"));

        assertNothingRecorded(bid);
    }

    @Test
    void declarationContestee_messageEnAnglais() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.CONTESTED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "en")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(BLOCKED_EN));
    }

    @Test
    void departDejaEnregistre_rejeuIdempotent200_malgreUneDeclarationBloquante() throws Exception {
        // Rejeu du même DEPART (envoi direct + file hors ligne) : la branche idempotente passe avant les portes.
        BidEntity bid = seedBid(BidStatus.HANDED_OVER);
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(bid.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        UUID departId = trackingEventRepository.saveAndFlush(depart).getId();
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.CONTESTED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(departId.toString()));

        assertThat(statusOf(c)).isEqualTo("CONTESTED");
    }

    // ── Sans déclaration ouverte ─────────────────────────────────────────────

    @Test
    void declarationLevee_scanDepart_201() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.RESOLVED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr")
                .andExpect(status().isCreated());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(auditCount(c, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isZero();
    }

    @Test
    void aucuneDeclaration_scanDepart_201() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr")
                .andExpect(status().isCreated());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
    }

    @Test
    void declarationContestee_neBloquePasLeTransitDUnColisDejaRemis() throws Exception {
        BidEntity bid = seedBid(BidStatus.HANDED_OVER);
        seedSenderNoShow(bid, CancellationStatus.CONTESTED);

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"TRANSIT\"}", "fr")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("TRANSIT"));

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
    }

    // ── Concurrence réelle (verrou du colis) ─────────────────────────────────

    @RepeatedTest(3)
    void declarationEtScanSimultanes_jamaisDeColisRemisAvecUneDeclarationOuverte() throws Exception {
        // Sans le verrou dans reportSenderNoShow, la déclaration lisait ACCEPTED pendant que le scan
        // passait le colis HANDED_OVER : colis parti, déclaration PENDING, absence confirmée à 24 h.
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        AtomicReference<Throwable> reportError = new AtomicReference<>();
        AtomicReference<Throwable> scanError = new AtomicReference<>();

        race(() -> cancellationService.reportSenderNoShow(bid.getId(), travelerId), reportError,
                () -> trackingService.recordScan(departOf(bid), travelerUid), scanError);

        assertThat(scanError.get()).as("le scan passe toujours (déclaration absente ou levée)").isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, bid.getId()))
                .isEqualTo("HANDED_OVER");
        List<String> declarations = jdbc.queryForList(
                "SELECT no_show_status FROM cancellations WHERE bid_id = ? AND scope = 'HANDOVER'", String.class, bid.getId());
        if (reportError.get() != null) {
            // Scan d'abord : le colis n'est plus ACCEPTED, la déclaration est refusée.
            assertThat(declarations).isEmpty();
        } else {
            // Déclaration d'abord : le scan l'a levée.
            assertThat(declarations).containsExactly("RESOLVED");
        }
    }

    @RepeatedTest(3)
    void echeanceEtScanSimultanes_jamaisDeRemboursementDUnColisRemis() throws Exception {
        // Le job confirme l'absence (colis CANCELLED, séquestre rendu) pendant que le scan la lève (colis
        // HANDED_OVER). Les deux prennent le verrou du colis : l'un ou l'autre, jamais un mélange.
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        jdbc.update("UPDATE cancellations SET contestation_deadline = now() - interval '5 minutes' WHERE id = ?",
                c.getId());
        AtomicReference<Throwable> jobError = new AtomicReference<>();
        AtomicReference<Throwable> scanError = new AtomicReference<>();

        race(contestationTimeoutJob::run, jobError,
                () -> trackingService.recordScan(departOf(bid), travelerUid), scanError);

        assertThat(jobError.get()).isNull();
        String bidStatus = jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, bid.getId());
        String declaration = statusOf(c);
        if (scanError.get() == null) {
            assertThat(bidStatus).isEqualTo("HANDED_OVER");
            assertThat(declaration).isEqualTo("RESOLVED");
        } else {
            assertThat(scanError.get()).isInstanceOfSatisfying(YadonyBusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo("bid-not-accepted"));
            assertThat(bidStatus).isEqualTo("CANCELLED");
            assertThat(declaration).isEqualTo("CONFIRMED");
            assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
        }
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    private void race(Runnable first, AtomicReference<Throwable> firstError,
                      Runnable second, AtomicReference<Throwable> secondError) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(2);
        Thread a = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                first.run();
            } catch (Throwable t) {
                firstError.set(t);
            }
        });
        Thread b = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                second.run();
            } catch (Throwable t) {
                secondError.set(t);
            }
        });
        a.start();
        b.start();
        a.join(30_000);
        b.join(30_000);
    }

    private QrScanRequest departOf(BidEntity bid) {
        return new QrScanRequest(bid.getId(), TrackingEventType.DEPART, null, null, null, null, null);
    }

    private ResultActions scan(String body, String language) throws Exception {
        return mockMvc.perform(post("/tracking/events")
                .with(authentication(traveler()))
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertHandedOverAndWithdrawn(BidEntity bid, CancellationEntity c) {
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(statusOf(c)).isEqualTo("RESOLVED");
        assertThat(auditCount(c, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE entity_id = ? AND action = ?",
                UUID.class, c.getId(), "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isEqualTo(travelerId);
    }

    private void assertNothingRecorded(BidEntity bid) {
        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(after.getConfirmationCode()).isNull();
        assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
    }

    private String statusOf(CancellationEntity c) {
        return jdbc.queryForObject("SELECT no_show_status FROM cancellations WHERE id = ?", String.class, c.getId());
    }

    private int auditCount(CancellationEntity c, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, c.getId(), action);
        return n == null ? 0 : n;
    }

    private UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(travelerUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-noshow-it-" + UUID.randomUUID());
        u.setFirstName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(u);
    }

    private CancellationEntity seedSenderNoShow(BidEntity bid, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bid.getId());
        c.setCancelledBy(travelerId);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setNoShowStatus(status);
        c.setContestationDeadline(OffsetDateTime.now().plusHours(24));
        return cancellationRepository.saveAndFlush(c);
    }

    private BidEntity seedBid(BidStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(1));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Plateau");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(persistUser(Role.SENDER).getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(status);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        // Date limite de remise passée (la déclaration d'absence n'est possible qu'après elle), mais
        // encore dans l'heure de grâce du NoShowScheduler : un passage horaire pendant le test l'ignore.
        bid.setHandoverDeadline(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        return bidRepository.saveAndFlush(bid);
    }
}
