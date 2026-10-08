package com.yadony.api.cancellation;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-E4/E0/E6, de bout en bout côté HTTP contre une vraie base PostgreSQL (migrations
 * Flyway jouées, dont V298) et avec de vrais commits : les écouteurs de fiabilité et de
 * commission sont {@code AFTER_COMMIT}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class CancellationReliabilityIT {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private CashCommissionService cashCommissionService;
    @Autowired private BidRepository bidRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void travelerCancelsAcceptedCashParcel_keepsNoCommission_andCountsOneCancellation() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        openWallet(traveler.getId());
        UUID bidId = acceptedCashBid(persistAnnouncement(traveler.getId()), sender.getId(), traveler.getId());

        mockMvc.perform(put("/bids/{id}/cancel", bidId).with(authentication(as(traveler))))
                .andExpect(status().isOk());

        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        assertThat(auditCount(bidId, "COMMISSION_RETAINED_TRAVELER_CANCEL")).isEqualTo(1);
        assertThat(userRepository.findById(traveler.getId()).orElseThrow().getCancellationCount()).isEqualTo(1);
        assertThat(userRepository.findById(sender.getId()).orElseThrow().getSenderCancellationCount()).isZero();
    }

    @Test
    void senderCancelsAcceptedCashParcel_commissionRefunded_andSenderCounterShownToTravelers() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        openWallet(traveler.getId());
        UUID bidId = acceptedCashBid(persistAnnouncement(traveler.getId()), sender.getId(), traveler.getId());

        mockMvc.perform(put("/bids/{id}/cancel", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk());

        assertThat(bidRepository.findById(bidId).orElseThrow().getCommissionStatus())
                .isEqualTo(CommissionStatus.REFUNDED);
        assertThat(userRepository.findById(sender.getId()).orElseThrow().getSenderCancellationCount()).isEqualTo(1);
        assertThat(userRepository.findById(traveler.getId()).orElseThrow().getCancellationCount()).isZero();

        mockMvc.perform(get("/users/{id}/profile-public", sender.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderIncidentCount").value(1));
    }

    @Test
    void senderWithdrawsPendingRequest_countsNothing() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID bidId = persistBid(persistAnnouncement(traveler.getId()), sender.getId());

        mockMvc.perform(put("/bids/{id}/cancel", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderIncidentCount").value(0));

        assertThat(userRepository.findById(sender.getId()).orElseThrow().getSenderCancellationCount()).isZero();
    }

    @Test
    void travelerCancelsWholeTripWithTwoParcels_countsOnce_andKeepsBothCommissions() throws Exception {
        UserEntity traveler = persistUser();
        openWallet(traveler.getId());
        UUID announcementId = persistAnnouncement(traveler.getId());
        UUID first = acceptedCashBid(announcementId, persistUser().getId(), traveler.getId());
        UUID second = acceptedCashBid(announcementId, persistUser().getId(), traveler.getId());

        mockMvc.perform(post("/cancellations").with(authentication(as(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcementId\":\"" + announcementId + "\",\"reason\":\"Imprévu\"}"))
                .andExpect(status().isCreated());

        assertThat(userRepository.findById(traveler.getId()).orElseThrow().getCancellationCount()).isEqualTo(1);
        for (UUID bidId : List.of(first, second)) {
            assertThat(bidRepository.findById(bidId).orElseThrow().getCommissionStatus())
                    .isEqualTo(CommissionStatus.CHARGED);
            assertThat(auditCount(bidId, "COMMISSION_RETAINED_TRAVELER_CANCEL")).isEqualTo(1);
        }
    }

    /**
     * Le rattrapage de V298 rejoué sur des traces d'audit réelles : seule l'annulation par
     * l'expéditeur d'un colis déjà accepté compte.
     */
    @Test
    void v298Backfill_countsOnlySenderCancellationsAfterAcceptance() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID announcementId = persistAnnouncement(traveler.getId());
        UUID accepted = persistBid(announcementId, sender.getId());
        UUID pending = persistBid(announcementId, sender.getId());
        UUID byTraveler = persistBid(announcementId, sender.getId());
        audit(accepted, "BID_ACCEPTED", traveler.getId(), "{}", "1 hour");
        audit(accepted, "BID_CANCELLED", sender.getId(), "{\"actor\":\"SENDER\"}", "1 minute");
        audit(pending, "BID_CANCELLED", sender.getId(), "{\"actor\":\"SENDER\"}", "1 minute");
        audit(byTraveler, "BID_ACCEPTED", traveler.getId(), "{}", "1 hour");
        audit(byTraveler, "BID_CANCELLED", traveler.getId(), "{\"actor\":\"TRAVELER\"}", "1 minute");

        String migration = new ClassPathResource("db/migration/V298__users_sender_cancellation_count.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.update(migration.substring(migration.indexOf("WITH sender_cancellations")));

        assertThat(userRepository.findById(sender.getId()).orElseThrow().getSenderCancellationCount()).isEqualTo(1);
        assertThat(userRepository.findById(traveler.getId()).orElseThrow().getSenderCancellationCount()).isZero();
    }

    // --- helpers (données commitées : les écouteurs AFTER_COMMIT doivent les voir) ---

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"), new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private int auditCount(UUID entityId, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, entityId, action);
        return n == null ? 0 : n;
    }

    private void audit(UUID bidId, String action, UUID actorId, String payload, String ago) {
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) "
                        + "VALUES ('BID', ?, ?, ?, CAST(? AS jsonb), now() - CAST(? AS interval))",
                bidId, action, actorId, payload, ago);
    }

    private UUID acceptedCashBid(UUID announcementId, UUID senderId, UUID travelerId) {
        UUID bidId = persistBid(announcementId, senderId);
        assertThat(cashCommissionService.acceptCashBid(bidId, travelerId, CommissionSource.WALLET_FIRST).status())
                .isEqualTo(AcceptanceStatusDto.ACCEPTED);
        return bidId;
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("reliability-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private void openWallet(UUID userId) {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("100"));
        walletAccountRepository.saveAndFlush(wallet);
    }

    private UUID persistAnnouncement(UUID travelerId) {
        return tx.execute(status -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(travelerId);
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            announcement.setDepartureDate(LocalDate.now().plusDays(10));
            announcement.setTransportMode(TransportMode.PLANE);
            announcement.setPickupAddressLabel("Gare du Nord, Paris");
            announcement.setPickupLat(new BigDecimal("48.880756"));
            announcement.setPickupLng(new BigDecimal("2.354987"));
            announcement.setDeliveryAddressLabel("Aéroport de Dakar");
            announcement.setDeliveryLat(new BigDecimal("14.670833"));
            announcement.setDeliveryLng(new BigDecimal("-17.073056"));
            announcement.setAvailableKg(new BigDecimal("20.00"));
            announcement.setTotalKg(new BigDecimal("23.00"));
            announcement.setPricePerKg(new BigDecimal("20.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(BidStatus.PENDING);
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
