package com.yadony.api.common.media;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde n° 1 du rapprochement : une table oubliée = des photos vivantes effacées.
 * Toute nouvelle colonne qui stocke une clé R2 doit ajouter sa source ET sa ligne ici.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReferenceSourceCoverageIT {

    /** Source dédiée attendue par préfixe (les sources « tous préfixes » des saisies libres ne suffisent pas). */
    private static final Map<String, Set<String>> DEDICATED_SOURCES = Map.of(
            "tracking/", Set.of("TrackingPhotoReferenceSource"),
            "package_requests/", Set.of("PackageRequestPhotoReferenceSource"),
            "bids/", Set.of("BidPhotoReferenceSource"),
            "users/", Set.of("AvatarReferenceSource"),
            "reports/", Set.of("ReportPhotoReferenceSource"),
            "support/", Set.of("SupportAttachmentReferenceSource"));

    @Autowired List<MediaKeyReferenceSource> sources;

    @Test
    void everyReconciledPrefix_hasAtLeastOneDedicatedSource() {
        Set<String> covered = sources.stream().filter(MediaKeyReferenceSource::dedicated)
                .flatMap(s -> s.prefixes().stream()).collect(Collectors.toSet());
        for (String prefix : PhotoReconciliationService.RECONCILED_PREFIXES) {
            if (PhotoReconciliationService.PREFIXES_WITHOUT_SOURCE.contains(prefix)) continue;
            assertThat(covered).as("préfixe %s sans source de références", prefix).contains(prefix);
        }
    }

    @Test
    void everyReconciledPrefix_hasItsDedicatedSources() {
        for (String prefix : PhotoReconciliationService.RECONCILED_PREFIXES) {
            if (PhotoReconciliationService.PREFIXES_WITHOUT_SOURCE.contains(prefix)) continue;
            Set<String> declaring = sources.stream()
                    .filter(s -> s.dedicated() && s.prefixes().contains(prefix))
                    .map(s -> org.springframework.aop.support.AopUtils.getTargetClass(s).getSimpleName())
                    .collect(Collectors.toSet());
            assertThat(DEDICATED_SOURCES).as("préfixe %s absent de la table des sources attendues", prefix)
                    .containsKey(prefix);
            assertThat(declaring).as("sources du préfixe %s", prefix).containsAll(DEDICATED_SOURCES.get(prefix));
        }
    }

    @Test
    void everySourceAnswers_onTheRealSchema() {
        // Les requêtes (natives comprises) s'exécutent sur le schéma réel des entités.
        for (MediaKeyReferenceSource s : sources) {
            for (String prefix : s.prefixes()) {
                assertThat(s.referencedKeys(prefix)).isNotNull();
            }
        }
    }

    @Test
    void messagingIsExcluded_andNoMessagingSourceExists() {
        assertThat(PhotoReconciliationService.RECONCILED_PREFIXES).doesNotContain("messaging/");
        assertThat(sources).noneMatch(s -> org.springframework.aop.support.AopUtils.getTargetClass(s)
                .getSimpleName().contains("Messaging"));
    }

    @Test
    void freeValueSources_areNotDedicated() {
        Set<String> notDedicated = sources.stream().filter(s -> !s.dedicated())
                .map(s -> org.springframework.aop.support.AopUtils.getTargetClass(s).getSimpleName())
                .collect(Collectors.toSet());
        assertThat(notDedicated).containsExactlyInAnyOrder(
                "BidRefusalPhotoReferenceSource", "PackageRequestLegacyPhotoReferenceSource");
    }
}
