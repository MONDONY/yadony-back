package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationCategoryTest {

    @Test
    void broadcastsAndSanctionsAreAnnonces() {
        assertThat(NotificationCategory.fromType("ADMIN_BROADCAST")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationCategory.fromType("SYSTEM")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationCategory.fromType("ADMIN_WARNING")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationCategory.fromType("MESSAGING_MUTED")).isEqualTo(NotificationCategory.ANNONCE);
    }

    @Test
    void moneyAndIdentityArePaiements() {
        assertThat(NotificationCategory.fromType("PAYMENT_RELEASED")).isEqualTo(NotificationCategory.PAIEMENTS);
        assertThat(NotificationCategory.fromType("MM_PAYMENT_PENDING")).isEqualTo(NotificationCategory.PAIEMENTS);
        assertThat(NotificationCategory.fromType("KYC_ACTION_REQUIRED")).isEqualTo(NotificationCategory.PAIEMENTS);
        assertThat(NotificationCategory.fromType("CARD_EXPIRING")).isEqualTo(NotificationCategory.PAIEMENTS);
        assertThat(NotificationCategory.fromType("negotiation_commission_pending")).isEqualTo(NotificationCategory.PAIEMENTS);
        assertThat(NotificationCategory.fromType("negotiation_awaiting_payment")).isEqualTo(NotificationCategory.PAIEMENTS);
    }

    @Test
    void tripsAlertsAndRequestsAreTrajets() {
        assertThat(NotificationCategory.fromType("CORRIDOR_ALERT")).isEqualTo(NotificationCategory.TRAJETS);
        assertThat(NotificationCategory.fromType("TRIP_IN_PROGRESS")).isEqualTo(NotificationCategory.TRAJETS);
        assertThat(NotificationCategory.fromType("PACKAGE_MATCH")).isEqualTo(NotificationCategory.TRAJETS);
        assertThat(NotificationCategory.fromType("negotiation_started")).isEqualTo(NotificationCategory.TRAJETS);
        assertThat(NotificationCategory.fromType("request_accepted")).isEqualTo(NotificationCategory.TRAJETS);
        assertThat(NotificationCategory.fromType("ANNOUNCEMENT_REMOVED")).isEqualTo(NotificationCategory.TRAJETS);
    }

    @Test
    void parcelsDisputesAndUnknownTypesAreColis() {
        assertThat(NotificationCategory.fromType("BID_CREATED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("DELIVERY_CONFIRMED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("DISPUTE_OPENED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("TRIP_CANCELLED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("bid_negotiation_message")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("TYPE_INCONNU")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType(null)).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("")).isEqualTo(NotificationCategory.COLIS);
    }

    @Test
    void apiCodeIsLowercaseFrench() {
        assertThat(NotificationCategory.ANNONCE.code()).isEqualTo("annonce");
        assertThat(NotificationCategory.PAIEMENTS.code()).isEqualTo("paiements");
        assertThat(NotificationCategory.TRAJETS.code()).isEqualTo("trajets");
        assertThat(NotificationCategory.COLIS.code()).isEqualTo("colis");
    }
}
