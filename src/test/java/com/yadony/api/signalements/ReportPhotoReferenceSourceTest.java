package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(ReportPhotoReferenceSource.class)
class ReportPhotoReferenceSourceTest {

    @Autowired ReportPhotoRepository photos;
    @Autowired ReportPhotoReferenceSource source;

    private ReportPhotoEntity photo(String key, boolean purged) {
        ReportPhotoEntity p = new ReportPhotoEntity();
        p.setReportId(UUID.randomUUID());
        p.setObjectKey(key);
        if (purged) p.softDelete();
        return photos.saveAndFlush(p);
    }

    @Test
    void livePhoto_isReferenced_purgedIsNot() {
        photo("reports/u/live.jpg", false);
        photo("reports/u/purged.jpg", true);   // supprimée par la purge après R2

        assertThat(source.prefixes()).containsExactly("reports/");
        assertThat(source.referencedKeys("reports/")).containsExactly("reports/u/live.jpg");
    }
}
