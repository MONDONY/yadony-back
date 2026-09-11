package com.yadony.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UserEntityMobileMoneyProvidersTest {

    @Test
    void providerList_isEmptyWhenTheColumnIsNullOrBlank() {
        UserEntity u = new UserEntity();
        assertThat(u.getMobileMoneyProviderList()).isEmpty();
        u.setMobileMoneyProviders("   ");
        assertThat(u.getMobileMoneyProviderList()).isEmpty();
    }

    @Test
    void providerList_splitsTheCsv_trimmed_inOrder() {
        UserEntity u = new UserEntity();
        u.setMobileMoneyProviders("ORANGE_SEN, WAVE_SEN,,FREE_SEN ");
        assertThat(u.getMobileMoneyProviderList()).containsExactly("ORANGE_SEN", "WAVE_SEN", "FREE_SEN");
    }

    @Test
    void setProviderList_joinsWithCommas_andNullsAnEmptyList() {
        UserEntity u = new UserEntity();
        u.setMobileMoneyProviderList(List.of("ORANGE_SEN", "WAVE_SEN"));
        assertThat(u.getMobileMoneyProviders()).isEqualTo("ORANGE_SEN,WAVE_SEN");
        u.setMobileMoneyProviderList(List.of());
        assertThat(u.getMobileMoneyProviders()).isNull();
        u.setMobileMoneyProviderList(null);
        assertThat(u.getMobileMoneyProviders()).isNull();
    }
}
