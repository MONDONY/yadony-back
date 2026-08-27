package com.yadony.api.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProSubscriptionStatusTest {

    @Test
    @DisplayName("ACTIVE, PAST_DUE et LEGACY_GRACE ouvrent l'accès PRO")
    void grantingStatuses() {
        assertThat(ProSubscriptionStatus.ACTIVE.grantsProAccess()).isTrue();
        assertThat(ProSubscriptionStatus.PAST_DUE.grantsProAccess()).isTrue();
        assertThat(ProSubscriptionStatus.LEGACY_GRACE.grantsProAccess()).isTrue();
    }

    @Test
    @DisplayName("CANCELED et EXPIRED ferment l'accès PRO")
    void revokingStatuses() {
        assertThat(ProSubscriptionStatus.CANCELED.grantsProAccess()).isFalse();
        assertThat(ProSubscriptionStatus.EXPIRED.grantsProAccess()).isFalse();
    }
}
