package com.yadony.api.admin.dto;

import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.common.media.PhotoPurgeRunEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoStorageStatusResponseTest {

    @Test
    void runView_copiesFinishedRun() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 12, 0);
        PhotoPurgeRunEntity run = new PhotoPurgeRunEntity(PhotoPurgeRunEntity.KIND_PURGE, true, "ADMIN", start);
        run.finish(new PhotoPurgeOutcome(5, 0, 1), start.plusMinutes(1));

        var view = PhotoStorageStatusResponse.RunView.of(run);

        assertThat(view.kind()).isEqualTo("PURGE");
        assertThat(view.dryRun()).isTrue();
        assertThat(view.triggerSource()).isEqualTo("ADMIN");
        assertThat(view.startedAt()).isEqualTo(start);
        assertThat(view.finishedAt()).isEqualTo(start.plusMinutes(1));
        assertThat(view.eligible()).isEqualTo(5);
        assertThat(view.deleted()).isZero();
        assertThat(view.failed()).isEqualTo(1);
        assertThat(view.error()).isNull();
    }

    @Test
    void runView_exposesErrorOfFailedRun() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 12, 0);
        PhotoPurgeRunEntity run = new PhotoPurgeRunEntity(PhotoPurgeRunEntity.KIND_RECONCILE, false, "SCHEDULE", start);
        run.fail("r2 down", start.plusSeconds(3));

        var view = PhotoStorageStatusResponse.RunView.of(run);

        assertThat(view.kind()).isEqualTo("RECONCILE");
        assertThat(view.error()).isEqualTo("r2 down");
    }
}
