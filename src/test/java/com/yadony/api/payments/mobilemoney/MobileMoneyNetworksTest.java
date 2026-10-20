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
    void acceptedCodes_ignoresABlankSingleProvider() {
        assertThat(MobileMoneyNetworks.acceptedCodes(travelerWith(null, "   "))).isEmpty();
    }
}
