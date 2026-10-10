package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PackageRequestPhotoRepositoryPurgeDbTest {

    @Autowired private PackageRequestPhotoRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void purgedPhoto_isHiddenFromQueries() {
        UUID requestId = UUID.randomUUID();
        PackageRequestPhotoEntity live = repository.save(new PackageRequestPhotoEntity(requestId, "live.jpg", 0));
        PackageRequestPhotoEntity purged = repository.save(new PackageRequestPhotoEntity(requestId, "purged.jpg", 1));
        purged.markPurged(LocalDateTime.of(2026, 10, 10, 10, 0));
        repository.saveAndFlush(purged);
        em.clear();

        assertThat(repository.findByPackageRequestIdOrderByPositionAsc(requestId))
                .extracting(PackageRequestPhotoEntity::getId).containsExactly(live.getId());
        assertThat(repository.findRequestIdsWithLivePhotos()).containsExactly(requestId);
    }

    @Test
    void requestWithOnlyPurgedPhotos_isNotListed() {
        UUID requestId = UUID.randomUUID();
        PackageRequestPhotoEntity p = repository.save(new PackageRequestPhotoEntity(requestId, "p.jpg", 0));
        p.markPurged(LocalDateTime.of(2026, 10, 10, 10, 0));
        repository.saveAndFlush(p);
        em.clear();

        List<UUID> ids = repository.findRequestIdsWithLivePhotos();
        assertThat(ids).doesNotContain(requestId);
    }
}
