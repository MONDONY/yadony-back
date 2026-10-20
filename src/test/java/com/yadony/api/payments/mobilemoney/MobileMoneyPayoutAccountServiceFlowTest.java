package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bout en bout sur le schéma H2 : chaque écriture du service est vue par le recalcul du résumé
 * ({@code syncSummary} relit la table dans la même transaction), jusqu'au dernier compte retiré
 * (Review Focus 4 côté API). Seuls le client pawaPay et le verrou pessimiste (syntaxe PostgreSQL)
 * sont simulés ; le contexte Spring est celui du test du dépôt (aucun bean remplacé), pour ne pas en
 * démarrer un de plus.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MobileMoneyPayoutAccountServiceFlowTest {

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "PROVIDER_AUTH", "OPERATIONAL");
    private static final String CIV_NUMBER = "2250701234567";
    private static final String SEN_NUMBER = "221771234567";
    private static final String CIV_MTN_NUMBER = "2250501234588";

    @Autowired private UserRepository userRepository;
    @Autowired private MobileMoneyPayoutAccountRepository repository;
    @Autowired private MobileMoneyPayoutAccounts accounts;
    @Autowired private AuditService audit;
    @Autowired private PawapayProperties props;
    @Autowired private MessagesResolver messagesResolver;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Autowired private com.yadony.api.matching.BidRepository bidRepository;

    private final PawapayClient client = mock(PawapayClient.class);
    private MobileMoneyPayoutAccountService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        // H2 ne connaît pas le « FOR NO KEY UPDATE » du verrou pessimiste PostgreSQL : seul
        // findByIdForUpdate est remplacé par findById, tout le reste va au vrai dépôt.
        UserRepository users = mock(UserRepository.class, AdditionalAnswers.delegatesTo(userRepository));
        doAnswer(inv -> userRepository.findById(inv.getArgument(0))).when(users).findByIdForUpdate(any());
        service = new MobileMoneyPayoutAccountService(users, repository, accounts,
                new PawapayProviderResolver(client), audit, props, messagesResolver);
        Map<String, PawapayProviderConfig> conf = new LinkedHashMap<>();
        conf.put("ORANGE_CIV", new PawapayProviderConfig("ORANGE_CIV", "CIV", "XOF", OK, OK));
        conf.put("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK));
        conf.put("MTN_MOMO_CIV", new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, OK));
        when(client.activeConfiguration()).thenReturn(conf);
        when(client.predictProvider(CIV_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "ORANGE_CIV", CIV_NUMBER)));
        when(client.predictProvider(SEN_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", SEN_NUMBER)));
        org.mockito.Mockito.lenient().when(client.predictProvider(CIV_MTN_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "MTN_MOMO_CIV", CIV_MTN_NUMBER)));

        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-mm-flow-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        user = userRepository.saveAndFlush(u);
    }

    @Test
    void addTwoCountries_thenRemoveBoth_keepsTheSummaryInStepWithTheTable() {
        AccountView civ = service.add(user.getId(), "+225 07 01 23 45 67", "ORANGE_CIV");
        AccountView sen = service.add(user.getId(), "+221 77 123 45 67", "ORANGE_SEN");

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(reloaded.getMobileMoneyProviderList()).containsExactlyInAnyOrder("ORANGE_CIV", "ORANGE_SEN");
        assertThat(reloaded.getMobileMoneyCurrencyList()).containsExactly("XOF");
        assertThat(reloaded.canReceiveMobileMoney("XOF")).isTrue();
        assertThat(service.list(user.getId()).accounts()).extracting(AccountView::country).containsExactlyInAnyOrder("CI", "SN");
        assertThat(jdbc.queryForObject("SELECT msisdn FROM mobile_money_payout_accounts WHERE id = ?", String.class, civ.id()))
                .doesNotContain("0701234567");

        service.remove(user.getId(), civ.id());
        reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getMobileMoneyProviderList()).containsExactly("ORANGE_SEN");

        service.remove(user.getId(), sen.id());
        reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.DISABLED);
        assertThat(reloaded.getMobileMoneyProviderList()).isEmpty();
        assertThat(reloaded.canReceiveMobileMoney("XOF")).isFalse();
        assertThat(service.list(user.getId()).accounts()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action LIKE 'MM_PAYOUT_ACCOUNT_%'",
                Integer.class, user.getId())).isEqualTo(4);
    }

    // ── Verrou du compte repris (repli de versement des réseaux cochés sur l'ancien compte) ──────

    /** Compte repris par V343 : ORANGE_CIV, « MTN accepté » coché sur l'ancien compte unique. */
    private UUID migratedOrangeAccount() {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(user.getId(), "ORANGE_CIV", "CI", "XOF",
                CIV_NUMBER, "+225 •••• 67", java.time.Instant.parse("2026-03-04T10:11:12Z"));
        PayoutAccountFixtures.setMigrated(a, true);
        PayoutAccountFixtures.setLegacyAcceptedProviders(a, "ORANGE_CIV,MTN_MOMO_CIV");
        UUID id = repository.saveAndFlush(a).getId();
        UserEntity u = userRepository.findById(user.getId()).orElseThrow();
        accounts.syncSummary(u);
        userRepository.saveAndFlush(u);
        return id;
    }

    /** Colis du voyageur payé en MTN avant la bascule (séquestre, dépôt abouti, pas encore versé). */
    private void mtnParcelAwaitingPayout() {
        com.yadony.api.matching.AnnouncementEntity a = new com.yadony.api.matching.AnnouncementEntity();
        a.setTravelerId(user.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Abidjan");
        a.setDepartureDate(java.time.LocalDate.now().plusDays(7));
        a.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Abidjan Plateau");
        a.setDeliveryLat(new BigDecimal("5.320000"));
        a.setDeliveryLng(new BigDecimal("-4.020000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        com.yadony.api.matching.BidEntity b = new com.yadony.api.matching.BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(UUID.randomUUID());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(com.yadony.api.matching.BidStatus.ACCEPTED);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+225 07 00 00 00 01");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        UUID bid = bidRepository.saveAndFlush(b).getId();

        UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, rail, amount, currency, commission_amount, status, "
                + "legacy_destination_charge, disputed, created_at, updated_at) VALUES (?, ?, 'PAWAPAY', 15000, 'XOF', "
                + "1800, 'ESCROW', false, false, NOW(), NOW())", payment, bid);
        jdbc.update("INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, msisdn, "
                + "msisdn_masked, payment_id, purpose, topup_guard, created_at, updated_at) VALUES (?, 'DEPOSIT', "
                + "'COMPLETED', 15000, 'XOF', 'MTN_MOMO_CIV', 'CI', 'enc', '+225 ** 88', ?, 'BID_PAYMENT', true, NOW(), "
                + "NOW())", UUID.randomUUID(), payment);
    }

    private static void assertLocked(Throwable thrown, int pending) {
        assertThat(thrown).isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
        com.yadony.api.common.YadonyBusinessException e = (com.yadony.api.common.YadonyBusinessException) thrown;
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(e.getProperties()).containsEntry("pendingPayouts", pending);
    }

    /**
     * Le colis MTN payé avant la bascule sera versé sur le compte repris (repli) : ce compte est
     * verrouillé, retrait comme changement de numéro, et sa vue le compte.
     */
    @Test
    void migratedAccount_isLockedByAParcelPaidOnALegacyCodeWithoutAccount() {
        UUID orange = migratedOrangeAccount();
        mtnParcelAwaitingPayout();

        assertThat(service.list(user.getId()).accounts()).singleElement()
                .extracting(AccountView::pendingPayouts).isEqualTo(1);
        assertLocked(org.assertj.core.api.Assertions.catchThrowable(() -> service.remove(user.getId(), orange)), 1);
        assertLocked(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.changeNumber(user.getId(), orange, "+225 07 01 23 45 67")), 1);
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(orange, user.getId())).isPresent();
    }

    /** Dès qu'un compte MTN existe, le colis MTN sera versé dessus : il verrouille ce compte, plus le repris. */
    @Test
    void onceALegacyCodeHasItsOwnAccount_theParcelLocksThatAccount_notTheMigratedOne() {
        UUID orange = migratedOrangeAccount();
        mtnParcelAwaitingPayout();
        AccountView mtn = service.add(user.getId(), "+225 05 01 23 45 88", "MTN_MOMO_CIV");

        assertThat(service.list(user.getId()).accounts()).extracting(AccountView::provider, AccountView::pendingPayouts)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ORANGE_CIV", 0),
                        org.assertj.core.groups.Tuple.tuple("MTN_MOMO_CIV", 1));
        assertLocked(org.assertj.core.api.Assertions.catchThrowable(() -> service.remove(user.getId(), mtn.id())), 1);
        service.remove(user.getId(), orange);
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(orange, user.getId())).isEmpty();
    }
}
