package com.yadony.api.payments.cash.job;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.cash.CashCommissionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Prévient le titulaire d'une carte de commission qui arrive à expiration. Trois rappels
 * datés, sans état à stocker : le 1er du mois d'expiration, 7 jours avant la fin de ce
 * mois, puis le 1er du mois suivant (carte expirée). Avant, la tâche relançait chaque jour
 * jusqu'au remplacement de la carte, y compris des mois après son expiration.
 */
@Component
public class CardExpirationNotificationJob {

    /** Jours avant la fin du mois d'expiration où part le second rappel. */
    static final int LAST_REMINDER_DAYS_BEFORE_END = 7;

    enum Reminder { NONE, EXPIRING, EXPIRED }

    private final UserRepository userRepo;
    private final NotificationDispatcher notificationDispatcher;
    private final CashCommissionProperties props;
    private final Clock clock;

    @Autowired
    public CardExpirationNotificationJob(UserRepository userRepo,
                                          NotificationDispatcher notificationDispatcher,
                                          CashCommissionProperties props) {
        this(userRepo, notificationDispatcher, props, Clock.systemUTC());
    }

    CardExpirationNotificationJob(UserRepository userRepo,
                                  NotificationDispatcher notificationDispatcher,
                                  CashCommissionProperties props,
                                  Clock clock) {
        this.userRepo = userRepo;
        this.notificationDispatcher = notificationDispatcher;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    @Transactional(readOnly = true)
    public void notifyExpiringCards() {
        LocalDate today = LocalDate.now(clock);
        YearMonth cutoff = YearMonth.from(today).plusMonths(props.cardExpirationWarningDays() / 30L);
        for (UserEntity user : userRepo.findUsersWithCardExpiringBefore(cutoff.getYear(), cutoff.getMonthValue())) {
            Integer year = user.getCommissionCardExpYear();
            Integer month = user.getCommissionCardExpMonth();
            if (year == null || month == null) {
                continue;
            }
            switch (reminderFor(today, YearMonth.of(year, month))) {
                case EXPIRING -> notificationDispatcher.sendCardExpiringNotice(user);
                case EXPIRED -> notificationDispatcher.sendCardExpiredNotice(user);
                case NONE -> { }
            }
        }
    }

    /** Une carte reste valable jusqu'au dernier jour de son mois d'expiration. */
    static Reminder reminderFor(LocalDate today, YearMonth expiry) {
        if (today.equals(expiry.atDay(1))
                || today.equals(expiry.atEndOfMonth().minusDays(LAST_REMINDER_DAYS_BEFORE_END))) {
            return Reminder.EXPIRING;
        }
        if (today.equals(expiry.plusMonths(1).atDay(1))) {
            return Reminder.EXPIRED;
        }
        return Reminder.NONE;
    }
}
