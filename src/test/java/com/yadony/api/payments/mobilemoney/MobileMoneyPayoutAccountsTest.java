package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts.PayoutDestination;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutAccountsTest {

    private static final Instant CIV_VERIFIED = Instant.parse("2026-03-04T10:11:12Z");
    private static final Instant CMR_VERIFIED = Instant.parse("2026-05-06T07:08:09Z");

    @Mock private MobileMoneyPayoutAccountRepository repository;

    private MobileMoneyPayoutAccounts accounts;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accounts = new MobileMoneyPayoutAccounts(repository);
    }

    private static MobileMoneyPayoutAccountEntity account(String provider, String country, String currency,
                                                          String msisdn, Instant verifiedAt, boolean migrated) {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(UUID.randomUUID(), provider, country,
                currency, msisdn, "masked-" + msisdn.substring(msisdn.length() - 2), verifiedAt);
        a.setMigrated(migrated);
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        return a;
    }

    private void given(MobileMoneyPayoutAccountEntity... live) {
        when(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId)).thenReturn(List.of(live));
    }

    // ── Lecture ──────────────────────────────────────────────────────────────────────────────

    @Test
    void active_returnsTheRepositoryOrder_andActiveIn_filtersByCurrencyIgnoringCase() {
        var civ = account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, false);
        var cmr = account("MTN_MOMO_CMR", "CM", "XAF", "+237670000002", CMR_VERIFIED, false);
        given(civ, cmr);

        assertThat(accounts.active(userId)).containsExactly(civ, cmr);
        assertThat(accounts.activeIn(userId, "xaf")).containsExactly(cmr);
        assertThat(accounts.activeIn(userId, "EUR")).isEmpty();
        assertThat(accounts.activeIn(userId, null)).isEmpty();
    }

    @Test
    void accepts_isExactCode() {
        given(account("ORANGE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false));

        assertThat(accounts.accepts(userId, "ORANGE_CIV", "XOF")).isFalse();
        assertThat(accounts.accepts(userId, "orange_sen", "XOF")).isTrue();
        assertThat(accounts.accepts(userId, "ORANGE_SEN", "XAF")).isFalse();
        assertThat(accounts.accepts(userId, null, "XOF")).isFalse();
    }

    // ── Libellés et refus du payeur ──────────────────────────────────────────────────────────

    @Test
    void acceptedLabels_areTheCurrencyAccountsLabels_deduplicatedAndOldestFirst() {
        given(account("WAVE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false),
                account("ORANGE_SEN", "SN", "XOF", "+221770000002", CIV_VERIFIED, false),
                account("MTN_MOMO_CMR", "CM", "XAF", "+237670000003", CMR_VERIFIED, false));

        assertThat(accounts.acceptedLabels(userId, "XOF")).containsExactly("Wave", "Orange Money");
        assertThat(accounts.acceptedLabels(userId, "XAF")).containsExactly("MTN MoMo");
        assertThat(accounts.acceptedLabels(userId, "EUR")).isEmpty();
        assertThat(accounts.acceptedLabels(userId, null)).isEmpty();
    }

    @Test
    void acceptedLabels_sameBrandInTwoCountries_isSuffixedWithTheCountry() {
        given(account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, false),
                account("ORANGE_SEN", "SN", "XOF", "+221770000002", CIV_VERIFIED, false),
                account("WAVE_SEN", "SN", "XOF", "+221770000004", CIV_VERIFIED, false));

        assertThat(accounts.acceptedLabels(userId, "XOF"))
                .containsExactly("Orange Money (CI)", "Orange Money (SN)", "Wave");
    }

    @Test
    void payerRefusal_isEmptyWhenTheExactCodeHasAnAccount() {
        given(account("ORANGE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false));

        assertThat(accounts.payerRefusal(userId, "orange_sen", "Orange Money", "SN", "XOF")).isEmpty();
    }

    @Test
    void payerRefusal_withoutAnyAccountInTheCurrency_saysNoNetwork() {
        given(account("ORANGE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false));

        assertThat(accounts.payerRefusal(userId, "ORANGE_SEN", "Orange Money", "SN", "XAF"))
                .contains("Ce voyageur n'accepte aucun réseau mobile money pour le moment.");
    }

    @Test
    void payerRefusal_otherBrand_namesTheRejectedAndAcceptedNetworks() {
        given(account("ORANGE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false));

        assertThat(accounts.payerRefusal(userId, "WAVE_SEN", "Wave", "SN", "XOF"))
                .contains("Ce voyageur n'accepte pas Wave. Réseaux acceptés : Orange Money.");
    }

    @Test
    void payerRefusal_sameBrandOtherCountry_namesBothCountries() {
        given(account("ORANGE_SEN", "SN", "XOF", "+221770000001", CIV_VERIFIED, false),
                account("WAVE_SEN", "SN", "XOF", "+221770000002", CIV_VERIFIED, false));

        assertThat(accounts.payerRefusal(userId, "ORANGE_CIV", "Orange Money", "ci", "XOF"))
                .contains("Ce voyageur n'accepte pas Orange Money (CI). Réseaux acceptés : Orange Money (SN), Wave.");
        // Pays inconnu du payeur : pas de suffixe sur le refusé, mais les comptes restent désambiguïsés.
        assertThat(accounts.payerRefusal(userId, "ORANGE_CIV", "Orange Money", null, "XOF"))
                .contains("Ce voyageur n'accepte pas Orange Money. Réseaux acceptés : Orange Money (SN), Wave.");
    }

    // ── Routage du versement ─────────────────────────────────────────────────────────────────

    @Test
    void destinationFor_sameCodeWins() {
        var civ = account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, true);
        var sen = account("ORANGE_SEN", "SN", "XOF", "+221770000002", CMR_VERIFIED, false);
        given(civ, sen);

        assertThat(accounts.destinationFor(userId, "ORANGE_SEN", "XOF"))
                .contains(new PayoutDestination(sen.getId(), "+221770000002", "ORANGE_SEN", "SN"));
        assertThat(accounts.destinationFor(userId, "orange_civ", "xof"))
                .contains(new PayoutDestination(civ.getId(), "+2250700000001", "ORANGE_CIV", "CI"));
    }

    @Test
    void destinationFor_fallsBackToMigratedAccountInCurrency() {
        var civ = account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, true);
        given(civ);

        // Review Focus 1 : versé sur le compte repris (son numéro, son propre code), jamais MTN vers un numéro Orange.
        assertThat(accounts.destinationFor(userId, "MTN_MOMO_CIV", "XOF"))
                .contains(new PayoutDestination(civ.getId(), "+2250700000001", "ORANGE_CIV", "CI"));
    }

    @Test
    void destinationFor_nonMigratedOtherCode_isEmpty() {
        given(account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, false));

        assertThat(accounts.destinationFor(userId, "MTN_MOMO_CIV", "XOF")).isEmpty();
    }

    @Test
    void destinationFor_nullDepositProvider_usesMigrated() {
        var other = account("MTN_MOMO_CIV", "CI", "XOF", "+2250500000003", CMR_VERIFIED, false);
        var civ = account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, true);
        given(other, civ);

        assertThat(accounts.destinationFor(userId, null, "XOF"))
                .contains(new PayoutDestination(civ.getId(), "+2250700000001", "ORANGE_CIV", "CI"));
        assertThat(accounts.destinationFor(userId, "  ", "XOF"))
                .contains(new PayoutDestination(civ.getId(), "+2250700000001", "ORANGE_CIV", "CI"));
    }

    @Test
    void destinationFor_nullDepositProvider_migratedInOtherCurrency_isEmpty() {
        given(account("MTN_MOMO_CMR", "CM", "XAF", "+237670000002", CMR_VERIFIED, true));

        assertThat(accounts.destinationFor(userId, null, "XOF")).isEmpty();
        assertThat(accounts.destinationFor(userId, "MTN_MOMO_CMR", "XOF")).isEmpty();
    }

    @Test
    void pendingPayouts_delegatesToTheLockQuery() {
        when(repository.countParcelsAwaitingPayout(userId, "ORANGE_CIV")).thenReturn(2);

        assertThat(accounts.pendingPayouts(userId, "ORANGE_CIV")).isEqualTo(2);
        verify(repository).countParcelsAwaitingPayout(userId, "ORANGE_CIV");
    }

    // ── Résumé utilisateur ───────────────────────────────────────────────────────────────────

    private UserEntity user(MobileMoneyPayoutStatus status) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setMobileMoneyStatus(status);
        return u;
    }

    @Test
    void syncSummary_activeAccounts() {
        var civ = account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, false);
        var cmr = account("MTN_MOMO_CMR", "CM", "XAF", "+237670000002", CMR_VERIFIED, false);
        given(civ, cmr);
        UserEntity u = user(MobileMoneyPayoutStatus.NOT_CONFIGURED);

        accounts.syncSummary(u);

        assertThat(u.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(u.getMobileMoneyProvider()).isEqualTo("ORANGE_CIV");
        assertThat(u.getMobileMoneyMsisdn()).isEqualTo("+2250700000001");
        assertThat(u.getMobileMoneyMsisdnMasked()).isEqualTo(civ.getMsisdnMasked());
        assertThat(u.getMobileMoneyCountry()).isEqualTo("CI");
        assertThat(u.getMobileMoneyCurrency()).isEqualTo("XOF");
        assertThat(u.getMobileMoneyVerifiedAt()).isEqualTo(CIV_VERIFIED);
        assertThat(u.getMobileMoneyProviderList()).containsExactly("ORANGE_CIV", "MTN_MOMO_CMR");
        assertThat(u.getMobileMoneyCurrencyList()).containsExactly("XOF", "XAF");
        assertThat(u.canReceiveMobileMoney("XAF")).isTrue();
    }

    @Test
    void syncSummary_twoAccountsSameCurrency_listCurrencyOnce() {
        given(account("ORANGE_CIV", "CI", "XOF", "+2250700000001", CIV_VERIFIED, false),
                account("ORANGE_SEN", "SN", "XOF", "+221770000002", CMR_VERIFIED, false));
        UserEntity u = user(MobileMoneyPayoutStatus.DISABLED);

        accounts.syncSummary(u);

        assertThat(u.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(u.getMobileMoneyProviderList()).containsExactly("ORANGE_CIV", "ORANGE_SEN");
        assertThat(u.getMobileMoneyCurrencyList()).containsExactly("XOF");
    }

    @Test
    void syncSummary_noAccount_disables() {
        given();
        UserEntity active = user(MobileMoneyPayoutStatus.ACTIVE);
        active.setMobileMoneyProviderList(List.of("ORANGE_CIV"));
        active.setMobileMoneyCurrencyList(List.of("XOF"));

        accounts.syncSummary(active);

        // Review Focus 4 : dernier compte retiré → DISABLED, plus aucune réception possible.
        assertThat(active.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.DISABLED);
        assertThat(active.getMobileMoneyProviderList()).isEmpty();
        assertThat(active.getMobileMoneyCurrencyList()).isEmpty();
        assertThat(active.canReceiveMobileMoney("XOF")).isFalse();

        UserEntity never = user(MobileMoneyPayoutStatus.NOT_CONFIGURED);
        accounts.syncSummary(never);
        assertThat(never.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.NOT_CONFIGURED);
        assertThat(never.getMobileMoneyProviderList()).isEmpty();
        assertThat(never.getMobileMoneyCurrencyList()).isEmpty();
    }
}
