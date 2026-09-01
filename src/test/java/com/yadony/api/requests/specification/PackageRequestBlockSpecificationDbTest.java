package com.yadony.api.requests.specification;

import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confidentialité v2 — masquage mutuel appliqué aux demandes de colis.
 *
 * <p>Test au niveau base (H2, profil test) : les deux sous-requêtes sur {@code user_blocks}
 * ne se vérifient qu'une fois traduites en SQL par Hibernate. Miroir de
 * {@code AnnouncementSpecificationBlockTest} côté trajets.
 */
@DataJpaTest
@ActiveProfiles("test")
class PackageRequestBlockSpecificationDbTest {

    @Autowired
    private PackageRequestRepository repository;

    @Autowired
    private TestEntityManager em;

    private PackageRequestEntity persistOpenRequest(UUID senderId) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        return repository.saveAndFlush(e);
    }

    private void persistBlock(UUID blockerId, UUID blockedId) {
        UserBlockEntity b = new UserBlockEntity();
        b.setBlockerId(blockerId);
        b.setBlockedId(blockedId);
        em.persistAndFlush(b);
    }

    @Test
    @DisplayName("aucun blocage → la demande reste dans les résultats")
    void noBlock_requestIsPresent() {
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        PackageRequestEntity req = persistOpenRequest(sender);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.notBlockedBy(viewer);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).contains(req.getId());
    }

    @Test
    @DisplayName("le viewer a bloqué l'expéditeur → demande absente")
    void viewerBlockedSender_requestIsAbsent() {
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        PackageRequestEntity req = persistOpenRequest(sender);
        persistBlock(viewer, sender);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.notBlockedBy(viewer);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).doesNotContain(req.getId());
    }

    @Test
    @DisplayName("l'expéditeur a bloqué le viewer → demande absente aussi (masquage symétrique)")
    void senderBlockedViewer_requestIsAbsent() {
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        PackageRequestEntity req = persistOpenRequest(sender);
        persistBlock(sender, viewer);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.notBlockedBy(viewer);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).doesNotContain(req.getId());
    }

    @Test
    @DisplayName("un blocage sans rapport ne masque pas les autres demandes")
    void unrelatedBlock_otherRequestsStayVisible() {
        UUID viewer = UUID.randomUUID();
        UUID blockedSender = UUID.randomUUID();
        UUID neutralSender = UUID.randomUUID();
        PackageRequestEntity masked = persistOpenRequest(blockedSender);
        PackageRequestEntity visible = persistOpenRequest(neutralSender);
        persistBlock(viewer, blockedSender);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.notBlockedBy(viewer);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId)
                .contains(visible.getId())
                .doesNotContain(masked.getId());
    }

    @Test
    @DisplayName("viewer null (visiteur) → aucun filtre appliqué")
    void nullViewer_noFilterApplied() {
        UUID sender = UUID.randomUUID();
        PackageRequestEntity req = persistOpenRequest(sender);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.notBlockedBy(null);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).contains(req.getId());
    }

    @Test
    @DisplayName("composée avec openOnly() → les deux filtres s'appliquent ensemble")
    void composedWithOpenOnly_bothFiltersApply() {
        UUID viewer = UUID.randomUUID();
        UUID blockedSender = UUID.randomUUID();
        UUID neutralSender = UUID.randomUUID();
        PackageRequestEntity masked = persistOpenRequest(blockedSender);
        PackageRequestEntity visible = persistOpenRequest(neutralSender);
        persistBlock(blockedSender, viewer);

        Specification<PackageRequestEntity> spec = Specification
                .where(PackageRequestSpecifications.openOnly())
                .and(PackageRequestSpecifications.notBlockedBy(viewer));
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId)
                .contains(visible.getId())
                .doesNotContain(masked.getId());
    }
}
