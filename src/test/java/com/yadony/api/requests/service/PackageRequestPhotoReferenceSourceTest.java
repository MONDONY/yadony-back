package com.yadony.api.requests.service;

import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({PackageRequestPhotoReferenceSource.class, PackageRequestLegacyPhotoReferenceSource.class})
class PackageRequestPhotoReferenceSourceTest {

    @Autowired PackageRequestPhotoRepository photos;
    @Autowired PackageRequestRepository requests;
    @Autowired PackageRequestPhotoReferenceSource photoSource;
    @Autowired PackageRequestLegacyPhotoReferenceSource legacySource;

    private PackageRequestEntity request(String photoUrl, boolean softDeleted) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5.00"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setDescription("Vêtements pour la famille");
        e.setTargetPriceEur(new BigDecimal("40.00"));
        e.setStatus(PackageRequestStatus.OPEN);
        e.setPhotoUrl(photoUrl);
        if (softDeleted) e.softDelete();
        return requests.saveAndFlush(e);
    }

    @Test
    void requestPhotos_live_areReferenced_purgedAreNot() {
        UUID requestId = UUID.randomUUID();
        photos.save(new PackageRequestPhotoEntity(requestId, "package_requests/u/live.jpg", 0));
        PackageRequestPhotoEntity purged = photos.save(
                new PackageRequestPhotoEntity(requestId, "package_requests/u/purged.jpg", 1));
        purged.markPurged(LocalDateTime.now(ZoneOffset.UTC));
        photos.saveAndFlush(purged);

        assertThat(photoSource.prefixes()).containsExactly("package_requests/");
        assertThat(photoSource.referencedKeys("package_requests/"))
                .containsExactly("package_requests/u/live.jpg");
    }

    @Test
    void photosOfSoftDeletedRequest_stayReferenced() {
        PackageRequestEntity deleted = request(null, true);
        photos.saveAndFlush(new PackageRequestPhotoEntity(deleted.getId(), "package_requests/u/kept.jpg", 0));

        assertThat(photoSource.referencedKeys("package_requests/")).contains("package_requests/u/kept.jpg");
    }

    @Test
    void legacyPhotoUrl_anyPrefix_softDeletedIncluded_urlNormalized() {
        request("package_requests/u/legacy.jpg", false);
        request("https://h/yadony-prod/package_requests/u/old.jpg?X-Amz-Signature=1", true);
        request("requests/u/dead.jpg", false);

        assertThat(legacySource.prefixes()).contains("package_requests/", "requests/");
        assertThat(legacySource.referencedKeys("package_requests/"))
                .containsExactlyInAnyOrder("package_requests/u/legacy.jpg", "package_requests/u/old.jpg");
        // « requests/ » ne capte pas « package_requests/ » (début de segment seulement).
        assertThat(legacySource.referencedKeys("requests/")).containsExactly("requests/u/dead.jpg");
    }
}
