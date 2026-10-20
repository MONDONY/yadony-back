package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAcceptedOperatorsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.CountryOption;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.OperatorOption;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class MobileMoneyAcceptedOperatorsServiceTest {

    private MobileMoneyCountriesService countries;
    private UserRepository userRepository;
    private InMemoryPayoutAccounts payoutAccounts;
    private MobileMoneyAcceptedOperatorsService service;

    @BeforeEach
    void setUp() {
        countries = mock(MobileMoneyCountriesService.class);
        userRepository = mock(UserRepository.class);
        payoutAccounts = new InMemoryPayoutAccounts();
        service = new MobileMoneyAcceptedOperatorsService(countries, userRepository, payoutAccounts);
        when(countries.countries(PawapayOperationKind.DEPOSIT)).thenReturn(new MobileMoneyCountriesResponse(List.of(
                country("CI", "XOF", op("ORANGE_CIV", "ORANGE"), op("MTN_MOMO_CIV", "MTN"), op("MOOV_CIV", "MOOV")),
                country("SN", "XOF", op("ORANGE_SEN", "ORANGE")),
                country("CM", "XAF", op("MTN_MOMO_CMR", "MTN"))), List.of()));
    }

    private static CountryOption country(String code, String currency, OperatorOption... ops) {
        return new CountryOption(code, currency, List.of(ops));
    }

    private static OperatorOption op(String code, String brand) {
        return new OperatorOption(code, code, brand, ValidationHint.GENERIC, BigDecimal.ONE, BigDecimal.TEN);
    }

    /** Voyageur avec un compte de versement par code, tous dans {@code currency}. */
    private UserEntity traveler(String firstName, String currency, String... providers) {
        UserEntity t = new UserEntity();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setFirstName(firstName);
        payoutAccounts.give(t.getId(), currency, providers);
        return t;
    }

    @Test
    void forTraveler_keepsTheExactCodesOfTheAccountsInCurrency() {
        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(traveler("Aminata", "XOF", "ORANGE_CIV", "MTN_MOMO_CIV"), "XOF");

        // Code exact : ORANGE_SEN (même marque, même devise) n'est pas listé ; MOOV_CIV sans compte non plus.
        assertThat(r.providers()).containsExactly("ORANGE_CIV", "MTN_MOMO_CIV");
        assertThat(r.currency()).isEqualTo("XOF");
    }

    @Test
    void forTraveler_accountOrangeSenOnly_orangeCivIsNotListed() {
        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(traveler("Aminata", "XOF", "ORANGE_SEN"), "XOF");

        assertThat(r.providers()).containsExactly("ORANGE_SEN").doesNotContain("ORANGE_CIV");
    }

    @Test
    void forTraveler_otherCurrency_keepsOnlyThatCurrency() {
        UserEntity t = traveler("Aminata", "XAF", "MTN_MOMO_CMR");

        assertThat(service.forTraveler(t, "XAF").providers()).containsExactly("MTN_MOMO_CMR");
        // Un compte XAF n'ouvre aucun opérateur d'un trajet en XOF, même de la même marque (MTN_MOMO_CIV).
        assertThat(service.forTraveler(t, "XOF").providers()).isEmpty();
    }

    @Test
    void forTraveler_noneAccepted_returnsEmpty() {
        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(traveler("Aminata", "XOF", "AIRTEL_COG"), "XOF");
        assertThat(r.providers()).isEmpty();
        assertThat(service.forTraveler(traveler("Aminata", "XOF"), "XOF").providers()).isEmpty();
    }

    @Test
    void forTraveler_nullCurrency_returnsEmpty() {
        assertThat(service.forTraveler(traveler("Aminata", "XOF", "ORANGE_CIV"), null).providers()).isEmpty();
    }

    @Test
    void forTraveler_firstNameExposed() {
        assertThat(service.forTraveler(traveler("Aminata", "XOF", "ORANGE_CIV"), "XOF").travelerFirstName()).isEqualTo("Aminata");
    }

    @Test
    void forTraveler_byId_loadsTheTraveler() {
        UserEntity t = traveler("Moussa", "XOF", "MOOV_CIV");
        UUID id = t.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(t));

        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(id, "XOF");

        assertThat(r.providers()).containsExactly("MOOV_CIV");
        assertThat(r.travelerFirstName()).isEqualTo("Moussa");
    }

    @Test
    void forTraveler_byId_unknownTraveler_is404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forTraveler(id, "XOF"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void forTraveler_catalogueFailure_propagates() {
        when(countries.countries(PawapayOperationKind.DEPOSIT)).thenThrow(
                new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable", "Bad Gateway", "pawaPay"));

        assertThatThrownBy(() -> service.forTraveler(traveler("Aminata", "XOF", "ORANGE_CIV"), "XOF"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
