package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class BidMediaRetentionTest {

    private static BidEntity bid(BidStatus status, LocalDateTime deliveredAt, LocalDateTime updatedAt) throws Exception {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        var d = BidEntity.class.getDeclaredField("deliveredAt"); d.setAccessible(true); d.set(b, deliveredAt);
        var u = com.yadony.api.common.BaseEntity.class.getDeclaredField("updatedAt"); u.setAccessible(true); u.set(b, updatedAt);
        return b;
    }

    @Test
    void completed_usesDeliveredAtPlusDays() throws Exception {
        LocalDateTime delivered = LocalDateTime.of(2026, 10, 1, 12, 0);
        assertThat(BidMediaRetention.end(bid(BidStatus.COMPLETED, delivered, delivered.plusDays(2)), 7))
                .contains(delivered.plusDays(7));
    }

    @Test
    void running_hasNoEnd() throws Exception {
        for (BidStatus s : BidMediaRetention.RUNNING_STATUSES) {
            assertThat(BidMediaRetention.end(bid(s, null, LocalDateTime.now()), 7)).isEmpty();
        }
    }

    @Test
    void cancelled_usesUpdatedAt() throws Exception {
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);
        assertThat(BidMediaRetention.end(bid(BidStatus.CANCELLED, null, end), 30)).contains(end.plusDays(30));
    }
}
