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
}
