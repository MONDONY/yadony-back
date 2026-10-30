package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.common.i18n.MessagesResolver;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scan de remise (DEPART) refusé tant qu'une déclaration d'absence de l'expéditeur est ouverte
 * sur le colis : sans ce refus, le colis partait, puis l'échéance de contestation confirmait
 * l'absence et remboursait le séquestre à l'expéditeur dont le colis voyageait déjà. Service,
 * portes de remise et vraie base PostgreSQL (les portes verrouillent en {@code FOR UPDATE}, que H2
 * ne sait pas lire) : 409 RFC 7807 {@code handover-sender-noshow-pending},
 * aucune étape enregistrée, colis toujours ACCEPTED. Tous les chemins de l'app passent par
 * {@code POST /tracking/events} : scan direct, scan hors ligne rejoué ({@code offlineTimestamp}),
 * saisie du numéro de suivi ({@code scanMethod = MANUAL}).
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
    @Autowired MessagesResolver messagesResolver;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

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

    @ParameterizedTest
    @EnumSource(value = CancellationStatus.class, names = {"PENDING_CONFIRMATION", "CONTESTED"})
    void declarationOuverte_scanDepart_409_rienNEstEnregistre(CancellationStatus open) throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, open);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"QR\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/handover-sender-noshow-pending"))
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.detail").value(
                        "Vous avez déclaré l'expéditeur absent : la remise est bloquée tant que cette déclaration"
                                + " n'est pas levée. Contactez le support si l'expéditeur est finalement venu."));

        assertNothingRecorded(bid);
    }

    @Test
    void declarationOuverte_scanHorsLigneRejoue_409_resteRefuse() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);
        LocalDateTime offline = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).withNano(0);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"QR\","
                + "\"offlineTimestamp\":\"" + offline + "\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"));

        assertNothingRecorded(bid);
    }

    @Test
    void declarationOuverte_saisieDuNumeroDeSuivi_409() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.CONTESTED);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\",\"scanMethod\":\"MANUAL\","
                + "\"trackingNumber\":\"" + bid.getTrackingNumber() + "\"}", "fr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"));

        assertNothingRecorded(bid);
    }

    @Test
    void declarationOuverte_messageEnAnglais() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "en")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("handover-sender-noshow-pending"))
                .andExpect(jsonPath("$.detail").value(
                        "You reported the sender as absent: the handover is blocked until this report is lifted."
                                + " Contact support if the sender did show up."));
    }

    @Test
    void declarationLevee_scanDepart_201_colisRemis() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);
        seedSenderNoShow(bid, CancellationStatus.RESOLVED);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("DEPART"));

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
    }

    @Test
    void aucuneDeclaration_scanDepart_201() throws Exception {
        BidEntity bid = seedBid(BidStatus.ACCEPTED);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"DEPART\"}", "fr")
                .andExpect(status().isCreated());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
    }

    @Test
    void declarationOuverte_neBloquePasLeTransitDUnColisDejaRemis() throws Exception {
        // Ancienne donnée : colis remis avant la porte, déclaration restée ouverte. Le TRANSIT
        // n'est pas une remise, il passe.
        BidEntity bid = seedBid(BidStatus.HANDED_OVER);
        seedSenderNoShow(bid, CancellationStatus.PENDING_CONFIRMATION);

        scan(bid, "{\"bidId\":\"" + bid.getId() + "\",\"eventType\":\"TRANSIT\"}", "fr")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("TRANSIT"));

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
    }

    private ResultActions scan(BidEntity bid, String body, String language) throws Exception {
        return mockMvc.perform(post("/tracking/events")
                .with(authentication(traveler()))
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertNothingRecorded(BidEntity bid) {
        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(after.getConfirmationCode()).isNull();
        assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
    }

    private UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(travelerUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private void seedSenderNoShow(BidEntity bid, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bid.getId());
        c.setCancelledBy(travelerId);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setNoShowStatus(status);
        c.setContestationDeadline(OffsetDateTime.now().plusHours(24));
        cancellationRepository.save(c);
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
        return bidRepository.save(bid);
    }
}
