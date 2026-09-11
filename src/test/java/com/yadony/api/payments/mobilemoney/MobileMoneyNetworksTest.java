package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.Test;

class MobileMoneyNetworksTest {

    private static UserEntity travelerWith(String csv, String fallback) {
        UserEntity u = new UserEntity();
        u.setMobileMoneyProviders(csv);
        u.setMobileMoneyProvider(fallback);
        return u;
    }

    @Test
    void acceptedCodes_readsTheList_orFallsBackToTheSingleProvider() {
        assertThat(MobileMoneyNetworks.acceptedCodes(travelerWith("ORANGE_CIV,WAVE_CIV", "ORANGE_CIV")))
                .containsExactly("ORANGE_CIV", "WAVE_CIV");
        assertThat(MobileMoneyNetworks.acceptedCodes(travelerWith(null, "ORANGE_CIV"))).containsExactly("ORANGE_CIV");
        assertThat(MobileMoneyNetworks.acceptedCodes(travelerWith(null, null))).isEmpty();
    }

    @Test
    void acceptedBrands_areDeduplicatedAndOrdered() {
        assertThat(MobileMoneyNetworks.acceptedBrands(travelerWith("ORANGE_CIV,WAVE_CIV,ORANGE_SEN", null)))
                .containsExactly("ORANGE", "WAVE");
    }

    @Test
    void acceptedLabels_areHumanReadable_andDeduplicated() {
        assertThat(MobileMoneyNetworks.acceptedLabels(travelerWith("ORANGE_CIV,WAVE_CIV,ORANGE_SEN", null)))
                .containsExactly("Orange Money", "Wave");
    }

    @Test
    void acceptsBrand_comparesBrands_notCountries() {
        UserEntity t = travelerWith("ORANGE_CIV,WAVE_CIV", "ORANGE_CIV");
        assertThat(MobileMoneyNetworks.acceptsBrand(t, "ORANGE_SEN")).isTrue();
        assertThat(MobileMoneyNetworks.acceptsBrand(t, "wave_sen")).isTrue();
        assertThat(MobileMoneyNetworks.acceptsBrand(t, "MTN_CIV")).isFalse();
        assertThat(MobileMoneyNetworks.acceptsBrand(t, null)).isFalse();
    }

    @Test
    void providerForBrand_returnsTheTravelersCodeOfThatBrand() {
        UserEntity t = travelerWith("ORANGE_CIV,WAVE_CIV", "ORANGE_CIV");
        assertThat(MobileMoneyNetworks.providerForBrand(t, "WAVE")).contains("WAVE_CIV");
        assertThat(MobileMoneyNetworks.providerForBrand(t, "MTN")).isEmpty();
        assertThat(MobileMoneyNetworks.providerForBrand(t, null)).isEmpty();
        assertThat(MobileMoneyNetworks.providerForBrand(travelerWith(null, "ORANGE_CIV"), "ORANGE")).contains("ORANGE_CIV");
    }

    @Test
    void helpers_tolerateAUserWithoutAnyProvider() {
        UserEntity none = travelerWith(null, null);
        assertThat(MobileMoneyNetworks.acceptedBrands(none)).isEmpty();
        assertThat(MobileMoneyNetworks.acceptedLabels(none)).isEmpty();
        assertThat(MobileMoneyNetworks.acceptsBrand(none, "ORANGE_SEN")).isFalse();
    }
}
