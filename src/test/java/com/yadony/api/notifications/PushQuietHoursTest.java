package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PushQuietHoursTest {

    private static UserEntity user(String country) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setCountry(country);
        return u;
    }

    private static PushQuietHours at(String isoUtc) {
        return new PushQuietHours(true, Clock.fixed(Instant.parse(isoUtc), ZoneOffset.UTC));
    }

    /** 21 h UTC = 23 h à Paris (été) mais 21 h à Dakar : le calme suit l'heure locale. */
    @Test
    void nightIsJudgedAtTheUsersLocalTime() {
        PushQuietHours quiet = at("2026-07-10T21:00:00Z");

        assertThat(quiet.hold(user("FR"), "NEW_MESSAGE")).isTrue();
        assertThat(quiet.hold(user("SN"), "NEW_MESSAGE")).isFalse();
    }

    @Test
    void heldPushesAreCountedForTheMorningDigest() {
        PushQuietHours quiet = at("2026-07-10T01:00:00Z");
        UserEntity awa = user("SN");

        quiet.hold(awa, "NEW_MESSAGE");
        quiet.hold(awa, "BID_CREATED");

        assertThat(quiet.usersWithHeldPushes()).containsExactly(awa.getId());
        assertThat(quiet.isMorning(awa)).isFalse();
        assertThat(quiet.takeHeld(awa.getId())).isEqualTo(2);
        assertThat(quiet.takeHeld(awa.getId())).isZero();
    }

    @Test
    void criticalAndDigestTypes_alwaysRing() {
        PushQuietHours quiet = at("2026-07-10T01:00:00Z");
        UserEntity awa = user("SN");

        assertThat(quiet.hold(awa, "DELIVERY_CONFIRMED")).isFalse();
        assertThat(quiet.hold(awa, "HANDOVER_REMINDER_H2")).isFalse();
        assertThat(quiet.hold(awa, PushQuietHours.NIGHT_DIGEST_TYPE)).isFalse();
        assertThat(quiet.usersWithHeldPushes()).isEmpty();
    }

    @Test
    void daytime_capsAtTenNonCriticalPushes() {
        PushQuietHours quiet = at("2026-07-10T12:00:00Z");
        UserEntity awa = user("SN");

        for (int i = 0; i < PushQuietHours.DAILY_CAP; i++) {
            assertThat(quiet.hold(awa, "NEW_MESSAGE")).isFalse();
        }
        assertThat(quiet.hold(awa, "NEW_MESSAGE")).isTrue();
        assertThat(quiet.hold(awa, "PAYMENT_RELEASED")).isFalse();
        assertThat(quiet.hold(user("SN"), "NEW_MESSAGE")).isFalse();
        assertThat(quiet.isMorning(awa)).isTrue();
    }

    @Test
    void disabled_neverHolds() {
        PushQuietHours quiet = new PushQuietHours(false,
                Clock.fixed(Instant.parse("2026-07-10T01:00:00Z"), ZoneOffset.UTC));

        assertThat(quiet.hold(user("SN"), "NEW_MESSAGE")).isFalse();
    }

    @Test
    void quietWindowRunsFromTenPmToEightAm() {
        assertThat(PushQuietHours.isQuietHour(22)).isTrue();
        assertThat(PushQuietHours.isQuietHour(3)).isTrue();
        assertThat(PushQuietHours.isQuietHour(7)).isTrue();
        assertThat(PushQuietHours.isQuietHour(8)).isFalse();
        assertThat(PushQuietHours.isQuietHour(21)).isFalse();
    }
}
