package com.yadony.api.tracking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(TrackingPhotoReferenceSource.class)
class TrackingPhotoReferenceSourceTest {

    @Autowired TrackingEventRepository events;
    @Autowired TrackingPhotoReferenceSource source;

    private TrackingEventEntity event(UUID bidId, String photo) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setBidId(bidId);
        e.setEventType(TrackingEventType.DEPART);
        e.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        e.setPhotoUrl(photo);
        return events.saveAndFlush(e);
    }

    @Test
    void livePhoto_isReferenced_purgedIsNot_legacyUrlIsNormalized() {
        UUID bid = UUID.randomUUID();
        event(bid, "tracking/" + bid + "/live.jpg");
        event(bid, "https://acc.r2.cloudflarestorage.com/yadony-prod/tracking/" + bid + "/legacy.jpg?X-Amz-Signature=1");
        event(bid, null);
        TrackingEventEntity purged = event(bid, "tracking/" + bid + "/purged.jpg");
        purged.markPhotoPurged(LocalDateTime.now(ZoneOffset.UTC));
        events.saveAndFlush(purged);

        assertThat(source.prefixes()).containsExactly("tracking/");
        assertThat(source.referencedKeys("tracking/")).containsExactlyInAnyOrder(
                "tracking/" + bid + "/live.jpg", "tracking/" + bid + "/legacy.jpg");
    }
}
