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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        // Pool réduit et délai court, comme en prod (20 connexions, 5 s) mais plus serré : une
        // seconde connexion prise par rejeu épuiserait le pool sous 25 rejeux simultanés.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 5000);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    // Espion : le test d'entrelacement retient le job entre sa lecture des échéances et le verrou du colis.
    @MockitoSpyBean CancellationRepository cancellationRepository;
    @Autowired com.yadony.api.payments.PaymentRepository paymentRepository;
    // Aucun remboursement réel ; le test vérifie qu'aucun n'est demandé pour un colis remis.
    @MockitoBean com.yadony.api.payments.RefundProcessor refundProcessor;
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
    void declarationEnAttente_scanHorsLigneRejoue_memeRegle_auditAvecLaPreuveDuScan() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        LocalDateTime offline = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).withNano(0);
        String photo = "tracking/" + bid.getId() + "/1_depart.jpg";

        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"MANUAL\","
                + "\"trackingNumber\":\"" + bid.getTrackingNumber() + "\",\"photoUrl\":\"" + photo + "\","
                + "\"gpsLat\":48.8566,\"gpsLon\":2.3522,\"gpsLabel\":\"Gare du Nord\","
                + "\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isCreated());

        assertHandedOverAndWithdrawn(bid, c);
        // Décision du propriétaire : aucune preuve exigée, mais l'audit garde celle du scan.
        Map<String, Object> payload = jdbc.queryForMap("SELECT payload->>'trackingEventId' AS event, "
                + "payload->>'scanMethod' AS method, payload->>'photoKey' AS photo, payload->>'offlineTimestamp' AS offline, "
                + "payload->>'gpsLat' AS lat, payload->>'gpsLon' AS lon, payload->>'previousStatus' AS previous, "
                + "payload::text AS raw FROM audit_log WHERE entity_id = ? AND action = 'SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER'",
                c.getId());
        UUID departId = trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId()).get(0).getId();
        assertThat(payload.get("event")).isEqualTo(departId.toString());
        assertThat(payload.get("method")).isEqualTo("MANUAL");
        assertThat(payload.get("photo")).isEqualTo(photo);
        assertThat(payload.get("offline")).isEqualTo(offline.toString());
        assertThat(payload.get("lat")).isEqualTo("48.8566");
        assertThat(payload.get("lon")).isEqualTo("2.3522");
        assertThat(payload.get("previous")).isEqualTo("PENDING_CONFIRMATION");
        // Coordonnées seules : jamais le libellé d'adresse.
        assertThat((String) payload.get("raw")).doesNotContain("Gare du Nord");
    }

    @Test
    void declarationEnAttente_scanEnsuiteRefuse_laLeveeEstAnnuleeAvecLui() throws Exception {
        // La photo invalide est refusée avant l'enregistrement de la remise : la déclaration n'est pas levée.
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

    @Test
    void declarationLeveeParLeScan_puisAnnulationApresRemise_200_retourOuvert() throws Exception {
        // UNIQUE(bid_id, scope) : la ligne HANDOVER de la déclaration levée est réutilisée, pas de 409.
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr").andExpect(status().isCreated());

        mockMvc.perform(post("/bids/{bidId}/cancel-after-handover", bid.getId()).with(authentication(traveler())))
                .andExpect(status().isOk());

        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(after.getReturnCode()).hasSize(6);
        assertThat(after.getReturnDeadline()).isNotNull();
        Map<String, Object> row = jdbc.queryForMap("SELECT id, reason, no_show_status, cancelled_by, contestation_deadline "
                + "FROM cancellations WHERE bid_id = ? AND scope = 'HANDOVER'", bid.getId());
        assertThat(row.get("id")).isEqualTo(c.getId());
        assertThat(row.get("reason")).isEqualTo("TRAVELER_CANCEL_AFTER_HANDOVER");
        assertThat(row.get("no_show_status")).isEqualTo("CONFIRMED");
        assertThat(row.get("cancelled_by")).isEqualTo(travelerId);
        assertThat(row.get("contestation_deadline")).isNull();
        assertThat(auditCount(c, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isEqualTo(1);
        assertThat(auditCount(c, "SENDER_NO_SHOW_ROW_REUSED_AFTER_HANDOVER")).isEqualTo(1);
    }

    @Test
    void departHorsLigneAnterieurALaConfirmation_422_uneSeuleAlerteNonVerifiee_aucunEtatMetierModifie() throws Exception {
        // Le téléphone était hors ligne au moment de la remise ; l'échéance a confirmé l'absence entre-temps.
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.CONFIRMED);
        com.yadony.api.payments.PaymentEntity payment = seedEscrowPayment(bid);
        LocalDateTime decidedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10).withNano(0);
        // Colis créé deux jours plus tôt : l'heure de scan déclarée n'est pas antérieure au colis.
        jdbc.update("UPDATE bids SET status = 'CANCELLED', rejection_reason = 'SENDER_NO_SHOW', cancelled_at = ?, "
                + "created_at = created_at - interval '2 days' WHERE id = ?", decidedAt, bid.getId());
        Map<String, Object> bidBefore = jdbc.queryForMap(
                "SELECT status, rejection_reason, cancelled_at, updated_at FROM bids WHERE id = ?", bid.getId());
        Map<String, Object> declarationBefore = jdbc.queryForMap(
                "SELECT no_show_status, updated_at FROM cancellations WHERE id = ?", c.getId());
        LocalDateTime offline = decidedAt.minusHours(2);
        String body = "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"MANUAL\","
                + "\"trackingNumber\":\"" + bid.getTrackingNumber() + "\",\"offlineTimestamp\":\"" + offline + "\"}";

        // Deux rejeux (la file hors ligne renvoie l'étape) : 422 à chaque fois, une seule alerte, un seul audit.
        for (int i = 0; i < 2; i++) {
            scan(body, "fr")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("bid-not-accepted"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_alerts WHERE type = ?",
                Integer.class, "LATE_HANDOVER_SCAN_" + bid.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT severity FROM admin_alerts WHERE type = ?", String.class,
                "LATE_HANDOVER_SCAN_" + bid.getId())).as("numéro de suivi vérifié : incident").isEqualTo("CRITICAL");
        Map<String, Object> alert = jdbc.queryForMap("SELECT detail, payload->>'clientDeclared' AS declared, "
                + "payload->>'verified' AS verified, payload->>'proofValidated' AS proof, payload->>'proofMethod' AS method "
                + "FROM admin_alerts WHERE type = ?", "LATE_HANDOVER_SCAN_" + bid.getId());
        assertThat((String) alert.get("detail")).startsWith("DÉCLARÉ PAR LE CLIENT, NON VÉRIFIÉ");
        assertThat(alert.get("declared")).isEqualTo("true");
        assertThat(alert.get("verified")).isEqualTo("false");
        assertThat(alert.get("proof")).isEqualTo("true");
        assertThat(alert.get("method")).isEqualTo("TRACKING_NUMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'LATE_HANDOVER_SCAN_AFTER_DECISION' AND actor_id = ?", Integer.class, bid.getId(), travelerId))
                .isEqualTo(1);
        // Alerte et audit seulement : colis, déclaration, étapes et paiement intacts.
        assertThat(jdbc.queryForMap("SELECT status, rejection_reason, cancelled_at, updated_at FROM bids WHERE id = ?",
                bid.getId())).isEqualTo(bidBefore);
        assertThat(jdbc.queryForMap("SELECT no_show_status, updated_at FROM cancellations WHERE id = ?", c.getId()))
                .isEqualTo(declarationBefore);
        assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(com.yadony.api.payments.PaymentStatus.ESCROW);
        org.mockito.Mockito.verifyNoInteractions(refundProcessor);
    }

    @Test
    void departHorsLigneTardifSansPreuveValide_422_sansAlerte() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        LocalDateTime decidedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10).withNano(0);
        // Colis créé deux jours plus tôt : l'heure de scan déclarée n'est pas antérieure au colis.
        jdbc.update("UPDATE bids SET status = 'CANCELLED', rejection_reason = 'SENDER_NO_SHOW', cancelled_at = ?, "
                + "created_at = created_at - interval '2 days' WHERE id = ?", decidedAt, bid.getId());
        LocalDateTime offline = decidedAt.minusHours(2);

        // Numéro de suivi faux.
        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"MANUAL\","
                + "\"trackingNumber\":\"DNY000000000\",\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bid-not-accepted"));
        // Photo hors du dossier du colis.
        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"photoUrl\":\"https://ailleurs.example/x.jpg\","
                + "\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bid-not-accepted"));
        // Un autre voyageur : refusé avant tout (403), rien n'est signalé.
        UserEntity other = persistUser(Role.TRAVELER);
        mockMvc.perform(post("/tracking/events")
                        .with(authentication(new UsernamePasswordAuthenticationToken(other.getFirebaseUid(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"offlineTimestamp\":\""
                                + offline + "\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_alerts WHERE type IN (?, ?)",
                Integer.class, "LATE_HANDOVER_SCAN_" + bid.getId(), "LATE_HANDOVER_CLAIM_" + bid.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'LATE_HANDOVER_SCAN_AFTER_DECISION'", Integer.class, bid.getId())).isZero();
    }

    @Test
    void vingtCinqRejeuxSimultanesDeColisDejaSignales_nEpuisentPasLePool() throws Exception {
        // Colis distincts (donc des verrous distincts, en parallèle), tous déjà signalés : la
        // déduplication se lit dans la transaction du scan, sans seconde connexion. Avec une
        // transaction REQUIRES_NEW par rejeu, 10 scans tenaient chacun une connexion en attendant la
        // seconde : pool épuisé, chaque rejeu attendait le délai de 5 s.
        int replays = 25;
        List<BidEntity> bids = new java.util.ArrayList<>();
        LocalDateTime decidedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10).withNano(0);
        for (int i = 0; i < replays; i++) {
            BidEntity bid = seedBid(BidStatus.ACCEPTED);
            jdbc.update("UPDATE bids SET status = 'CANCELLED', rejection_reason = 'SENDER_NO_SHOW', cancelled_at = ?, "
                    + "created_at = created_at - interval '2 days' WHERE id = ?", decidedAt, bid.getId());
            jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) "
                    + "VALUES ('BID', ?, 'LATE_HANDOVER_SCAN_AFTER_DECISION', ?, '{}'::jsonb, now())", bid.getId(), travelerId);
            bids.add(bid);
        }
        LocalDateTime offline = decidedAt.minusHours(2);
        CyclicBarrier start = new CyclicBarrier(replays);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(replays);
        List<java.util.concurrent.Future<Throwable>> results = new java.util.ArrayList<>();
        long begin = System.nanoTime();
        try {
            for (BidEntity bid : bids) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        trackingService.recordScan(new QrScanRequest(bid.getId(), TrackingEventType.DEPART,
                                null, null, null, null, offline), travelerUid);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            for (var result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(YadonyBusinessException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo("bid-not-accepted"));
            }
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertThat(elapsedMs).as("aucun rejeu n'a attendu le délai du pool (5 s)").isLessThan(4_500);
        for (BidEntity bid : bids) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? "
                    + "AND action = 'LATE_HANDOVER_SCAN_AFTER_DECISION'", Integer.class, bid.getId())).isEqualTo(1);
        }
    }

    @Test
    void expediteurConfirmeSonAbsence_surUneDeclarationLeveeParLeScan_409_rienNeChange() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        scan("{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr").andExpect(status().isCreated());

        mockMvc.perform(post("/cancellations/bids/{bidId}/confirm-noshow-self", bid.getId())
                        .with(authentication(sender(bid))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("noshow-already-decided"));

        assertThat(statusOf(c)).isEqualTo("RESOLVED");
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        org.mockito.Mockito.verify(refundProcessor, org.mockito.Mockito.after(500).never())
                .processRefund(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expediteurConfirmeSonAbsence_surUneDeclarationEnAttente_200_colisAnnule() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);

        mockMvc.perform(post("/cancellations/bids/{bidId}/confirm-noshow-self", bid.getId())
                        .with(authentication(sender(bid))))
                .andExpect(status().isOk());

        assertThat(statusOf(c)).isEqualTo("CONFIRMED");
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
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
        com.yadony.api.payments.PaymentEntity payment = seedEscrowPayment(bid);
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
            // Colis remis : le séquestre n'est jamais rendu.
            org.mockito.Mockito.verify(refundProcessor, org.mockito.Mockito.after(1_500).never())
                    .processRefund(org.mockito.ArgumentMatchers.eq(payment.getId()), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        } else {
            assertThat(scanError.get()).isInstanceOfSatisfying(YadonyBusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo("bid-not-accepted"));
            assertThat(bidStatus).isEqualTo("CANCELLED");
            assertThat(declaration).isEqualTo("CONFIRMED");
            assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
        }
    }

    @Test
    void echeanceRetenueApresSaLecture_leScanLeveLaDeclaration_leJobNeConfirmeRien_aucunRemboursement() throws Exception {
        // Entrelacement dangereux forcé : le job a lu la déclaration échue (encore en attente) puis est
        // retenu AVANT le verrou du colis ; le scan passe, lève la déclaration et commite. Sans le
        // verrou suivi de la relecture du statut, le job confirmait sa copie périmée (CONFIRMED sur un
        // colis remis).
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        CancellationEntity c = seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        jdbc.update("UPDATE cancellations SET contestation_deadline = now() - interval '5 minutes' WHERE id = ?",
                c.getId());
        com.yadony.api.payments.PaymentEntity payment = seedEscrowPayment(bid);
        java.util.concurrent.CountDownLatch jobRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch scanDone = new java.util.concurrent.CountDownLatch(1);
        org.mockito.stubbing.Answer<?> real = org.mockito.Mockito.mockingDetails(cancellationRepository)
                .getMockCreationSettings().getDefaultAnswer();
        org.mockito.Mockito.doAnswer(inv -> {
            Object expired = real.answer(inv);
            jobRead.countDown();
            assertThat(scanDone.await(20, TimeUnit.SECONDS)).isTrue();
            return expired;
        }).when(cancellationRepository).findExpiredPending(org.mockito.ArgumentMatchers.any());
        AtomicReference<Throwable> jobError = new AtomicReference<>();
        Thread job = new Thread(() -> {
            try {
                contestationTimeoutJob.run();
            } catch (Throwable t) {
                jobError.set(t);
            }
        });
        try {
            job.start();
            assertThat(jobRead.await(20, TimeUnit.SECONDS)).as("le job a lu les échéances").isTrue();
            assertThat(trackingService.recordScan(departOf(bid), travelerUid).created()).isTrue();
        } finally {
            scanDone.countDown();
            job.join(30_000);
            org.mockito.Mockito.reset(cancellationRepository);
        }

        assertThat(job.isAlive()).isFalse();
        assertThat(jobError.get()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, bid.getId()))
                .isEqualTo("HANDED_OVER");
        assertThat(statusOf(c)).isEqualTo("RESOLVED");
        // Le job a relu le statut sous le verrou et ignoré la déclaration levée : il ne l'a pas réécrite
        // à partir de sa copie périmée (sans verrou, il la « clôturait » une seconde fois depuis PENDING).
        assertThat(auditCount(c, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER")).isEqualTo(1);
        assertThat(auditCount(c, "SENDER_NO_SHOW_CLOSED_PARCEL_HANDED_OVER")).isZero();
        org.mockito.Mockito.verify(refundProcessor, org.mockito.Mockito.after(1_500).never())
                .processRefund(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(com.yadony.api.payments.PaymentStatus.ESCROW);
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
        assertThat(a.isAlive()).as("premier fil terminé").isFalse();
        assertThat(b.isAlive()).as("second fil terminé").isFalse();
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

    private UsernamePasswordAuthenticationToken sender(BidEntity bid) {
        String uid = userRepository.findById(bid.getSenderId()).orElseThrow().getFirebaseUid();
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
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

    private com.yadony.api.payments.PaymentEntity seedEscrowPayment(BidEntity bid) {
        com.yadony.api.payments.PaymentEntity p = new com.yadony.api.payments.PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(com.yadony.api.payments.PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        p.setCurrency("EUR");
        p.setStatus(com.yadony.api.payments.PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p);
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
