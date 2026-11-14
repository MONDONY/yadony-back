package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fréquence EVERY_TIME : éligibilité, repli pour les apps sans caps=everytime, validation admin. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : fréquence EVERY_TIME")
class VideoModalEveryTimeTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;
    @Mock VideoModalImageUrls imageUrls;
    @Mock VideoModalTargeting targeting;
    @Mock AuditService auditService;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private VideoModalService service;
    private VideoModalAdminService admin;

    @BeforeEach
    void setUp() {
        service = new VideoModalService(campaignRepository, responseRepository, userRepository, tx, imageUrls,
                targeting, 1, Clock.fixed(NOW, ZoneOffset.UTC));
        admin = new VideoModalAdminService(campaignRepository, responseRepository, auditService,
                mock(VideoModalImageUrls.class), targeting, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static VideoModalCampaignEntity everyTime(VideoModalTrigger... triggers) {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of(triggers));
        c.setFrequency(VideoModalFrequency.EVERY_TIME);
        return c;
    }

    private void knownUser(List<VideoModalResponseEntity> responses) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        when(responseRepository.findByUserIdAndCampaignIdIn(eq(userId), anyCollection())).thenReturn(responses);
    }

    // ── Enum et entité ─────────────────────────────────────────────────────

    @Test
    @DisplayName("EVERY_TIME exige caps=targeting et n'a aucun plafond d'affichages")
    void enumEtEntite() {
        assertThat(VideoModalFrequency.EVERY_TIME.needsTargetingCapability()).isTrue();
        var c = everyTime(VideoModalTrigger.DELIVERY_CONFIRMED);
        assertThat(c.needsTargetingCapability()).isTrue();
        assertThat(c.displayCap()).isNull();
        assertThat(VideoModalFrequency.valueOf("EVERY_TIME")).isEqualTo(VideoModalFrequency.EVERY_TIME);
    }

    @Test
    @DisplayName("éligibilité : aucun plafond, même après de nombreux affichages ; « ne plus afficher » reste respecté")
    void eligibilite() {
        var c = everyTime(VideoModalTrigger.DELIVERY_CONFIRMED);
        c.setStopAfterAnswer(false);

        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 50, VideoModalEvent.SHOWN)), NOW))
                .isEqualTo(50);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(), NOW)).isEqualTo(0);

        var dismissed = response(c, 1, 2, VideoModalEvent.SHOWN);
        dismissed.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(dismissed), NOW)).isNull();
    }

    // ── caps=everytime ─────────────────────────────────────────────────────

    @Test
    @DisplayName("supportsEveryTime : jeton seul dans une liste, casse et espaces ignorés")
    void supportsEveryTime() {
        assertThat(VideoModalService.supportsEveryTime("layout,targeting,moments,everytime")).isTrue();
        assertThat(VideoModalService.supportsEveryTime(" EveryTime ")).isTrue();
        assertThat(VideoModalService.supportsEveryTime("layout,targeting,moments")).isFalse();
        assertThat(VideoModalService.supportsEveryTime("every")).isFalse();
        assertThat(VideoModalService.supportsEveryTime(null)).isFalse();
    }

    @Test
    @DisplayName("visiteur : servie EVERY_TIME avec le cap, EVERY_OPEN sans ; jamais envoyée sans targeting")
    void visiteur() {
        var c = everyTime(VideoModalTrigger.PAYMENT_SUCCEEDED);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c));

        assertThat(service.eligible(null, "fr", "layout,targeting,moments,everytime")).singleElement()
                .extracting(VideoModalEligibleResponse::frequency).isEqualTo(VideoModalFrequency.EVERY_TIME);
        assertThat(service.eligible(null, "fr", "layout,targeting,moments")).singleElement()
                .extracting(VideoModalEligibleResponse::frequency).isEqualTo(VideoModalFrequency.EVERY_OPEN);
        assertThat(service.eligible(null, "fr", "layout,moments,everytime")).isEmpty();
        assertThat(service.eligible(null, "fr", null)).isEmpty();
    }

    @Test
    @DisplayName("compte : le repli EVERY_OPEN garde priorité, jours, réponses et dernier affichage")
    void compte() {
        var c = everyTime(VideoModalTrigger.PAYMENT_SUCCEEDED);
        c.setPriority(7);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c));
        knownUser(List.of(response(c, 1, 9, VideoModalEvent.SHOWN)));
        when(responseRepository.countShownSince(eq(userId), any())).thenReturn(0L);

        VideoModalEligibleResponse legacy = service.eligible("uid", "fr", "layout,targeting,moments").get(0);
        assertThat(legacy.frequency()).isEqualTo(VideoModalFrequency.EVERY_OPEN);
        assertThat(legacy.priority()).isEqualTo(7);
        assertThat(legacy.displays()).isEqualTo(9);
        assertThat(legacy.lastShownAt()).isNotNull();

        assertThat(service.eligible("uid", "fr", "layout,targeting,moments,everytime").get(0).frequency())
                .isEqualTo(VideoModalFrequency.EVERY_TIME);
    }

    @Test
    @DisplayName("plafond journalier atteint : seules les campagnes EVERY_TIME restent servies")
    void plafondJournalier() {
        var every = everyTime(VideoModalTrigger.DELIVERY_CONFIRMED);
        var other = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(other, every));
        knownUser(List.of());
        when(responseRepository.countShownSince(eq(userId), any())).thenReturn(1L);

        assertThat(service.eligible("uid", "fr", "layout,targeting,moments,everytime"))
                .extracting(VideoModalEligibleResponse::id).containsExactly(every.getId());
    }

    @Test
    @DisplayName("plafond journalier atteint sans campagne EVERY_TIME : rien n'est servi")
    void plafondJournalierSansEveryTime() {
        var other = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(other));
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        when(responseRepository.countShownSince(eq(userId), any())).thenReturn(1L);

        assertThat(service.eligible("uid", "fr", "layout,targeting,moments,everytime")).isEmpty();
    }

    // ── Validation admin ───────────────────────────────────────────────────

    private static VideoModalRequest request(VideoModalFrequency frequency, Integer days, List<VideoModalTrigger> triggers,
                                             java.time.Instant endsAt) {
        return new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), triggers, null, null, endsAt,
                null, null, null, null, null, null,
                frequency, days, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private void assertRejected(VideoModalRequest request, String code) {
        assertThatThrownBy(() -> admin.create(request, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("tous les déclencheurs sont des moments d'action, sans date de fin : acceptée, frequencyDays nul")
    void accepteeSansDateDeFin() {
        savesItself();

        VideoModalAdminResponse r = admin.create(request(VideoModalFrequency.EVERY_TIME, null,
                List.of(VideoModalTrigger.DELIVERY_CONFIRMED, VideoModalTrigger.APP_UPDATED), null), adminId);

        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.EVERY_TIME);
        assertThat(r.frequencyDays()).isNull();
        assertThat(r.endsAt()).isNull();
    }

    @Test
    @DisplayName("un déclencheur « où » (écran ou bouton), même mêlé à des moments : 422")
    void refuseDeclencheurNonMoment() {
        assertRejected(request(VideoModalFrequency.EVERY_TIME, null, List.of(VideoModalTrigger.SEARCH_TAB), null),
                "video-modal-every-time-moments-only");
        assertRejected(request(VideoModalFrequency.EVERY_TIME, null,
                List.of(VideoModalTrigger.APP_OPEN, VideoModalTrigger.KYC_VERIFIED), null),
                "video-modal-every-time-moments-only");
    }

    @Test
    @DisplayName("situations seules (APP_OPEN implicite) : 422")
    void refuseSituationsSeules() {
        var r = new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null, null, null,
                VideoModalFrequency.EVERY_TIME, null, null, null, null, null, null, null, null, null, null,
                List.of(new VideoModalRequest.SituationRequest("PROFILE_INCOMPLETE", null)), null, null, null);

        assertRejected(r, "video-modal-every-time-moments-only");
    }

    @Test
    @DisplayName("frequencyDays renseigné avec EVERY_TIME : 422")
    void refuseJours() {
        assertRejected(request(VideoModalFrequency.EVERY_TIME, 3, List.of(VideoModalTrigger.PAYMENT_FAILED), null),
                "video-modal-every-time-days");
    }

    @Test
    @DisplayName("les autres fréquences ne sont pas concernées par la règle des moments")
    void autresFrequencesInchangees() {
        savesItself();
        assertThat(admin.create(request(VideoModalFrequency.EVERY_OPEN, null, List.of(VideoModalTrigger.SEARCH_TAB), null),
                adminId).frequency()).isEqualTo(VideoModalFrequency.EVERY_OPEN);
    }
}
