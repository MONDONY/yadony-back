package com.yadony.api.requests.specification;

import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * « Date flexible » (V328) évaluée en SQL réel : une demande sans date reste dans les
 * recherches filtrées par date, n'est jamais urgente, et expire 60 jours après sa création.
 */
@DataJpaTest
@ActiveProfiles("test")
class PackageRequestFlexibleDateDbTest {

    @Autowired private PackageRequestRepository repository;
    @Autowired private EntityManager em;

    private PackageRequestEntity persist(LocalDate desiredDate) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(desiredDate);
        e.setDateToleranceDays(desiredDate == null ? null : (short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        return repository.saveAndFlush(e);
    }

    private void backdateCreation(PackageRequestEntity e, LocalDateTime createdAt) {
        em.createNativeQuery("UPDATE package_requests SET created_at = :c WHERE id = :id")
            .setParameter("c", createdAt)
            .setParameter("id", e.getId())
            .executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("une demande sans date est enregistrée avec date et tolérance nulles")
    void persists_withoutDate() {
        PackageRequestEntity flexible = persist(null);
        em.clear();

        PackageRequestEntity reloaded = repository.findById(flexible.getId()).orElseThrow();
        assertThat(reloaded.getDesiredDate()).isNull();
        assertThat(reloaded.getDateToleranceDays()).isNull();
    }

    @Test
    @DisplayName("dateRange(from, to), (from, null), (null, to) incluent la demande sans date")
    void dateRange_includesFlexibleRequests() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PackageRequestEntity flexible = persist(null);
        PackageRequestEntity inside = persist(today.plusDays(5));
        PackageRequestEntity outside = persist(today.plusDays(40));

        List<UUID> both = repository.findAll(PackageRequestSpecifications.dateRange(today, today.plusDays(10)))
            .stream().map(PackageRequestEntity::getId).toList();
        assertThat(both).contains(flexible.getId(), inside.getId()).doesNotContain(outside.getId());

        List<UUID> fromOnly = repository.findAll(PackageRequestSpecifications.dateRange(today.plusDays(30), null))
            .stream().map(PackageRequestEntity::getId).toList();
        assertThat(fromOnly).contains(flexible.getId(), outside.getId()).doesNotContain(inside.getId());

        List<UUID> toOnly = repository.findAll(PackageRequestSpecifications.dateRange(null, today.plusDays(10)))
            .stream().map(PackageRequestEntity::getId).toList();
        assertThat(toOnly).contains(flexible.getId(), inside.getId()).doesNotContain(outside.getId());
    }

    @Test
    @DisplayName("urgent() exclut la demande sans date")
    void urgent_excludesFlexibleRequests() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PackageRequestEntity flexible = persist(null);
        PackageRequestEntity soon = persist(today.plusDays(1));

        List<UUID> urgent = repository.findAll(PackageRequestSpecifications.urgent(3))
            .stream().map(PackageRequestEntity::getId).toList();
        assertThat(urgent).contains(soon.getId()).doesNotContain(flexible.getId());
    }

    @Test
    @DisplayName("findFlexibleExpired : publiée (ou créée) il y a plus de 60 jours → expirée, 59 jours → non")
    void findFlexibleExpired_sixtyDaysAfterCreation() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PackageRequestEntity old = persist(null);
        PackageRequestEntity recent = persist(null);
        PackageRequestEntity datedOld = persist(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        backdateCreation(old, now.minusDays(60).minusHours(1));
        backdateCreation(recent, now.minusDays(59));
        backdateCreation(datedOld, now.minusDays(90));

        List<UUID> expired = repository.findFlexibleExpired(now.minusDays(60))
            .stream().map(PackageRequestEntity::getId).toList();

        assertThat(expired).contains(old.getId()).doesNotContain(recent.getId(), datedOld.getId());
    }

    @Test
    @DisplayName("findFlexibleExpired : un vieux brouillon publié il y a 10 jours n'expire pas")
    void findFlexibleExpired_countsFromPublication() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PackageRequestEntity oldDraftPublishedRecently = persist(null);
        PackageRequestEntity publishedLongAgo = persist(null);
        backdateCreation(oldDraftPublishedRecently, now.minusDays(80));
        backdateCreation(publishedLongAgo, now.minusDays(80));
        em.createNativeQuery("UPDATE package_requests SET disclaimer_signed_at = :d WHERE id = :id")
            .setParameter("d", now.minusDays(10)).setParameter("id", oldDraftPublishedRecently.getId())
            .executeUpdate();
        em.createNativeQuery("UPDATE package_requests SET disclaimer_signed_at = :d WHERE id = :id")
            .setParameter("d", now.minusDays(61)).setParameter("id", publishedLongAgo.getId())
            .executeUpdate();
        em.clear();

        List<UUID> expired = repository.findFlexibleExpired(now.minusDays(60))
            .stream().map(PackageRequestEntity::getId).toList();

        assertThat(expired).contains(publishedLongAgo.getId()).doesNotContain(oldDraftPublishedRecently.getId());
    }

    @Test
    @DisplayName("findExpired (règle des demandes datées) ignore les demandes sans date")
    void findExpired_ignoresFlexibleRequests() {
        PackageRequestEntity flexible = persist(null);

        List<UUID> expired = repository.findExpired(LocalDate.now(ZoneOffset.UTC).plusDays(365))
            .stream().map(PackageRequestEntity::getId).toList();

        assertThat(expired).doesNotContain(flexible.getId());
    }
}
