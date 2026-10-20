package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Régression de sécurité (revue automatique, tâche 4) sur le schéma H2 : l'ancien contrat
 * {@code POST/DELETE /payments/mobile-money/account} écrivait directement les colonnes
 * {@code users.mobile_money_*}. Un voyageur repris (compte XOF dans la table) qui réactivait par
 * l'ancienne route avec un numéro XAF obtenait un résumé XAF seul, décorrélé de la table des comptes
 * : le résumé et le routage du versement divergeaient. Désormais le résumé est toujours recalculé
 * depuis la table, quelle que soit la route. Seuls le client pawaPay, Firebase et le verrou
 * pessimiste (syntaxe PostgreSQL) sont simulés.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MobileMoneyAccountServiceFlowTest {

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "PROVIDER_AUTH", "OPERATIONAL");
    private static final String CIV_NUMBER = "2250701234567";
    private static final String CMR_NUMBER = "237671234567";

    @Autowired private UserRepository userRepository;
    @Autowired private MobileMoneyPayoutAccountRepository repository;
    @Autowired private MobileMoneyPayoutAccounts accounts;
    @Autowired private AuditService audit;
    @Autowired private PawapayProperties props;
    @Autowired private MessagesResolver messagesResolver;

    private final PawapayClient client = mock(PawapayClient.class);
    private MobileMoneyAccountService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class, AdditionalAnswers.delegatesTo(userRepository));
        doAnswer(inv -> userRepository.findById(inv.getArgument(0))).when(users).findByIdForUpdate(any());
        PawapayProviderResolver resolver = new PawapayProviderResolver(client);
        MobileMoneyPayoutAccountService payoutAccounts = new MobileMoneyPayoutAccountService(users, repository, accounts,
                resolver, audit, props, messagesResolver);
        service = new MobileMoneyAccountService(users, mock(FirebaseContactService.class), resolver, payoutAccounts,
                props, messagesResolver);

        Map<String, PawapayProviderConfig> conf = new LinkedHashMap<>();
        conf.put("ORANGE_CIV", new PawapayProviderConfig("ORANGE_CIV", "CIV", "XOF", OK, OK));
        conf.put("MTN_MOMO_CIV", new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, OK));
        conf.put("MTN_MOMO_CMR", new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", OK, OK));
        when(client.activeConfiguration()).thenReturn(conf);
        when(client.predictProvider(CIV_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "ORANGE_CIV", CIV_NUMBER)));
        when(client.predictProvider(CMR_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CMR", "MTN_MOMO_CMR", CMR_NUMBER)));

        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-mm-legacy-flow-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        user = userRepository.saveAndFlush(u);

        // Voyageur repris par la migration V343 : un compte XOF Orange CI, « MTN accepté » coché jadis.
        MobileMoneyPayoutAccountEntity migrated = new MobileMoneyPayoutAccountEntity(user.getId(), "ORANGE_CIV", "CI",
                "XOF", CIV_NUMBER, Msisdn.mask(CIV_NUMBER), Instant.parse("2026-01-02T03:04:05Z"));
        PayoutAccountFixtures.setMigrated(migrated, true);
        PayoutAccountFixtures.setLegacyAcceptedProviders(migrated, "ORANGE_CIV,MTN_MOMO_CIV");
        repository.saveAndFlush(migrated);
        accounts.syncSummary(user);
        userRepository.saveAndFlush(user);
    }

    private void assertSummaryMatchesTheTable(UserEntity reloaded) {
        List<MobileMoneyPayoutAccountEntity> live = accounts.active(user.getId());
        assertThat(reloaded.getMobileMoneyCurrencyList())
                .containsExactlyInAnyOrderElementsOf(live.stream().map(MobileMoneyPayoutAccountEntity::getCurrency).distinct().toList());
        assertThat(reloaded.getMobileMoneyProviderList())
                .containsExactlyInAnyOrderElementsOf(live.stream().map(MobileMoneyPayoutAccountEntity::getProvider).toList());
        for (MobileMoneyPayoutAccountEntity a : live) {
            assertThat(reloaded.canReceiveMobileMoney(a.getCurrency())).isTrue();
        }
    }

    @Test
    void legacyReactivationWithAnXafNumber_keepsTheSummaryInStepWithTheTable_thenLegacyDeleteClosesEveryCurrency() {
        MobileMoneyAccountResponse r = service.activate(user.getId(), "+237 6 71 23 45 67", null);

        assertThat(r.provider()).isEqualTo("MTN_MOMO_CMR");
        assertThat(r.currency()).isEqualTo("XAF");
        assertThat(r.msisdnMasked()).isEqualTo("+237 •••• 67");
        // L'upsert vise le code : le compte XOF repris est toujours là, à côté du nouveau compte XAF.
        assertThat(accounts.active(user.getId())).extracting(MobileMoneyPayoutAccountEntity::getProvider)
                .containsExactly("ORANGE_CIV", "MTN_MOMO_CMR");
        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getMobileMoneyCurrencyList()).contains("XAF");
        assertThat(reloaded.canReceiveMobileMoney("XAF")).isTrue();
        assertSummaryMatchesTheTable(reloaded);
        // Le versement d'un dépôt MTN Cameroun part sur le compte XAF, jamais sur le numéro ivoirien.
        assertThat(accounts.destinationFor(user.getId(), "MTN_MOMO_CMR", "XAF").orElseThrow().msisdn()).isEqualTo(CMR_NUMBER);

        MobileMoneyAccountResponse disabled = service.disable(user.getId());

        assertThat(disabled.status()).isEqualTo("DISABLED");
        reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(accounts.active(user.getId())).isEmpty();
        assertThat(reloaded.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.DISABLED);
        for (String currency : List.of("XOF", "XAF", "EUR")) {
            assertThat(reloaded.canReceiveMobileMoney(currency)).as(currency).isFalse();
        }
    }

    @Test
    void legacyReactivationOnTheMigratedCode_replacesItsNumber_withoutASecondAccount() {
        when(client.predictProvider("2250709999999"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "ORANGE_CIV", "2250709999999")));

        service.activate(user.getId(), "+225 07 09 99 99 99", List.of("MTN_MOMO_CIV", "ORANGE_CIV"));

        List<MobileMoneyPayoutAccountEntity> live = accounts.active(user.getId());
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getProvider()).isEqualTo("ORANGE_CIV");
        assertThat(live.get(0).getMsisdn()).isEqualTo("2250709999999");
        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getMobileMoneyMsisdnMasked()).isEqualTo("+225 •••• 99");
        assertSummaryMatchesTheTable(reloaded);
    }
}
