package com.yadony.api.common.media;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class MediaUploadRepositoryDbTest {

    @Autowired MediaUploadRepository repository;

    @Test
    void countsByOwnerWindow_andByBidPurpose() {
        UUID owner = UUID.randomUUID();
        UUID bid = UUID.randomUUID();
        repository.save(new MediaUploadEntity("tracking/" + bid + "/1.jpg", owner, MediaPurpose.TRACKING, bid, 1000));
        repository.save(new MediaUploadEntity("tracking/" + bid + "/2.jpg", owner, MediaPurpose.TRACKING, bid, 2000));
        repository.save(new MediaUploadEntity("bids/" + owner + "/3.jpg", owner, MediaPurpose.BID, null, 3000));

        LocalDateTime dayAgo = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        assertThat(repository.countByOwnerIdAndCreatedAtAfter(owner, dayAgo)).isEqualTo(3);
        assertThat(repository.countByOwnerIdAndCreatedAtAfter(UUID.randomUUID(), dayAgo)).isZero();
        assertThat(repository.countByBidIdAndPurpose(bid, MediaPurpose.TRACKING)).isEqualTo(2);

        UploadStats stats = repository.statsSince(dayAgo);
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.bytes()).isEqualTo(6000);
    }

    @Test
    void statsSince_isZeroWhenNothing() {
        UploadStats stats = repository.statsSince(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertThat(stats.count()).isZero();
        assertThat(stats.bytes()).isZero();
    }
}
