package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PackageRequestViewCountDbTest {

    @Autowired private PackageRequestRepository repository;
    @Autowired private TestEntityManager em;

    private PackageRequestEntity persistOpenRequest() {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Divo");
        e.setArrivalCity("Annemasse");
        e.setDesiredDate(LocalDate.now().plusDays(10));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("2"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        e.setContentCategory("cosmetiques");
        e.setStatus(PackageRequestStatus.OPEN);
        return repository.saveAndFlush(e);
    }

    @Test
    void newRequest_hasZeroViews() {
        PackageRequestEntity saved = persistOpenRequest();
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getViewCount()).isZero();
    }

    @Test
    void incrementViewCount_addsOneEachCall_withoutTouchingUpdatedAt() {
        PackageRequestEntity saved = persistOpenRequest();
        var updatedAtBefore = saved.getUpdatedAt();

        assertThat(repository.incrementViewCount(saved.getId())).isEqualTo(1);
        repository.incrementViewCount(saved.getId());
        em.clear();

        PackageRequestEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getViewCount()).isEqualTo(2);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
    }

    @Test
    void incrementViewCount_nullColumn_isTreatedAsZero() {
        PackageRequestEntity saved = persistOpenRequest();
        em.getEntityManager()
            .createNativeQuery("UPDATE package_requests SET view_count = NULL WHERE id = :id")
            .setParameter("id", saved.getId())
            .executeUpdate();

        repository.incrementViewCount(saved.getId());
        em.clear();

        assertThat(repository.findById(saved.getId()).orElseThrow().getViewCount()).isEqualTo(1);
    }
}
