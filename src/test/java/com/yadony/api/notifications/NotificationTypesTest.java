package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTypesTest {

    /**
     * Garde-fou de synchronisation : le client Flutter tient la même liste dans
     * {@code notification_service.dart} pour décider quels pushes accuser depuis
     * l'arrière-plan. Un type ajouté ici sans être ajouté là-bas deviendrait
     * critique côté serveur sans jamais être accusé — donc doublé par un SMS à
     * chaque envoi. Ce test force à traiter les deux côtés dans le même geste.
     */
    @Test
    void criticalSet_isExactlyTheThreeSharedWithTheFlutterClient() {
        assertThat(NotificationTypes.CRITICAL)
                .containsExactlyInAnyOrder("PAYMENT_RELEASED", "DELIVERY_CONFIRMED", "DISPUTE_OPENED");
    }

    @Test
    void isCritical_criticalTypes_returnTrue() {
        assertThat(NotificationTypes.isCritical("PAYMENT_RELEASED")).isTrue();
        assertThat(NotificationTypes.isCritical("DELIVERY_CONFIRMED")).isTrue();
        assertThat(NotificationTypes.isCritical("DISPUTE_OPENED")).isTrue();
    }

    @Test
    void isCritical_nullOrOtherType_returnsFalse() {
        assertThat(NotificationTypes.isCritical(null)).isFalse();
        assertThat(NotificationTypes.isCritical("BID_CREATED")).isFalse();
        assertThat(NotificationTypes.isCritical("")).isFalse();
    }
}
