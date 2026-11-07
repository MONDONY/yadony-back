package com.yadony.api.alerts;

import com.yadony.api.auth.events.UserFinalizedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchInterestRetentionTest {

    private final SearchInterestRepository repository = mock(SearchInterestRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T03:40:00Z"), ZoneOffset.UTC);
    private final SearchInterestRetention retention = new SearchInterestRetention(repository, clock);

    @Test
    void purge_erasesSearchesOlderThanThirtyDays() {
        when(repository.purgeSearchedBefore(LocalDateTime.of(2026, 10, 8, 3, 40))).thenReturn(4);
        retention.purgeExpired();
        verify(repository).purgeSearchedBefore(LocalDateTime.of(2026, 10, 8, 3, 40));

        when(repository.purgeSearchedBefore(LocalDateTime.of(2026, 10, 8, 3, 40))).thenReturn(0);
        retention.purgeExpired();
    }

    @Test
    void finalizedAccount_losesAllItsSearches() {
        UUID userId = UUID.randomUUID();
        retention.onUserFinalized(new UserFinalizedEvent(userId, null));

        verify(repository).purgeByUserId(userId);
    }
}
