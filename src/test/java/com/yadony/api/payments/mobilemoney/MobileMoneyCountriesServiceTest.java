package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayValidationHints;
import com.yadony.api.payments.pawapay.ValidationHint;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig.Limits;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MobileMoneyCountriesServiceTest {

    private PawapayClient client;
    private final PawapayValidationHints hints = new PawapayValidationHints(Map.of("ORANGE_CIV", "SMS_LINK"));

    @BeforeEach
    void setUp() {
        client = mock(PawapayClient.class);
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://api.sandbox.pawapay.io", "token", true, 30,
                "https://api-staging.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                new PawapayProperties.BalanceMin(null, null));
    }

    private MobileMoneyCountriesService service(boolean enabled, String upcoming) {
        return new MobileMoneyCountriesService(client, props(enabled), hints, upcoming);
    }

    private static Limits open(String min, String max) {
        return new Limits(new BigDecimal(min), new BigDecimal(max), "PROVIDER_AUTH", "OPERATIONAL");
    }

    private static PawapayProviderConfig config(String provider, String alpha3, Limits deposit, Limits payout) {
        return new PawapayProviderConfig(provider, alpha3, "XOF", deposit, payout);
    }

    private void stub(PawapayProviderConfig... configs) {
        Map<String, PawapayProviderConfig> map = new LinkedHashMap<>();
        for (PawapayProviderConfig c : configs) {
            map.put(c.provider(), c);
        }
        when(client.activeConfiguration()).thenReturn(map);
    }

    @Test
    void countries_groupsOperatorsByCountry_sortedByLabel() {
        // Ordre d'entrée volontairement mélangé : MTN avant Orange, Sénégal avant Côte d'Ivoire.
        stub(config("ORANGE_SEN", "SEN", open("100", "1000000"), open("100", "1000000")),
                config("MTN_MOMO_CIV", "CIV", open("100", "1500000"), open("100", "1500000")),
                config("ORANGE_CIV", "CIV", open("100", "2000000"), open("100", "2000000")));

        MobileMoneyCountriesResponse r = service(true, "ML").countries(PawapayOperationKind.DEPOSIT);

        assertThat(r.available()).extracting(MobileMoneyCountriesResponse.CountryOption::country)
                .containsExactly("CI", "SN");
        assertThat(r.available().get(0).currency()).isEqualTo("XOF");
        assertThat(r.available().get(0).operators()).extracting(MobileMoneyCountriesResponse.OperatorOption::code)
                .containsExactly("ORANGE_CIV", "MTN_MOMO_CIV");
        MobileMoneyCountriesResponse.OperatorOption orange = r.available().get(0).operators().get(0);
        assertThat(orange.label()).isEqualTo("Orange Money");
        assertThat(orange.brand()).isEqualTo("ORANGE");
        assertThat(orange.validationHint()).isEqualTo(ValidationHint.SMS_LINK);
        assertThat(r.available().get(0).operators().get(1).validationHint()).isEqualTo(ValidationHint.GENERIC);
        assertThat(r.available().get(1).operators()).extracting(MobileMoneyCountriesResponse.OperatorOption::code)
                .containsExactly("ORANGE_SEN");
    }

    @Test
    void countries_deposit_excludesClosedOrUnsupported() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), open("100", "2000000")),
                config("MOOV_CIV", "CIV", new Limits(new BigDecimal("100"), new BigDecimal("1000"), "PROVIDER_AUTH", "CLOSED"),
                        open("100", "1000")),
                config("WAVE_CIV", "CIV", null, open("100", "1000")),
                config("FREE_SEN", "SEN", null, null));

        MobileMoneyCountriesResponse r = service(true, "ML").countries(PawapayOperationKind.DEPOSIT);

        assertThat(r.available()).hasSize(1);
        assertThat(r.available().get(0).country()).isEqualTo("CI");
        assertThat(r.available().get(0).operators()).extracting(MobileMoneyCountriesResponse.OperatorOption::code)
                .containsExactly("ORANGE_CIV");
    }

    @Test
    void countries_payout_usesPayoutLimits() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), open("200", "300000")),
                config("WAVE_CIV", "CIV", open("100", "1000"), null));

        MobileMoneyCountriesResponse r = service(true, "ML").countries(PawapayOperationKind.PAYOUT);

        assertThat(r.available()).hasSize(1);
        assertThat(r.available().get(0).operators()).hasSize(1);
        MobileMoneyCountriesResponse.OperatorOption op = r.available().get(0).operators().get(0);
        assertThat(op.code()).isEqualTo("ORANGE_CIV");
        assertThat(op.minAmount()).isEqualByComparingTo("200");
        assertThat(op.maxAmount()).isEqualByComparingTo("300000");
    }

    @Test
    void countries_deposit_usesDepositLimits() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), open("200", "300000")));

        MobileMoneyCountriesResponse.OperatorOption op =
                service(true, "ML").countries(PawapayOperationKind.DEPOSIT).available().get(0).operators().get(0);

        assertThat(op.minAmount()).isEqualByComparingTo("100");
        assertThat(op.maxAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    void countries_unknownAlpha3_isIgnored() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), null),
                config("ORANGE_ZZZ", "ZZZ", open("100", "2000000"), null));

        assertThat(service(true, "ML").countries(PawapayOperationKind.DEPOSIT).available())
                .extracting(MobileMoneyCountriesResponse.CountryOption::country).containsExactly("CI");
    }

    @Test
    void countries_upcoming_excludesOpenCountries() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), null));

        assertThat(service(true, "ML,CI").countries(PawapayOperationKind.DEPOSIT).upcoming()).containsExactly("ML");
    }

    @Test
    void countries_upcoming_normalizesAndDedupes() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), null));

        assertThat(service(true, " ml, ML ,gn,,xyz,1A").countries(PawapayOperationKind.DEPOSIT).upcoming())
                .containsExactly("ML", "GN");
    }

    @Test
    void countries_upcoming_blankConfigGivesEmptyList() {
        stub(config("ORANGE_CIV", "CIV", open("100", "2000000"), null));

        assertThat(service(true, "").countries(PawapayOperationKind.DEPOSIT).upcoming()).isEmpty();
        assertThat(service(true, null).countries(PawapayOperationKind.DEPOSIT).upcoming()).isEmpty();
    }

    @Test
    void countries_pawapayDown_throwsProviderUnavailable() {
        when(client.activeConfiguration()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service(true, "ML").countries(PawapayOperationKind.DEPOSIT))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
                });
    }

    @Test
    void countries_businessExceptionFromClient_isRethrownAsIs() {
        YadonyBusinessException original = new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "x", "X", "x");
        when(client.activeConfiguration()).thenThrow(original);

        assertThatThrownBy(() -> service(true, "ML").countries(PawapayOperationKind.DEPOSIT)).isSameAs(original);
    }

    @Test
    void countries_disabled_throwsDisabled() {
        assertThatThrownBy(() -> service(false, "ML").countries(PawapayOperationKind.DEPOSIT))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("mobile-money-disabled");
                });
    }
}
