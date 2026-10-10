package com.yadony.api.payments.overview;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /payments/me/overview} sur PostgreSQL embarqué, migrations Flyway appliquées : la
 * requête du read-model est exécutée telle qu'en production (FLUTTER-HV).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class MoneyOverviewControllerIT {

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
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MoneyOverviewReadModel readModel;

    @Test
    void withoutToken_is401() throws Exception {
        mockMvc.perform(get("/payments/me/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userWithoutAnyParcel_getsEmptySections_andWalletBalances() throws Exception {
        UserEntity me = persistUser("Moussa", "Keita");
        wallet(me.getId(), "EUR", "12.50");
        wallet(me.getId(), "XOF", "5000");

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(me))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.recentWindowDays").value(30))
                .andExpect(jsonPath("$.wallet", hasSize(2)))
                .andExpect(jsonPath("$.wallet[0].currency").value("EUR"))
                .andExpect(jsonPath("$.wallet[0].balance").value(12.5))
                .andExpect(jsonPath("$.wallet[1].currency").value("XOF"))
                .andExpect(jsonPath("$.traveler.items", hasSize(0)))
                .andExpect(jsonPath("$.traveler.totals", hasSize(0)))
                .andExpect(jsonPath("$.sender.items", hasSize(0)));
    }

    @Test
    void wallet_putsTheActiveCurrencyFirst_thenOthersByBalance() throws Exception {
        UserEntity me = persistUser("Awa", "Traoré");
        wallet(me.getId(), "CAD", "0");
        wallet(me.getId(), "EUR", "4.00");
        wallet(me.getId(), "XOF", "5000");
        wallet(me.getId(), "USD", "7.25");
        activeCurrency(me.getId(), "USD");

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCurrency").value("USD"))
                .andExpect(jsonPath("$.wallet", hasSize(4)))
                .andExpect(jsonPath("$.wallet[0].currency").value("USD"))
                .andExpect(jsonPath("$.wallet[0].balance").value(7.25))
                .andExpect(jsonPath("$.wallet[1].currency").value("XOF"))
                .andExpect(jsonPath("$.wallet[2].currency").value("EUR"))
                .andExpect(jsonPath("$.wallet[3].currency").value("CAD"));
    }

    @Test
    void wallet_activeCurrencyWithoutAccount_isShownFirstAtZero_withoutBeingCreated() throws Exception {
        UserEntity me = persistUser("Ibrahim", "Cissé");
        wallet(me.getId(), "CAD", "0");
        activeCurrency(me.getId(), "USD");

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCurrency").value("USD"))
                .andExpect(jsonPath("$.wallet", hasSize(2)))
                .andExpect(jsonPath("$.wallet[0].currency").value("USD"))
                .andExpect(jsonPath("$.wallet[0].balance").value(0))
                .andExpect(jsonPath("$.wallet[1].currency").value("CAD"));
        Integer accounts = jdbc.queryForObject(
                "SELECT count(*) FROM wallet_accounts WHERE user_id = ?", Integer.class, me.getId());
        org.assertj.core.api.Assertions.assertThat(accounts).isEqualTo(1);
    }

    @Test
    void traveler_seesEscrowDisputeHoldReleasedAndCash_perCurrency() throws Exception {
        UserEntity traveler = persistUser("Awa", "Traore");
        UserEntity sender = persistUser("Ibrahima", "Sow");
        UUID eurTrip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Dakar");
        UUID xofTrip = persistAnnouncement(traveler.getId(), "XOF", "Paris", "Abidjan");

        UUID escrowBid = persistBid(eurTrip, sender.getId(), BidStatus.IN_TRANSIT, PaymentMethod.STRIPE, "EUR");
        persistPayment(escrowBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "100.00", "12.00", "EUR", null);

        UUID disputedBid = persistBid(eurTrip, sender.getId(), BidStatus.ARRIVED, PaymentMethod.STRIPE, "EUR");
        persistPayment(disputedBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "50.00", "6.00", "EUR", null);
        persistDispute(disputedBid, sender.getId(), traveler.getId(), "OPEN");

        OffsetDateTime holdUntil = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).truncatedTo(ChronoUnit.SECONDS);
        UUID heldBid = persistBid(xofTrip, sender.getId(), BidStatus.ARRIVED, PaymentMethod.MOBILE_MONEY, "XOF");
        persistPayment(heldBid, PaymentStatus.ESCROW, PaymentRail.PAWAPAY, "20000", "2400", "XOF", null);
        persistRecipientNoShow(heldBid, traveler.getId(), holdUntil);

        UUID releasedBid = persistBid(eurTrip, sender.getId(), BidStatus.COMPLETED, PaymentMethod.STRIPE, "EUR");
        persistPayment(releasedBid, PaymentStatus.RELEASED, PaymentRail.STRIPE, "30.00", "3.60", "EUR",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(3));

        UUID oldReleasedBid = persistBid(eurTrip, sender.getId(), BidStatus.COMPLETED, PaymentMethod.STRIPE, "EUR");
        UUID oldPayment = persistPayment(oldReleasedBid, PaymentStatus.RELEASED, PaymentRail.STRIPE, "80.00", "9.60",
                "EUR", LocalDateTime.now(ZoneOffset.UTC).minusDays(60));
        jdbc.update("UPDATE payments SET updated_at = now() - interval '60 days' WHERE id = ?", oldPayment);

        UUID refundedBid = persistBid(eurTrip, sender.getId(), BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR");
        persistPayment(refundedBid, PaymentStatus.REFUNDED, PaymentRail.STRIPE, "40.00", "4.80", "EUR", null);

        UUID cashBid = persistBid(xofTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.CASH, "XOF");
        jdbc.update("UPDATE bids SET commission_status = 'CHARGED' WHERE id = ?", cashBid);
        persistBid(xofTrip, sender.getId(), BidStatus.PENDING, PaymentMethod.CASH, "XOF");

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler.items", hasSize(5)))
                .andExpect(jsonPath("$.traveler.items[0].state").value("IN_DISPUTE"))
                .andExpect(jsonPath("$.traveler.items[0].releaseCondition").value("ADMIN_DECISION"))
                .andExpect(jsonPath("$.traveler.items[0].bidId").value(disputedBid.toString()))
                .andExpect(jsonPath("$.traveler.items[0].counterpartyName").value("Ibrahima S."))
                .andExpect(jsonPath("$.traveler.items[1].state").value("RELEASE_SCHEDULED"))
                .andExpect(jsonPath("$.traveler.items[1].releaseCondition").value("AUTO_RELEASE_AFTER_HOLD_IF_NO_DISPUTE"))
                .andExpect(jsonPath("$.traveler.items[1].releaseAt").value(holdUntil.toString()))
                .andExpect(jsonPath("$.traveler.items[1].channel").value("MOBILE_MONEY"))
                .andExpect(jsonPath("$.traveler.items[1].amount").value(17600))
                .andExpect(jsonPath("$.traveler.items[2].state").value("AWAITING_DELIVERY_CONFIRMATION"))
                .andExpect(jsonPath("$.traveler.items[2].releaseCondition").value("ON_DELIVERY_CONFIRMATION"))
                .andExpect(jsonPath("$.traveler.items[2].amount").value(88.0))
                .andExpect(jsonPath("$.traveler.items[2].releaseAt").doesNotExist())
                .andExpect(jsonPath("$.traveler.items[2].departureCity").value("Paris"))
                .andExpect(jsonPath("$.traveler.items[2].arrivalCity").value("Dakar"))
                .andExpect(jsonPath("$.traveler.items[2].trackingNumber").exists())
                .andExpect(jsonPath("$.traveler.items[2].bidStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.traveler.items[2].weightKg").value(5.0))
                .andExpect(jsonPath("$.traveler.items[2].cashCommissionStatus").doesNotExist())
                .andExpect(jsonPath("$.traveler.items[3].state").value("CASH"))
                .andExpect(jsonPath("$.traveler.items[3].bidId").value(cashBid.toString()))
                .andExpect(jsonPath("$.traveler.items[3].amount").doesNotExist())
                .andExpect(jsonPath("$.traveler.items[3].releaseCondition").value("CASH_IN_PERSON"))
                .andExpect(jsonPath("$.traveler.items[3].cashCommissionStatus").value("CHARGED"))
                .andExpect(jsonPath("$.traveler.items[4].state").value("RELEASED_RECENTLY"))
                .andExpect(jsonPath("$.traveler.items[4].bidId").value(releasedBid.toString()))
                .andExpect(jsonPath("$.traveler.items[4].settledAt").exists())
                .andExpect(jsonPath("$.traveler.totals", hasSize(2)))
                .andExpect(jsonPath("$.traveler.totals[0].currency").value("EUR"))
                .andExpect(jsonPath("$.traveler.totals[0].upcoming").value(132.0))
                .andExpect(jsonPath("$.traveler.totals[0].releasedRecently").value(26.4))
                .andExpect(jsonPath("$.traveler.totals[1].currency").value("XOF"))
                .andExpect(jsonPath("$.traveler.totals[1].upcoming").value(17600))
                .andExpect(jsonPath("$.sender.items", hasSize(0)));

        // Côté expéditeur : son argent bloqué, et le remboursement récent ; jamais le cash en montant.
        mockMvc.perform(get("/payments/me/overview").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler.items", hasSize(0)))
                .andExpect(jsonPath("$.sender.items[*].state", containsInAnyOrder(
                        "IN_DISPUTE", "RELEASE_SCHEDULED", "AWAITING_DELIVERY_CONFIRMATION", "CASH",
                        "REFUNDED_RECENTLY")))
                .andExpect(jsonPath("$.sender.items[0].counterpartyName").value("Awa T."))
                .andExpect(jsonPath("$.sender.totals[0].currency").value("EUR"))
                .andExpect(jsonPath("$.sender.totals[0].blocked").value(150.0))
                .andExpect(jsonPath("$.sender.totals[0].refundedRecently").value(40.0))
                .andExpect(jsonPath("$.sender.totals[1].currency").value("XOF"))
                .andExpect(jsonPath("$.sender.totals[1].blocked").value(20000));
    }

    @Test
    void outsider_neverSeesSomeoneElsesMoney() throws Exception {
        UserEntity traveler = persistUser("Fatou", "Ndiaye");
        UserEntity sender = persistUser("Ousmane", "Ba");
        UserEntity outsider = persistUser("Kofi", "Mensah");
        UUID trip = persistAnnouncement(traveler.getId(), "EUR", "Lyon", "Bamako");
        UUID bid = persistBid(trip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(bid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "60.00", "7.20", "EUR", null);

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(outsider))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler.items", hasSize(0)))
                .andExpect(jsonPath("$.sender.items", hasSize(0)))
                .andExpect(jsonPath("$.wallet", hasSize(1)))
                .andExpect(jsonPath("$.wallet[0].currency").value("EUR"))
                .andExpect(jsonPath("$.wallet[0].balance").value(0));
    }

    @Test
    void softDeletedParcelOrTrip_withEscrow_isNeverListed() throws Exception {
        UserEntity traveler = persistUser("Aissata", "Camara");
        UserEntity sender = persistUser("Boubacar", "Sylla");
        UUID trip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Conakry");
        UUID deletedBid = persistBid(trip, sender.getId(), BidStatus.IN_TRANSIT, PaymentMethod.STRIPE, "EUR");
        persistPayment(deletedBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "70.00", "8.40", "EUR", null);
        jdbc.update("UPDATE bids SET deleted_at = now() WHERE id = ?", deletedBid);

        UUID deletedTrip = persistAnnouncement(traveler.getId(), "EUR", "Lyon", "Dakar");
        UUID bidOnDeletedTrip = persistBid(deletedTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(bidOnDeletedTrip, PaymentStatus.ESCROW, PaymentRail.STRIPE, "30.00", "3.60", "EUR", null);
        jdbc.update("UPDATE announcements SET deleted_at = now() WHERE id = ?", deletedTrip);

        UUID visibleBid = persistBid(trip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(visibleBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "10.00", "1.20", "EUR", null);

        for (UserEntity user : List.of(traveler, sender)) {
            mockMvc.perform(get("/payments/me/overview").with(authentication(as(user))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traveler.items[*].bidId",
                            user == traveler ? hasSize(1) : hasSize(0)))
                    .andExpect(jsonPath("$.sender.items[*].bidId",
                            user == sender ? hasSize(1) : hasSize(0)))
                    .andExpect(jsonPath(user == traveler ? "$.traveler.items[0].bidId" : "$.sender.items[0].bidId")
                            .value(visibleBid.toString()));
        }
    }

    @Test
    void mobileMoneyPayoutAndRefundInFlight_areReported() throws Exception {
        UserEntity traveler = persistUser("Mariam", "Coulibaly");
        UserEntity sender = persistUser("Seydou", "Diarra");
        UUID trip = persistAnnouncement(traveler.getId(), "XOF", "Marseille", "Douala");

        UUID paidOutBid = persistBid(trip, sender.getId(), BidStatus.COMPLETED, PaymentMethod.MOBILE_MONEY, "XOF");
        UUID paidOut = persistPayment(paidOutBid, PaymentStatus.RELEASED, PaymentRail.PAWAPAY, "10000", "1200", "XOF",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        persistPawapayOperation(paidOut, PawapayOperationKind.PAYOUT, "8800");

        UUID refundingBid = persistBid(trip, sender.getId(), BidStatus.CANCELLED, PaymentMethod.MOBILE_MONEY, "XOF");
        UUID refunding = persistPayment(refundingBid, PaymentStatus.ESCROW, PaymentRail.PAWAPAY, "5000", "600", "XOF", null);
        persistPawapayOperation(refunding, PawapayOperationKind.REFUND, "5000");

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler.items[*].state", containsInAnyOrder(
                        "PAYOUT_IN_PROGRESS", "REFUND_PENDING")))
                .andExpect(jsonPath("$.traveler.totals[0].upcoming").value(8800));

        mockMvc.perform(get("/payments/me/overview").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender.items[*].state", containsInAnyOrder(
                        "PAYOUT_IN_PROGRESS", "REFUND_PENDING")))
                .andExpect(jsonPath("$.sender.totals[0].refundPending").value(5000))
                .andExpect(jsonPath("$.sender.totals[0].blocked").value(0));
    }

    @Test
    void arrivalDate_isTheTripArrival_orItsDepartureDay_andTruncatedIsFalse() throws Exception {
        UserEntity traveler = persistUser("Kadiatou", "Barry");
        UserEntity sender = persistUser("Lamine", "Fofana");
        UUID sameDayTrip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Dakar");
        UUID overnightTrip = persistAnnouncement(traveler.getId(), "EUR", "Lyon", "Abidjan");
        jdbc.update("UPDATE announcements SET arrival_date = departure_date + 1 WHERE id = ?", overnightTrip);

        UUID sameDayBid = persistBid(sameDayTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(sameDayBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "20.00", "2.40", "EUR", null);
        UUID overnightBid = persistBid(overnightTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(overnightBid, PaymentStatus.ESCROW, PaymentRail.STRIPE, "30.00", "3.60", "EUR", null);

        LocalDate departure = LocalDate.now().plusDays(10);
        mockMvc.perform(get("/payments/me/overview").with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.traveler.items[?(@.bidId == '" + sameDayBid + "')].arrivalDate")
                        .value(departure.toString()))
                .andExpect(jsonPath("$.traveler.items[?(@.bidId == '" + overnightBid + "')].arrivalDate")
                        .value(departure.plusDays(1).toString()))
                .andExpect(jsonPath("$.traveler.items[?(@.bidId == '" + overnightBid + "')].departureDate")
                        .value(departure.toString()));
    }

    @Test
    void beforeTheCap_activeMoneyComesFirst_nearestArrivalFirst_historyLast() {
        UserEntity traveler = persistUser("Oumou", "Sangare");
        UserEntity sender = persistUser("Daouda", "Kone");
        UUID nearTrip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Bamako");
        UUID farTrip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Douala");
        UUID recentTrip = persistAnnouncement(traveler.getId(), "EUR", "Paris", "Dakar");
        jdbc.update("UPDATE announcements SET departure_date = current_date + 30 WHERE id = ?", farTrip);
        jdbc.update("UPDATE announcements SET departure_date = current_date + 60 WHERE id = ?", recentTrip);

        UUID released = persistBid(recentTrip, sender.getId(), BidStatus.COMPLETED, PaymentMethod.STRIPE, "EUR");
        persistPayment(released, PaymentStatus.RELEASED, PaymentRail.STRIPE, "10.00", "1.20", "EUR",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        UUID far = persistBid(farTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(far, PaymentStatus.ESCROW, PaymentRail.STRIPE, "10.00", "1.20", "EUR", null);
        UUID near = persistBid(nearTrip, sender.getId(), BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR");
        persistPayment(near, PaymentStatus.ESCROW, PaymentRail.STRIPE, "10.00", "1.20", "EUR", null);

        List<MoneyRow> rows = readModel.findRows(traveler.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), 3);
        org.assertj.core.api.Assertions.assertThat(rows).extracting(MoneyRow::bidId).containsExactly(near, far, released);

        List<MoneyRow> cut = readModel.findRows(traveler.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), 2);
        org.assertj.core.api.Assertions.assertThat(cut).extracting(MoneyRow::bidId).containsExactly(near, far);
    }

    // --- helpers ---

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"), new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UserEntity persistUser(String firstName, String lastName) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("money-overview-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return userRepository.saveAndFlush(user);
    }

    private void activeCurrency(UUID userId, String currency) {
        tx.executeWithoutResult(s -> {
            UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
            prefs.setUserId(userId);
            prefs.setCurrencyCode(currency);
            entityManager.persist(prefs);
        });
    }

    private void wallet(UUID userId, String currency, String balance) {
        tx.executeWithoutResult(s -> {
            WalletAccountEntity w = new WalletAccountEntity();
            w.setUserId(userId);
            w.setCurrency(currency);
            w.setBalance(new BigDecimal(balance));
            entityManager.persist(w);
        });
    }

    private UUID persistAnnouncement(UUID travelerId, String currency, String from, String to) {
        return tx.execute(status -> {
            AnnouncementEntity a = new AnnouncementEntity();
            a.setTravelerId(travelerId);
            a.setDepartureCity(from);
            a.setArrivalCity(to);
            a.setDepartureDate(LocalDate.now().plusDays(10));
            a.setTransportMode(TransportMode.PLANE);
            a.setPickupAddressLabel("Départ");
            a.setPickupLat(new BigDecimal("48.880756"));
            a.setPickupLng(new BigDecimal("2.354987"));
            a.setDeliveryAddressLabel("Arrivée");
            a.setDeliveryLat(new BigDecimal("14.670833"));
            a.setDeliveryLng(new BigDecimal("-17.073056"));
            a.setAvailableKg(new BigDecimal("20.00"));
            a.setTotalKg(new BigDecimal("23.00"));
            a.setPricePerKg(new BigDecimal("20.00"));
            a.setCurrency(currency);
            a.setTimezone("Europe/Paris");
            a.setStatus(AnnouncementStatus.ACTIVE);
            a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.STRIPE, PaymentMethod.MOBILE_MONEY));
            entityManager.persist(a);
            return a.getId();
        });
    }

    private UUID persistBid(UUID announcementId, UUID senderId, BidStatus status, PaymentMethod method,
                            String currency) {
        return tx.execute(s -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(method);
            bid.setCurrency(currency);
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(status);
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }

    private UUID persistPayment(UUID bidId, PaymentStatus status, PaymentRail rail, String amount, String commission,
                                String currency, LocalDateTime releasedAt) {
        return tx.execute(s -> {
            PaymentEntity p = new PaymentEntity();
            p.setBidId(bidId);
            p.setRail(rail);
            p.setStripePaymentIntentId(rail == PaymentRail.STRIPE ? "pi_" + UUID.randomUUID() : null);
            p.setAmount(new BigDecimal(amount));
            p.setCommissionAmount(new BigDecimal(commission));
            p.setCurrency(currency);
            p.setStatus(status);
            p.setEscrowReleasedAt(releasedAt);
            entityManager.persist(p);
            return p.getId();
        });
    }

    private void persistDispute(UUID bidId, UUID senderId, UUID travelerId, String status) {
        tx.executeWithoutResult(s -> {
            DisputeEntity d = new DisputeEntity();
            d.setBidId(bidId);
            d.setSenderId(senderId);
            d.setTravelerId(travelerId);
            d.setType("DAMAGED");
            d.setStatus(status);
            entityManager.persist(d);
        });
    }

    private void persistRecipientNoShow(UUID bidId, UUID travelerId, OffsetDateTime holdUntil) {
        tx.executeWithoutResult(s -> {
            CancellationEntity c = new CancellationEntity();
            c.setBidId(bidId);
            c.setCancelledBy(travelerId);
            c.setReason("RECIPIENT_NO_SHOW");
            c.setScope(CancellationScope.DELIVERY);
            c.setNoShowStatus(CancellationStatus.CONFIRMED);
            c.setHoldUntil(holdUntil);
            entityManager.persist(c);
        });
    }

    private void persistPawapayOperation(UUID paymentId, PawapayOperationKind kind, String amount) {
        tx.executeWithoutResult(s -> entityManager.persist(new PawapayOperationEntity(UUID.randomUUID(), kind,
                PawapayOperationPurpose.BID_PAYMENT, null, paymentId, null, new BigDecimal(amount), "XOF",
                "ORANGE_CIV", "CI", "+2250700000000")));
    }
}
