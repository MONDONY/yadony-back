package com.yadony.api.payments.amendment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lie {@code yadony.bid-amendment.*} depuis l'{@code application.yml} réel : les défauts du plan et les
 * variables d'environnement {@code BID_AMENDMENT_*} qui les surchargent.
 */
class BidAmendmentPropertiesTest {

    private static BidAmendmentProperties bind(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        // Les variables d'environnement l'emportent sur le fichier, comme en exécution.
        sources.addFirst(new MapPropertySource("env", environment));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("yadony.bid-amendment", BidAmendmentProperties.class).get();
    }

    @Test
    void defaults_matchThePlan() throws IOException {
        BidAmendmentProperties p = bind(Map.of());

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getExpiry()).isEqualTo(Duration.ofHours(48));
        assertThat(p.getDepartureMargin()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.getMinResponseWindow()).isEqualTo(Duration.ofHours(2));
        assertThat(p.getAuthorizationWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getCommissionConfirmWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getReminderBefore()).isEqualTo(Duration.ofHours(6));
        assertThat(p.getMaxAcceptedPerBid()).isEqualTo(2);
        assertThat(p.getMinNetEur()).isEqualByComparingTo("1");
        assertThat(p.getMaxNetEur()).isEqualByComparingTo("300");
        assertThat(p.getReconcileInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.isReconcileEnabled()).isTrue();
        assertThat(p.getMmMaxAttempts()).isEqualTo(3);
    }

    @Test
    void everyKey_isOverridableByAnEnvironmentVariable() throws IOException {
        BidAmendmentProperties p = bind(Map.ofEntries(
                Map.entry("BID_AMENDMENT_ENABLED", "true"),
                Map.entry("BID_AMENDMENT_EXPIRY", "PT24H"),
                Map.entry("BID_AMENDMENT_DEPARTURE_MARGIN", "PT30M"),
                Map.entry("BID_AMENDMENT_MIN_RESPONSE_WINDOW", "PT3H"),
                Map.entry("BID_AMENDMENT_AUTHORIZATION_WINDOW", "PT10M"),
                Map.entry("BID_AMENDMENT_COMMISSION_CONFIRM_WINDOW", "PT20M"),
                Map.entry("BID_AMENDMENT_REMINDER_BEFORE", "PT4H"),
                Map.entry("BID_AMENDMENT_MAX_ACCEPTED_PER_BID", "5"),
                Map.entry("BID_AMENDMENT_MIN_NET_EUR", "2"),
                Map.entry("BID_AMENDMENT_MAX_NET_EUR", "500"),
                Map.entry("BID_AMENDMENT_RECONCILE_INTERVAL", "PT1M"),
                Map.entry("BID_AMENDMENT_RECONCILE_ENABLED", "false"),
                Map.entry("BID_AMENDMENT_MM_MAX_ATTEMPTS", "7")));

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getExpiry()).isEqualTo(Duration.ofHours(24));
        assertThat(p.getDepartureMargin()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getMinResponseWindow()).isEqualTo(Duration.ofHours(3));
        assertThat(p.getAuthorizationWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.getCommissionConfirmWindow()).isEqualTo(Duration.ofMinutes(20));
        assertThat(p.getReminderBefore()).isEqualTo(Duration.ofHours(4));
        assertThat(p.getMaxAcceptedPerBid()).isEqualTo(5);
        assertThat(p.getMinNetEur()).isEqualByComparingTo("2");
        assertThat(p.getMaxNetEur()).isEqualByComparingTo("500");
        assertThat(p.getReconcileInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.isReconcileEnabled()).isFalse();
        assertThat(p.getMmMaxAttempts()).isEqualTo(7);
    }
}
