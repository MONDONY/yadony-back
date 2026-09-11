package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayProviderResolver.Reason;
import com.yadony.api.payments.pawapay.PawapayProviderResolver.UnsupportedNumberException;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PawapayProviderResolverTest {

    private static final PawapayProviderConfig.Limits OPEN =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL");
    private static final PawapayProviderConfig.Limits CLOSED =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "CLOSED");

    private static final PawapayProviderConfig ORANGE = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OPEN, OPEN);
    private static final PawapayProviderConfig WAVE = new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OPEN, OPEN);
    private static final PawapayProviderConfig FREE_PAYOUT_CLOSED = new PawapayProviderConfig("FREE_SEN", "SEN", "XOF", OPEN, CLOSED);
    private static final PawapayProviderConfig MTN_CIV = new PawapayProviderConfig("MTN_CIV", "CIV", "XOF", OPEN, OPEN);
    private static final PawapayProviderConfig MTN_CMR = new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", OPEN, OPEN);

    /** Configuration active dans l'ordre donné (LinkedHashMap, comme PawapayClient). */
    private void configured(PawapayProviderConfig... confs) {
        Map<String, PawapayProviderConfig> m = new LinkedHashMap<>();
        for (PawapayProviderConfig c : confs) {
            m.put(c.provider(), c);
        }
        when(client.activeConfiguration()).thenReturn(m);
    }

    @Mock PawapayClient client;
    @InjectMocks PawapayProviderResolver resolver;

    private void predicts(String provider, String phone) {
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", provider, phone)));
    }

    @Test
    void resolves_provider_normalizedNumber_countryAndConfig() {
        predicts("ORANGE_SEN", "+221771234567");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN",
                new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OPEN, OPEN)));

        PawapayProviderResolver.Resolved r = resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "xof", "ctx");

        assertThat(r.provider()).isEqualTo("ORANGE_SEN");
        assertThat(r.msisdn()).as("forme pawaPay normalisée, sans le +").isEqualTo("221771234567");
        assertThat(r.countryAlpha2()).isEqualTo("SN");
        assertThat(r.config().currency()).isEqualTo("XOF");
        assertThat(r.providerLabel()).isEqualTo("Orange Money");
    }

    @Test
    void pawapayDown_onPrediction_is502_neverA422() {
        when(client.predictProvider("221771234567")).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
        verify(client, never()).activeConfiguration();
    }

    @Test
    void pawapayDown_onConfiguration_is502() {
        predicts("ORANGE_SEN", null);
        when(client.activeConfiguration()).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.PAYOUT, "XOF", "ctx"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    @Test
    void unknownNumber_isNoProvider() {
        when(client.predictProvider("221771234567")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .extracting(e -> ((UnsupportedNumberException) e).reason()).isEqualTo(Reason.NO_PROVIDER);
        verify(client, never()).activeConfiguration();
    }

    /** DEPOSIT et PAYOUT se lisent chacun sur SA configuration : Wave paie mais ne verse pas. */
    @Test
    void operationClosed_dependsOnTheKind() {
        predicts("WAVE_SEN", null);
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN",
                new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OPEN, null)));

        assertThat(resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx").provider()).isEqualTo("WAVE_SEN");
        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.PAYOUT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .satisfies(e -> {
                    assertThat(((UnsupportedNumberException) e).reason()).isEqualTo(Reason.OPERATION_CLOSED);
                    assertThat(((UnsupportedNumberException) e).providerLabel()).isEqualTo("Wave");
                });
    }

    @Test
    void closedStatus_isOperationClosed() {
        predicts("ORANGE_SEN", null);
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN",
                new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", CLOSED, OPEN)));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .extracting(e -> ((UnsupportedNumberException) e).reason()).isEqualTo(Reason.OPERATION_CLOSED);
    }

    @Test
    void providerInAnotherCurrency_isCurrencyMismatch_withThatCurrency() {
        predicts("MTN_MOMO_CMR", null);
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CMR",
                new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", OPEN, OPEN)));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .satisfies(e -> {
                    assertThat(((UnsupportedNumberException) e).reason()).isEqualTo(Reason.CURRENCY_MISMATCH);
                    assertThat(((UnsupportedNumberException) e).providerCurrency()).isEqualTo("XAF");
                });
    }

    /** Un numéro prédit hors bornes n'est pas une erreur de saisie : c'est pawaPay qui répond une donnée inexploitable. */
    @Test
    void predictedNumberOutOfBounds_is502() {
        predicts("ORANGE_SEN", "12");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN",
                new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OPEN, OPEN)));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    @Test
    void unknownAlpha3_isCountryUnknown() {
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", null)));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN",
                new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OPEN, OPEN)));

        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .extracting(e -> ((UnsupportedNumberException) e).reason()).isEqualTo(Reason.COUNTRY_UNKNOWN);
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    @Test
    void catalogue_keepsTheNumbersCountry_theOperation_andTheCurrency_detectedFirst() {
        predicts("WAVE_SEN", "221771234567");
        configured(ORANGE, FREE_PAYOUT_CLOSED, WAVE, MTN_CIV, MTN_CMR);
        PawapayProviderResolver.Catalogue c = resolver.catalogue("221771234567", PawapayOperationKind.PAYOUT, "xof", "ctx");
        assertThat(c.options()).extracting(PawapayProviderConfig::provider).containsExactly("WAVE_SEN", "ORANGE_SEN");
        assertThat(c.detected()).isEqualTo("WAVE_SEN");
        assertThat(c.countryAlpha2()).isEqualTo("SN");
        assertThat(c.currency()).isEqualTo("XOF");
        assertThat(c.msisdn()).isEqualTo("221771234567");
        assertThat(c.option("wave_sen")).contains(WAVE);
        assertThat(c.option("MTN_CIV")).isEmpty();
    }

    @Test
    void catalogue_forDeposit_keepsAProviderClosedOnlyForPayout() {
        predicts("ORANGE_SEN", null);
        configured(ORANGE, FREE_PAYOUT_CLOSED);
        PawapayProviderResolver.Catalogue c = resolver.catalogue("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx");
        assertThat(c.options()).extracting(PawapayProviderConfig::provider).containsExactly("ORANGE_SEN", "FREE_SEN");
    }

    /** Le prédit est fermé pour l'opération : il n'est pas « détecté », mais les autres réseaux restent proposés. */
    @Test
    void catalogue_predictedExcluded_isNotDetected_butOthersRemain() {
        predicts("FREE_SEN", null);
        configured(ORANGE, FREE_PAYOUT_CLOSED, WAVE);
        PawapayProviderResolver.Catalogue c = resolver.catalogue("221771234567", PawapayOperationKind.PAYOUT, "XOF", "ctx");
        assertThat(c.detected()).isNull();
        assertThat(c.options()).extracting(PawapayProviderConfig::provider).containsExactly("ORANGE_SEN", "WAVE_SEN");
    }

    @Test
    void catalogue_empty_becauseTheOperationIsClosed_isOperationClosed() {
        predicts("FREE_SEN", null);
        configured(FREE_PAYOUT_CLOSED);
        assertThatThrownBy(() -> resolver.catalogue("221771234567", PawapayOperationKind.PAYOUT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .satisfies(e -> {
                    assertThat(((UnsupportedNumberException) e).reason()).isEqualTo(Reason.OPERATION_CLOSED);
                    assertThat(((UnsupportedNumberException) e).providerLabel()).isEqualTo("Free Money");
                });
    }

    @Test
    void catalogue_empty_becauseOfTheCurrency_isCurrencyMismatch() {
        when(client.predictProvider("237670000000"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CMR", "MTN_MOMO_CMR", "237670000000")));
        configured(MTN_CMR);
        assertThatThrownBy(() -> resolver.catalogue("237670000000", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .satisfies(e -> {
                    assertThat(((UnsupportedNumberException) e).reason()).isEqualTo(Reason.CURRENCY_MISMATCH);
                    assertThat(((UnsupportedNumberException) e).providerCurrency()).isEqualTo("XAF");
                });
    }

    @Test
    void catalogue_unknownNumber_isNoProvider_withoutReadingTheConfiguration() {
        when(client.predictProvider("221771234567")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.catalogue("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .extracting(e -> ((UnsupportedNumberException) e).reason()).isEqualTo(Reason.NO_PROVIDER);
        verify(client, never()).activeConfiguration();
    }

    @Test
    void catalogue_pawapayDown_is502() {
        when(client.predictProvider("221771234567")).thenThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> resolver.catalogue("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "ctx"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    // ── choix explicite ─────────────────────────────────────────────────────

    @Test
    void resolve_withChosenProvider_picksItFromTheCatalogue() {
        predicts("ORANGE_SEN", "221771234567");
        configured(ORANGE, WAVE);
        PawapayProviderResolver.Resolved r = resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "wave_sen", "ctx");
        assertThat(r.provider()).isEqualTo("WAVE_SEN");
        assertThat(r.providerLabel()).isEqualTo("Wave");
        assertThat(r.config()).isSameAs(WAVE);
        assertThat(r.countryAlpha2()).isEqualTo("SN");
        assertThat(r.msisdn()).isEqualTo("221771234567");
    }

    @Test
    void resolve_withChosenProviderOutsideTheCatalogue_isProviderNotAvailable() {
        predicts("ORANGE_SEN", null);
        configured(ORANGE, WAVE, MTN_CIV);
        assertThatThrownBy(() -> resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "MTN_CIV", "ctx"))
                .isInstanceOf(UnsupportedNumberException.class)
                .satisfies(e -> {
                    assertThat(((UnsupportedNumberException) e).reason()).isEqualTo(Reason.PROVIDER_NOT_AVAILABLE);
                    assertThat(((UnsupportedNumberException) e).providerLabel()).isEqualTo("MTN MoMo");
                });
    }

    @Test
    void resolve_withBlankChosenProvider_fallsBackToThePredictedOne() {
        predicts("ORANGE_SEN", null);
        configured(ORANGE, WAVE);
        assertThat(resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", "  ", "ctx").provider()).isEqualTo("ORANGE_SEN");
        assertThat(resolver.resolve("221771234567", PawapayOperationKind.DEPOSIT, "XOF", null, "ctx").provider()).isEqualTo("ORANGE_SEN");
    }
}
