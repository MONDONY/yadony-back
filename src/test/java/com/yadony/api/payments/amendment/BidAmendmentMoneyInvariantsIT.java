package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AMENDMENT_COMMISSION_DEDUCTED} traité comme {@code SUPPLEMENT_COMMISSION_DEDUCTED} par la
 * sonde d'intégrité de l'argent (INV-03 signe, INV-09 doublons, INV-17 devise), sur un vrai
 * PostgreSQL avec toutes les migrations. Chaque test retrouve ses propres lignes : les tests partagent
 * la base.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class BidAmendmentMoneyInvariantsIT {

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

    @MockitoBean AdminAlertEscalator alerts;

    @Autowired MoneyIntegrityMonitor monitor;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void doublonsDeCommissionEtDeRecreditDeLaModification_inv09_signeNul_inv03() {
        UserEntity traveler = persistUser();
        UUID id = UUID.randomUUID();
        ledgerLine(traveler, "TOP_UP", "10.00", "pi_topup_" + id, null);
        ledgerLine(traveler, "AMENDMENT_COMMISSION_DEDUCTED", "-1.10", "amendment-" + id, null);
        ledgerLine(traveler, "AMENDMENT_COMMISSION_DEDUCTED", "-1.10", "amendment-" + id, null);
        ledgerLine(traveler, "REFUND", "1.10", "amendment-refund-" + id, null);
        ledgerLine(traveler, "REFUND", "1.10", "amendment-refund-" + id, null);
        UUID zero = UUID.randomUUID();
        UUID zeroLine = ledgerLine(traveler, "AMENDMENT_COMMISSION_DEDUCTED", "0.00", "amendment-" + zero, null);

        List<Map<String, Object>> inv09 = rows("INV-09");
        assertThat(inv09).anySatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("AMENDMENT_COMMISSION_DOUBLE");
            assertThat(r.get("ref")).isEqualTo("amendment-" + id + ":EUR");
        });
        assertThat(inv09).anySatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("AMENDMENT_RECREDIT_DOUBLE");
            assertThat(r.get("ref")).isEqualTo("amendment-refund-" + id + ":EUR");
        });
        assertThat(rows("INV-03")).anySatisfy(r -> {
            assertThat(r.get("anomaly")).isEqualTo("SIGNE_INCOHERENT_AVEC_TYPE");
            assertThat(r.get("ref_id")).isEqualTo(zeroLine.toString());
        });
    }

    @Test
    void uneSeuleLigneDeCommissionParModification_aucuneAnomalie() {
        UserEntity traveler = persistUser();
        UUID id = UUID.randomUUID();
        ledgerLine(traveler, "TOP_UP", "10.00", "pi_topup_" + id, null);
        UUID debit = ledgerLine(traveler, "AMENDMENT_COMMISSION_DEDUCTED", "-1.10", "amendment-" + id, null);
        ledgerLine(traveler, "REFUND", "1.10", "amendment-refund-" + id, null);

        assertThat(rows("INV-09")).noneSatisfy(r -> assertThat(String.valueOf(r.get("ref"))).contains(id.toString()));
        assertThat(rows("INV-03")).noneSatisfy(r -> assertThat(r.get("ref_id")).isEqualTo(debit.toString()));
    }

    @Test
    void commissionDeLaModificationDansUneAutreDeviseQueLeColis_inv17() {
        UserEntity traveler = persistUser();
        UUID bidId = bid(traveler, persistUser(), "XOF");
        UUID wrong = ledgerLine(traveler, "AMENDMENT_COMMISSION_DEDUCTED", "-1.10", "amendment-" + UUID.randomUUID(), bidId);

        assertThat(rows("INV-17")).anySatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("COMMISSION_LEDGER_VS_BID");
            assertThat(r.get("ref_id")).isEqualTo(wrong.toString());
        });
    }

    private List<Map<String, Object>> rows(String code) {
        return monitor.inspect(code, MoneyIntegrityMonitor.MAX_INSPECT_ROWS).orElseThrow().rows();
    }

    private UUID ledgerLine(UserEntity user, String type, String amount, String paymentRef, UUID bidId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after, payment_ref, "
                        + "bid_id, idempotency_key, created_at) VALUES (?, ?, 'EUR', ?, ?, 10.00, ?, ?, ?, now())",
                id, user.getId(), type, new BigDecimal(amount), paymentRef, bidId, "it-" + id);
        return id;
    }

    private UUID bid(UserEntity traveler, UserEntity sender, String currency) {
        return tx.execute(st -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(traveler.getId());
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
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcement.getId());
            bid.setSenderId(sender.getId());
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency(currency);
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-inv-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }
}
