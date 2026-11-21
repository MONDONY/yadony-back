package com.yadony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Libère les identifiants réservés depuis plus de {@link UsernameService#RESERVATION}. Idempotent. */
@Component
public class UsernamePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsernamePurgeScheduler.class);

    private final ReleasedUsernameRepository releasedRepository;
    private final Clock clock;

    public UsernamePurgeScheduler(ReleasedUsernameRepository releasedRepository, Clock clock) {
        this.releasedRepository = releasedRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        int purged = releasedRepository.deleteByReleasedAtBefore(
                OffsetDateTime.now(clock).minus(UsernameService.RESERVATION));
        log.info("Username purge: {} expired reservation(s) removed", purged);
    }
}
