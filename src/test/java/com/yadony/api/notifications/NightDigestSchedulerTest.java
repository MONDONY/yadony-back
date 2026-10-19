package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NightDigestSchedulerTest {

    @Mock UserRepository userRepository;
    @Mock FcmService fcmService;
    @Mock NotificationDispatcher dispatcher;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-10T01:00:00Z"));
    private PushQuietHours quietHours;
    private NightDigestScheduler scheduler;
    private UserEntity awa;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        quietHours = new PushQuietHours(true, clock);
        scheduler = new NightDigestScheduler(quietHours, userRepository, fcmService, dispatcher);
        awa = new UserEntity();
        ReflectionTestUtils.setField(awa, "id", UUID.randomUUID());
        awa.setCountry("SN");
        lenient().when(userRepository.findById(awa.getId())).thenReturn(Optional.of(awa));
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    void stillNight_sendsNothingAndKeepsTheCount() {
        quietHours.hold(awa, "NEW_MESSAGE");

        scheduler.sendMorningDigests();

        verifyNoInteractions(fcmService);
        assertThat(quietHours.usersWithHeldPushes()).containsExactly(awa.getId());
    }

    @Test
    void morning_sendsOneDigestWithTheHeldCount() {
        quietHours.hold(awa, "NEW_MESSAGE");
        quietHours.hold(awa, "BID_CREATED");
        quietHours.hold(awa, "TRIP_ARRIVED");
        now.set(Instant.parse("2026-07-10T08:05:00Z"));

        scheduler.sendMorningDigests();
        scheduler.sendMorningDigests();

        verify(fcmService, times(1)).sendToUser(eq(awa.getId()), eq("Pendant la nuit"),
                eq("3 notifications vous attendent dans Yadony."),
                eq(Map.of("type", PushQuietHours.NIGHT_DIGEST_TYPE, "count", "3")));
    }

    @Test
    void morning_singleHeldPush_usesTheSingular() {
        quietHours.hold(awa, "NEW_MESSAGE");
        now.set(Instant.parse("2026-07-10T09:00:00Z"));

        scheduler.sendMorningDigests();

        verify(fcmService).sendToUser(eq(awa.getId()), anyString(),
                eq("1 notification vous attend dans Yadony."), any());
    }

    @Test
    void deletedUser_isDroppedWithoutPush() {
        quietHours.hold(awa, "NEW_MESSAGE");
        when(userRepository.findById(awa.getId())).thenReturn(Optional.empty());

        scheduler.sendMorningDigests();

        verifyNoInteractions(fcmService);
        assertThat(quietHours.usersWithHeldPushes()).isEmpty();
    }
}
