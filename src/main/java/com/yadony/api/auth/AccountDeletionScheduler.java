package com.yadony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class AccountDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);

    /** Délai de grâce entre la demande de suppression et sa finalisation (RGPD, Story 9.8). */
    public static final java.time.Duration GRACE_PERIOD = java.time.Duration.ofDays(30);

    /** Date à laquelle le scheduler finalisera une demande faite à {@code requestedAt}. */
    public static Instant scheduledFinalization(Instant requestedAt) {
        return requestedAt != null ? requestedAt.plus(GRACE_PERIOD) : null;
    }

    private final UserRepository userRepository;
    private final UserService userService;

    public AccountDeletionScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void finalizeExpiredDeletions() {
        Instant cutoff = Instant.now().minus(GRACE_PERIOD);
        List<UserEntity> toDelete = userRepository
                .findByStatusAndDeletionRequestedAtBefore(UserStatus.PENDING_DELETION, cutoff);

        log.info("Account deletion scheduler: {} account(s) to finalize", toDelete.size());
        toDelete.forEach(userService::finalizeGdprDeletion);
    }
}
