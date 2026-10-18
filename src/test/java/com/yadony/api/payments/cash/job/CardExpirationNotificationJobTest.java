package com.yadony.api.payments.cash.job;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.cash.CashCommissionProperties;
import com.yadony.api.payments.cash.job.CardExpirationNotificationJob.Reminder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardExpirationNotificationJobTest {

    @Mock private UserRepository userRepo;
    @Mock private NotificationDispatcher notificationDispatcher;

    private CardExpirationNotificationJob jobOn(LocalDate day) {
        CashCommissionProperties props = new CashCommissionProperties(
                "0 */15 * * * *", 30, "0 0 * * * *", 30);
        Clock clock = Clock.fixed(day.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new CardExpirationNotificationJob(userRepo, notificationDispatcher, props, clock);
    }

    private static UserEntity cardExpiring(int year, int month) {
        UserEntity user = new UserEntity();
        user.setCommissionPaymentMethodId("pm_abc");
        user.setCommissionCardBrand("Visa");
        user.setCommissionCardLast4("4242");
        user.setCommissionCardExpYear(year);
        user.setCommissionCardExpMonth(month);
        return user;
    }

    @Test
    void firstDayOfExpiryMonth_warnsThatTheCardExpires() {
        UserEntity user = cardExpiring(2026, 11);
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of(user));

        jobOn(LocalDate.of(2026, 11, 1)).notifyExpiringCards();

        verify(notificationDispatcher).sendCardExpiringNotice(user);
        verify(notificationDispatcher, never()).sendCardExpiredNotice(user);
    }

    @Test
    void sevenDaysBeforeEndOfMonth_warnsAgain() {
        UserEntity user = cardExpiring(2026, 11);
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of(user));

        jobOn(LocalDate.of(2026, 11, 23)).notifyExpiringCards();

        verify(notificationDispatcher).sendCardExpiringNotice(user);
    }

    @Test
    void dayAfterExpiry_saysTheCardHasExpired() {
        UserEntity user = cardExpiring(2026, 11);
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of(user));

        jobOn(LocalDate.of(2026, 12, 1)).notifyExpiringCards();

        verify(notificationDispatcher).sendCardExpiredNotice(user);
        verify(notificationDispatcher, never()).sendCardExpiringNotice(user);
    }

    /**
     * Bruit : la tâche relançait chaque jour, y compris des mois après l'expiration. Les
     * autres jours, et pour une carte expirée depuis longtemps, rien ne part.
     */
    @Test
    void otherDays_andLongExpiredCards_sendNothing() {
        UserEntity expiresThisMonth = cardExpiring(2026, 11);
        UserEntity expiredLongAgo = cardExpiring(2026, 3);
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt()))
                .thenReturn(List.of(expiresThisMonth, expiredLongAgo));

        jobOn(LocalDate.of(2026, 11, 10)).notifyExpiringCards();

        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void cardWithoutExpiryDate_isIgnored() {
        UserEntity user = new UserEntity();
        user.setCommissionPaymentMethodId("pm_abc");
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of(user));

        jobOn(LocalDate.of(2026, 11, 1)).notifyExpiringCards();

        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void noExpiringCardsIsNoOp() {
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of());

        jobOn(LocalDate.of(2026, 11, 1)).notifyExpiringCards();

        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void reminderFor_coversExactlyThreeDaysPerCard() {
        YearMonth february = YearMonth.of(2027, 2);
        long reminders = february.atDay(1).minusDays(5)
                .datesUntil(february.plusMonths(2).atDay(1))
                .filter(d -> CardExpirationNotificationJob.reminderFor(d, february) != Reminder.NONE)
                .count();

        assertThat(reminders).isEqualTo(3);
        assertThat(CardExpirationNotificationJob.reminderFor(LocalDate.of(2027, 2, 21), february))
                .isEqualTo(Reminder.EXPIRING);
        assertThat(CardExpirationNotificationJob.reminderFor(LocalDate.of(2027, 3, 1), february))
                .isEqualTo(Reminder.EXPIRED);
    }
}
