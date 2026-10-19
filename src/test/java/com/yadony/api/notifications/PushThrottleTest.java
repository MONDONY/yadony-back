package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PushThrottleTest {

    private final AtomicLong nanos = new AtomicLong();
    private final PushThrottle throttle = new PushThrottle(nanos::get);
    private final UUID user = UUID.randomUUID();
    private final String conversation = "conv_" + UUID.randomUUID();

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void chat_firstMessagePushes_nextOnesWaitFiveMinutes() {
        Map<String, String> data = Map.of("type", "NEW_MESSAGE", "conversationId", conversation);

        assertThat(throttle.hold(user, "NEW_MESSAGE", data)).isFalse();
        assertThat(throttle.hold(user, "NEW_MESSAGE", data)).isTrue();
        advance(Duration.ofMinutes(4));
        assertThat(throttle.hold(user, "NEW_MESSAGE", data)).isTrue();
        advance(Duration.ofMinutes(6));
        assertThat(throttle.hold(user, "NEW_MESSAGE", data)).isFalse();
    }

    @Test
    void streamsAreSeparate_perConversationAndPerUser() {
        Map<String, String> a = Map.of("conversationId", conversation);
        Map<String, String> b = Map.of("conversationId", "conv_" + UUID.randomUUID());

        assertThat(throttle.hold(user, "NEW_MESSAGE", a)).isFalse();
        assertThat(throttle.hold(user, "NEW_MESSAGE", b)).isFalse();
        assertThat(throttle.hold(UUID.randomUUID(), "NEW_MESSAGE", a)).isFalse();
    }

    @Test
    void newBidsOnATrip_waitFifteenMinutes() {
        Map<String, String> data = Map.of("announcementId", UUID.randomUUID().toString());

        assertThat(throttle.hold(user, "BID_CREATED", data)).isFalse();
        advance(Duration.ofMinutes(14));
        assertThat(throttle.hold(user, "BID_CREATED", data)).isTrue();
        advance(Duration.ofMinutes(2));
        assertThat(throttle.hold(user, "BID_CREATED", data)).isFalse();
    }

    /** Un trajet annulé avec trois colis du même expéditeur : une seule sonnerie. */
    @Test
    void burstTypes_ringOncePerTwoMinutes() {
        Map<String, String> data = Map.of("bidId", UUID.randomUUID().toString());

        assertThat(throttle.hold(user, "TRIP_CANCELLED", data)).isFalse();
        assertThat(throttle.hold(user, "TRIP_CANCELLED", Map.of("bidId", UUID.randomUUID().toString()))).isTrue();
        assertThat(throttle.hold(user, "TRIP_ARRIVED", data)).isFalse();
        advance(Duration.ofMinutes(3));
        assertThat(throttle.hold(user, "TRIP_CANCELLED", data)).isFalse();
    }

    @Test
    void criticalAndUnlistedTypes_areNeverHeld() {
        Map<String, String> data = Map.of("bidId", UUID.randomUUID().toString());

        for (int i = 0; i < 3; i++) {
            assertThat(throttle.hold(user, "DELIVERY_CONFIRMED", data)).isFalse();
            assertThat(throttle.hold(user, "CONFIRMATION_CODE_READY", data)).isFalse();
        }
        assertThat(throttle.hold(null, "NEW_MESSAGE", Map.of("conversationId", conversation))).isFalse();
        assertThat(throttle.hold(user, null, data)).isFalse();
    }

    /** Sans identifiant exploitable, pas de flux : rien n'est retenu plutôt que tout. */
    @Test
    void chatWithoutUsableId_isNeverHeld() {
        assertThat(throttle.hold(user, "NEW_MESSAGE", Map.of())).isFalse();
        assertThat(throttle.hold(user, "NEW_MESSAGE", Map.of())).isFalse();
    }

    /** Deux alertes, ou une alerte et un abonnement, pour le même trajet : une seule sonnerie. */
    @Test
    void sameTripFromSeveralSources_ringsOncePerDay() {
        String trip = UUID.randomUUID().toString();
        Map<String, String> alertA = Map.of("alertId", UUID.randomUUID().toString(), "announcementId", trip);
        Map<String, String> alertB = Map.of("alertId", UUID.randomUUID().toString(), "announcementId", trip);
        Map<String, String> follow = Map.of("travelerId", UUID.randomUUID().toString(), "announcementId", trip);

        assertThat(throttle.hold(user, "CORRIDOR_ALERT", alertA)).isFalse();
        assertThat(throttle.hold(user, "CORRIDOR_ALERT", alertB)).isTrue();
        assertThat(throttle.hold(user, "TRAVELER_NEW_ANNOUNCEMENT", follow)).isTrue();
        assertThat(throttle.hold(UUID.randomUUID(), "TRAVELER_NEW_ANNOUNCEMENT", follow)).isFalse();
        assertThat(throttle.hold(user, "CORRIDOR_ALERT",
                Map.of("announcementId", UUID.randomUUID().toString()))).isFalse();
        advance(Duration.ofHours(25));
        assertThat(throttle.hold(user, "CORRIDOR_ALERT", alertB)).isFalse();
    }

    /** Même demande de colis : alerte du voyageur et colis compatible ne sonnent qu'une fois. */
    @Test
    void samePackageRequest_ringsOnce() {
        Map<String, String> data = Map.of("requestId", UUID.randomUUID().toString());

        assertThat(throttle.hold(user, "PACKAGE_MATCH", data)).isFalse();
        assertThat(throttle.hold(user, "CORRIDOR_ALERT", data)).isTrue();
    }

    /** Le récapitulatif de 9 h n'a ni trajet ni demande : il n'est jamais retenu. */
    @Test
    void discoveryWithoutSubject_isNeverHeld() {
        Map<String, String> digest = Map.of("corridor", "Paris-Dakar");

        assertThat(throttle.hold(user, "CORRIDOR_ALERT", digest)).isFalse();
        assertThat(throttle.hold(user, "CORRIDOR_ALERT", digest)).isFalse();
    }
}
