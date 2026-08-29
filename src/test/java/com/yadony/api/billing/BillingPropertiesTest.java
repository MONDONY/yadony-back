package com.yadony.api.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPropertiesTest {

    @Test
    @DisplayName("tous les défauts : null, null, null → false, 60, 5")
    void returnsDefaultsWhenAllNull() {
        BillingProperties props = new BillingProperties(null, null, null, null, null, null, null, null);

        assertThat(props.schedulerEnabledOrDefault()).isFalse();
        assertThat(props.legacyGraceDaysOrDefault()).isEqualTo(60);
        assertThat(props.dunningGraceDaysOrDefault()).isEqualTo(5);
    }

    @Test
    @DisplayName("valeurs explicites priment sur les défauts")
    void overridesDefaultsWithExplicitValues() {
        BillingProperties props = new BillingProperties(true, 30, 3, null, null, null, null, null);

        assertThat(props.schedulerEnabledOrDefault()).isTrue();
        assertThat(props.legacyGraceDaysOrDefault()).isEqualTo(30);
        assertThat(props.dunningGraceDaysOrDefault()).isEqualTo(3);
    }

    @Test
    @DisplayName("le scheduler peut être explicitement désactivé")
    void schedulerCanBeExplicitlyDisabled() {
        BillingProperties props = new BillingProperties(false, 60, 5, null, null, null, null, null);

        assertThat(props.schedulerEnabledOrDefault()).isFalse();
    }

    @Test
    @DisplayName("Price monthly et yearly renseignés : configuré")
    void stripePricesConfiguredWhenBothSet() {
        BillingProperties props = new BillingProperties(null, null, null,
                "price_m", "price_y", null, null, null);

        assertThat(props.stripePricesConfigured()).isTrue();
    }

    @Test
    @DisplayName("Price manquants ou vides : non configuré")
    void stripePricesNotConfiguredWhenMissing() {
        assertThat(new BillingProperties(null, null, null, null, null, null, null, null)
                .stripePricesConfigured()).isFalse();
        assertThat(new BillingProperties(null, null, null, "price_m", null, null, null, null)
                .stripePricesConfigured()).isFalse();
        assertThat(new BillingProperties(null, null, null, null, "price_y", null, null, null)
                .stripePricesConfigured()).isFalse();
        assertThat(new BillingProperties(null, null, null, "  ", "price_y", null, null, null)
                .stripePricesConfigured()).isFalse();
        assertThat(new BillingProperties(null, null, null, "price_m", "  ", null, null, null)
                .stripePricesConfigured()).isFalse();
    }

    @Test
    @DisplayName("priceFor choisit le bon identifiant selon le cycle")
    void priceForSelectsRightIdentifier() {
        BillingProperties props = new BillingProperties(null, null, null,
                "price_m", "price_y", null, null, null);

        assertThat(props.priceFor(BillingCycle.MONTHLY)).isEqualTo("price_m");
        assertThat(props.priceFor(BillingCycle.YEARLY)).isEqualTo("price_y");
    }
}
