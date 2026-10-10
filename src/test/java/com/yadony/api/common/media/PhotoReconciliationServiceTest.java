package com.yadony.api.common.media;

import com.yadony.api.common.StorageService;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoReconciliationServiceTest {

    @Mock StorageService storage;
    @Mock PlatformSettingsService settings;
    @Mock PhotoPurgeRunRepository runs;
    @Mock PhotoPurgeRunner runner;   // pour le verrou partagé
    @Mock MediaKeyReferenceSource trackingSource;
    @Mock MediaKeyReferenceSource bidsSource;

    PhotoReconciliationService service;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-10T04:10:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(runner.tryLock()).thenReturn(true);
        lenient().when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(trackingSource.prefixes()).thenReturn(Set.of("tracking/"));
        lenient().when(bidsSource.prefixes()).thenReturn(Set.of("bids/"));
        // Mockito renvoie false pour une méthode default non bouchonnée.
        lenient().when(trackingSource.dedicated()).thenReturn(true);
        lenient().when(bidsSource.dedicated()).thenReturn(true);
        lenient().when(storage.listObjectsOlderThan(anyString(), any(), anyInt())).thenReturn(List.of());
        service = new PhotoReconciliationService(storage, List.of(trackingSource), settings, runs, runner, clock,
                List.of("tracking/"));
    }

    @Test
    void referencedKey_isKept_orphan_isDeleted() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt()))
                .thenReturn(List.of("tracking/b/live.jpg", "tracking/b/orphan.jpg"));
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of("tracking/b/live.jpg"));

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        verify(storage).deleteFile("tracking/b/orphan.jpg");
        verify(storage, never()).deleteFile("tracking/b/live.jpg");
        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(run.getKind()).isEqualTo(PhotoPurgeRunEntity.KIND_RECONCILE);
        assertThat(run.getError()).isNull();
        verify(runner).unlock();
    }

    @Test
    void switchOff_deletesNothing_butCounts() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(List.of("tracking/b/orphan.jpg"));
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of());

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN).orElseThrow();

        verify(storage, never()).deleteFile(any());
        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getEligible()).isEqualTo(1);
    }

    @Test
    void prefixWithoutSource_failsRun_andDeletesNothing() {
        service = new PhotoReconciliationService(storage, List.of(trackingSource), settings, runs, runner, clock,
                List.of("tracking/", "bids/"));
        lenient().when(settings.photoPurgeEnabled()).thenReturn(true);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getError()).contains("bids/");
        verify(storage, never()).deleteFile(any());
        verify(runner).unlock();
    }

    @Test
    void declaredDeadPrefix_needsNoSource_andIsEmptied() {
        service = new PhotoReconciliationService(storage, List.of(trackingSource), settings, runs, runner, clock,
                List.of("tracking/", "requests/"));
        when(settings.photoPurgeEnabled()).thenReturn(true);
        // 200 clés mortes : le préfixe mort n'est pas soumis à la garde de volume.
        List<String> dead = IntStream.range(0, 200).mapToObj(i -> "requests/u/" + i + ".jpg").toList();
        when(storage.listObjectsOlderThan(eq("requests/"), any(), anyInt())).thenReturn(dead);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getError()).isNull();
        assertThat(run.getDeleted()).isEqualTo(200);
        assertThat(run.getFailed()).isZero();
    }

    @Test
    void lockBusy_returnsEmpty() {
        when(runner.tryLock()).thenReturn(false);
        assertThat(service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN)).isEmpty();
        verifyNoInteractions(storage);
        verify(runner, never()).unlock();
    }

    @Test
    void cutoffIs24hBeforeNow() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        verify(storage).listObjectsOlderThan("tracking/", Instant.parse("2026-10-09T04:10:00Z"),
                PhotoReconciliationService.MAX_LISTED_PER_PREFIX);
    }

    @Test
    void referencesAreReadBeforeListing() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        var order = org.mockito.Mockito.inOrder(trackingSource, storage);

        service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE);

        order.verify(trackingSource).referencedKeys("tracking/");
        order.verify(storage).listObjectsOlderThan(eq("tracking/"), any(), anyInt());
    }

    @Test
    void unionOfAllSourcesOfAPrefix_isKept() {
        MediaKeyReferenceSource other = org.mockito.Mockito.mock(MediaKeyReferenceSource.class);
        when(other.prefixes()).thenReturn(Set.of("tracking/", "bids/"));
        lenient().when(other.dedicated()).thenReturn(false);
        when(other.referencedKeys("tracking/")).thenReturn(Set.of("tracking/b/other.jpg"));
        service = new PhotoReconciliationService(storage, List.of(trackingSource, other), settings, runs, runner,
                clock, List.of("tracking/"));
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of("tracking/b/live.jpg"));
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt()))
                .thenReturn(List.of("tracking/b/live.jpg", "tracking/b/other.jpg", "tracking/b/orphan.jpg"));

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        verify(storage).deleteFile("tracking/b/orphan.jpg");
        verify(storage, times(1)).deleteFile(any());
        assertThat(run.getDeleted()).isEqualTo(1);
    }

    @Test
    void sourceInError_skipsPrefix_withoutDeleting_andOtherPrefixesContinue() {
        service = new PhotoReconciliationService(storage, List.of(trackingSource, bidsSource), settings, runs,
                runner, clock, List.of("tracking/", "bids/"));
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(trackingSource.referencedKeys("tracking/")).thenThrow(new IllegalStateException("base indisponible"));
        when(bidsSource.referencedKeys("bids/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("bids/"), any(), anyInt())).thenReturn(List.of("bids/s/orphan.jpg"));

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        verify(storage, never()).listObjectsOlderThan(eq("tracking/"), any(), anyInt());
        verify(storage).deleteFile("bids/s/orphan.jpg");
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getDeleted()).isEqualTo(1);
    }

    @Test
    void deleteFailure_isCounted_andRunContinues() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt()))
                .thenReturn(List.of("tracking/b/1.jpg", "tracking/b/2.jpg"));
        doThrow(new RuntimeException("R2 en panne")).when(storage).deleteFile("tracking/b/1.jpg");

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        verify(storage).deleteFile("tracking/b/2.jpg");
        assertThat(run.getEligible()).isEqualTo(2);
        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(1);
    }

    @Test
    void unexpectedError_marksRunInError_andReleasesLock() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(trackingSource.prefixes()).thenThrow(new IllegalStateException("boum"));

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getError()).contains("boum");
        assertThat(run.getFinishedAt()).isNotNull();
        verify(storage, never()).deleteFile(any());
        verify(runner).unlock();
    }

    @Test
    void deletionsAreCappedPerRun() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        int total = PhotoReconciliationService.MAX_DELETES_PER_RUN + 10;
        List<String> listed = new ArrayList<>();
        // Une clé vivante pour 49 orphelines n'est pas acceptable pour la garde : on garde
        // assez de clés vivantes pour rester sous 50 % d'orphelins.
        for (int i = 0; i < total; i++) listed.add("tracking/b/orphan-" + i + ".jpg");
        Set<String> live = IntStream.range(0, total + 1).mapToObj(i -> "tracking/b/live-" + i + ".jpg")
                .collect(Collectors.toSet());
        listed.addAll(live);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(live);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getEligible()).isEqualTo(PhotoReconciliationService.MAX_DELETES_PER_RUN);
        verify(storage, times(PhotoReconciliationService.MAX_DELETES_PER_RUN)).deleteFile(any());
    }

    // ---------------------------------------------------------------- garde de volume

    @Test
    void volumeGuard_tooManyOrphans_deletesNothingForPrefix_failsPlusOne_othersContinue() {
        service = new PhotoReconciliationService(storage, List.of(trackingSource, bidsSource), settings, runs,
                runner, clock, List.of("tracking/", "bids/"));
        when(settings.photoPurgeEnabled()).thenReturn(true);
        // 101 orphelines sur 150 listées (67 %) : source probablement cassée.
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < 101; i++) listed.add("tracking/b/orphan-" + i + ".jpg");
        Set<String> live = IntStream.range(0, 49).mapToObj(i -> "tracking/b/live-" + i + ".jpg")
                .collect(Collectors.toSet());
        listed.addAll(live);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(live);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);
        when(bidsSource.referencedKeys("bids/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("bids/"), any(), anyInt())).thenReturn(List.of("bids/s/orphan.jpg"));

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        verify(storage, never()).deleteFile(org.mockito.ArgumentMatchers.startsWith("tracking/"));
        verify(storage).deleteFile("bids/s/orphan.jpg");
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(run.getError()).isNull();
    }

    @Test
    void volumeGuard_inDryRun_stillCountsFailure() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        List<String> listed = IntStream.range(0, 101).mapToObj(i -> "tracking/b/orphan-" + i + ".jpg").toList();
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getEligible()).isZero();
        verify(storage, never()).deleteFile(any());
    }

    @Test
    void volumeGuard_hundredOrphansOrFewer_isAllowed_evenIfAllOrphans() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        List<String> listed = IntStream.range(0, 100).mapToObj(i -> "tracking/b/orphan-" + i + ".jpg").toList();
        when(trackingSource.referencedKeys("tracking/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getDeleted()).isEqualTo(100);
        assertThat(run.getFailed()).isZero();
    }

    @Test
    void volumeGuard_halfOrLess_isAllowed_evenAboveHundred() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < 150; i++) listed.add("tracking/b/orphan-" + i + ".jpg");
        Set<String> live = IntStream.range(0, 150).mapToObj(i -> "tracking/b/live-" + i + ".jpg")
                .collect(Collectors.toSet());
        listed.addAll(live);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(live);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getDeleted()).isEqualTo(150);
        assertThat(run.getFailed()).isZero();
    }

    @Test
    void messagingIsNotReconciled() {
        assertThat(PhotoReconciliationService.RECONCILED_PREFIXES).doesNotContain("messaging/");
        assertThat(PhotoReconciliationService.RECONCILED_PREFIXES).containsExactlyInAnyOrder(
                "package_requests/", "bids/", "tracking/", "users/", "reports/", "support/", "requests/");
    }

    @Test
    void prefixCoveredOnlyByFreeValueSources_failsRun_andDeletesNothing() {
        MediaKeyReferenceSource refusal = org.mockito.Mockito.mock(MediaKeyReferenceSource.class);
        MediaKeyReferenceSource legacy = org.mockito.Mockito.mock(MediaKeyReferenceSource.class);
        for (MediaKeyReferenceSource s : List.of(refusal, legacy)) {
            lenient().when(s.dedicated()).thenReturn(false);
            lenient().when(s.prefixes()).thenReturn(Set.of("tracking/", "bids/"));
        }
        service = new PhotoReconciliationService(storage, List.of(trackingSource, refusal, legacy), settings, runs,
                runner, clock, List.of("tracking/", "bids/"));
        lenient().when(settings.photoPurgeEnabled()).thenReturn(true);

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getError()).contains("bids/").doesNotContain("tracking/");
        verify(storage, never()).deleteFile(any());
        verify(storage, never()).listObjectsOlderThan(anyString(), any(), anyInt());
        verify(runner).unlock();
    }

    @Test
    void dryRun_countsAllOrphans_withoutBudget() {
        service = new PhotoReconciliationService(storage, List.of(trackingSource, bidsSource), settings, runs,
                runner, clock, List.of("tracking/", "bids/"));
        when(settings.photoPurgeEnabled()).thenReturn(false);
        // 6000 orphelines pour 6001 vivantes (sous la garde de volume), puis 10 orphelines.
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < 6000; i++) listed.add("tracking/b/orphan-" + i + ".jpg");
        Set<String> live = IntStream.range(0, 6001).mapToObj(i -> "tracking/b/live-" + i + ".jpg")
                .collect(Collectors.toSet());
        listed.addAll(live);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(live);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);
        when(bidsSource.referencedKeys("bids/")).thenReturn(Set.of());
        when(storage.listObjectsOlderThan(eq("bids/"), any(), anyInt()))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> "bids/s/" + i + ".jpg").toList());

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN).orElseThrow();

        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getEligible()).isEqualTo(6010);
        verify(storage).listObjectsOlderThan(eq("bids/"), any(), anyInt());
        verify(storage, never()).deleteFile(any());
    }

    // ---------------------------------------------------------------- levée ponctuelle de la garde

    private void givenGuardTrippedOnTracking() {
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < 101; i++) listed.add("tracking/b/orphan-" + i + ".jpg");
        Set<String> live = IntStream.range(0, 49).mapToObj(i -> "tracking/b/live-" + i + ".jpg")
                .collect(Collectors.toSet());
        listed.addAll(live);
        when(trackingSource.referencedKeys("tracking/")).thenReturn(live);
        when(storage.listObjectsOlderThan(eq("tracking/"), any(), anyInt())).thenReturn(listed);
    }

    @Test
    void allowBulk_false_guardStillBlocks() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        givenGuardTrippedOnTracking();

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, false).orElseThrow();

        verify(storage, never()).deleteFile(anyString());
        assertThat(run.getFailed()).isEqualTo(1);
    }

    @Test
    void allowBulk_true_switchOn_deletesDespiteGuard() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        givenGuardTrippedOnTracking();

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, true).orElseThrow();

        verify(storage, times(101)).deleteFile(org.mockito.ArgumentMatchers.startsWith("tracking/b/orphan-"));
        assertThat(run.getDeleted()).isEqualTo(101);
        assertThat(run.getFailed()).isZero();
    }

    @Test
    void allowBulk_true_switchOff_stillDeletesNothing() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        givenGuardTrippedOnTracking();

        PhotoPurgeRunEntity run = service.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, true).orElseThrow();

        verify(storage, never()).deleteFile(anyString());
        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getEligible()).isEqualTo(101);
    }
}
