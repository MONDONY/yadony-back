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

    @Autowired private UserRepository userRepository;
    @Autowired private MobileMoneyPayoutAccountRepository repository;
    @Autowired private MobileMoneyPayoutAccounts accounts;
    @Autowired private AuditService audit;
    @Autowired private PawapayProperties props;
    @Autowired private MessagesResolver messagesResolver;
    @Autowired private JdbcTemplate jdbc;

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
        when(client.activeConfiguration()).thenReturn(conf);
        when(client.predictProvider(CIV_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "ORANGE_CIV", CIV_NUMBER)));
        when(client.predictProvider(SEN_NUMBER))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", SEN_NUMBER)));

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
}
