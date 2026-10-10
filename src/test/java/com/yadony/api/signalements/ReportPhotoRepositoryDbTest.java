package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ReportPhotoRepositoryDbTest {

    @Autowired ReportRepository reports;
    @Autowired ReportPhotoRepository photos;

    private ReportPhotoEntity persist(ReportStatus status, OffsetDateTime resolvedAt, String key) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(UUID.randomUUID());
        r.setReason(ReportReason.values()[0]);
        r.setStatus(status);
        r.setResolvedAt(resolvedAt);
        reports.saveAndFlush(r);
        ReportPhotoEntity p = new ReportPhotoEntity();
        p.setReportId(r.getId());
        p.setObjectKey(key);
        return photos.saveAndFlush(p);
    }

    @Test
    void renvoieSeulementLesPhotosDeSignalementsClosDepuisAssezLongtemps() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        persist(ReportStatus.OPEN, null, "reports/open.jpg");
        ReportPhotoEntity old = persist(ReportStatus.RESOLVED, now.minusDays(10), "reports/old.jpg");
        persist(ReportStatus.RESOLVED, now.minusDays(3), "reports/recent.jpg");
        ReportPhotoEntity dismissed = persist(ReportStatus.DISMISSED, now.minusDays(9), "reports/dismissed.jpg");

        List<ReportPhotoEntity> due = photos.findLiveOfClosedReportsBefore(now.minusDays(7), PageRequest.of(0, 50));

        assertThat(due).extracting(ReportPhotoEntity::getObjectKey)
                .containsExactly(old.getObjectKey(), dismissed.getObjectKey());
    }

    @Test
    void ignoreLesPhotosDejaSoftDeleted() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ReportPhotoEntity p = persist(ReportStatus.RESOLVED, now.minusDays(10), "reports/gone.jpg");
        p.softDelete();
        photos.saveAndFlush(p);

        assertThat(photos.findLiveOfClosedReportsBefore(now.minusDays(7), PageRequest.of(0, 50))).isEmpty();
    }
}
