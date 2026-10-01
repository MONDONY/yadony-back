package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AnnouncementViewRepositoryTest {

    @Autowired AnnouncementViewRepository repository;

    @Test
    void countByAnnouncementIds_groupeParTrajet_etIgnoreLesTrajetsHorsListe() {
        UUID seenTwice = UUID.randomUUID();
        UUID seenOnce = UUID.randomUUID();
        UUID notAsked = UUID.randomUUID();
        repository.save(new AnnouncementViewEntity(seenTwice, UUID.randomUUID()));
        repository.save(new AnnouncementViewEntity(seenTwice, UUID.randomUUID()));
        repository.save(new AnnouncementViewEntity(seenOnce, UUID.randomUUID()));
        repository.save(new AnnouncementViewEntity(notAsked, UUID.randomUUID()));

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repository.countByAnnouncementIds(List.of(seenTwice, seenOnce, UUID.randomUUID()))) {
            counts.put((UUID) row[0], (Long) row[1]);
        }

        assertThat(counts).containsOnly(Map.entry(seenTwice, 2L), Map.entry(seenOnce, 1L));
    }

    @Test
    void laMemePersonneNeCompteQuUneFois_contrainteUnique() {
        UUID trip = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        repository.saveAndFlush(new AnnouncementViewEntity(trip, viewer));

        assertThatThrownBy(() -> repository.saveAndFlush(new AnnouncementViewEntity(trip, viewer)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
