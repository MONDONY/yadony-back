package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestViewEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PackageRequestViewRepositoryTest {

    @Autowired PackageRequestViewRepository repository;

    @Test
    void countByPackageRequestIds_groupeParDemande_etIgnoreLesDemandesHorsListe() {
        UUID seenTwice = UUID.randomUUID();
        UUID seenOnce = UUID.randomUUID();
        UUID notAsked = UUID.randomUUID();
        repository.save(new PackageRequestViewEntity(seenTwice, UUID.randomUUID()));
        repository.save(new PackageRequestViewEntity(seenTwice, UUID.randomUUID()));
        repository.save(new PackageRequestViewEntity(seenOnce, UUID.randomUUID()));
        repository.save(new PackageRequestViewEntity(notAsked, UUID.randomUUID()));

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repository.countByPackageRequestIds(List.of(seenTwice, seenOnce, UUID.randomUUID()))) {
            counts.put((UUID) row[0], (Long) row[1]);
        }

        assertThat(counts).containsOnly(Map.entry(seenTwice, 2L), Map.entry(seenOnce, 1L));
    }
}
