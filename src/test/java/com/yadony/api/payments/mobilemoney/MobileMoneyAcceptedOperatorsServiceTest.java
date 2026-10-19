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
    private MobileMoneyAcceptedOperatorsService service;

    @BeforeEach
    void setUp() {
        countries = mock(MobileMoneyCountriesService.class);
        userRepository = mock(UserRepository.class);
        service = new MobileMoneyAcceptedOperatorsService(countries, userRepository);
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

    private static UserEntity traveler(String firstName, String... providers) {
        UserEntity t = new UserEntity();
        t.setFirstName(firstName);
        t.setMobileMoneyProviderList(List.of(providers));
        return t;
    }

    @Test
    void forTraveler_keepsAcceptedBrandsInCurrency() {
        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(traveler("Aminata", "ORANGE_CIV", "MTN_MOMO_CIV"), "XOF");

        // ORANGE_SEN passe (même marque Orange, même devise) ; MTN_MOMO_CMR est en XAF ; MOOV_CIV non accepté.
        assertThat(r.providers()).containsExactly("ORANGE_CIV", "MTN_MOMO_CIV", "ORANGE_SEN");
        assertThat(r.currency()).isEqualTo("XOF");
    }

    @Test
    void forTraveler_otherCurrency_keepsOnlyThatCurrency() {
        assertThat(service.forTraveler(traveler("Aminata", "MTN_MOMO_CIV"), "XAF").providers()).containsExactly("MTN_MOMO_CMR");
    }

    @Test
    void forTraveler_noneAccepted_returnsEmpty() {
        MobileMoneyAcceptedOperatorsResponse r = service.forTraveler(traveler("Aminata", "AIRTEL_COG"), "XOF");
        assertThat(r.providers()).isEmpty();
        assertThat(service.forTraveler(traveler("Aminata"), "XOF").providers()).isEmpty();
    }

    @Test
    void forTraveler_nullCurrency_returnsEmpty() {
        assertThat(service.forTraveler(traveler("Aminata", "ORANGE_CIV"), null).providers()).isEmpty();
    }

    @Test
    void forTraveler_firstNameExposed() {
        assertThat(service.forTraveler(traveler("Aminata", "ORANGE_CIV"), "XOF").travelerFirstName()).isEqualTo("Aminata");
    }

    @Test
    void forTraveler_byId_loadsTheTraveler() {
        UUID id = UUID.randomUUID();
        UserEntity t = traveler("Moussa", "MOOV_CIV");
        ReflectionTestUtils.setField(t, "id", id);
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

        assertThatThrownBy(() -> service.forTraveler(traveler("Aminata", "ORANGE_CIV"), "XOF"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
