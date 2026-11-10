package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.AudienceEstimateRequest;
import com.yadony.api.videomodal.dto.AudienceEstimateResponse;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.SituationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Création et modification d'une campagne avec les réglages de moment, de ciblage et de fréquence. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : réglages de moment, ciblage et fréquence (service admin)")
class VideoModalTargetingAdminServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock AuditService auditService;
    @Mock VideoModalTargeting targeting;

    private VideoModalAdminService service;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoModalAdminService(campaignRepository, responseRepository, auditService,
                mock(VideoModalImageUrls.class), targeting, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Champs ajoutés par l'étape 1, tous facultatifs. */
    record Extras(VideoModalFrequency frequency, Integer frequencyDays, Boolean stopAfterAnswer,
                  Boolean allowDontShowAgain, Integer priority, Boolean connectedOnly, JsonNode conditions,
                  String minAppVersion, String maxAppVersion, List<VideoModalPlatform> platforms,
                  Integer samplePercent, List<SituationRequest> situations) {
        static Extras none() {
            return new Extras(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        Extras with(Function<Extras, Extras> change) {
            return change.apply(this);
        }
    }

    private static VideoModalRequest request(List<VideoModalTrigger> triggers, Extras x) {
        return new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), triggers, null, null, null,
                null, null, null, null, null, null,
                x.frequency, x.frequencyDays, x.stopAfterAnswer, x.allowDontShowAgain, x.priority, x.connectedOnly,
                x.conditions, x.minAppVersion, x.maxAppVersion, x.platforms, x.samplePercent, x.situations);
    }

    private static VideoModalRequest request(Extras x) {
        return request(List.of(VideoModalTrigger.APP_OPEN), x);
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private void assertRejected(VideoModalRequest request, String code) {
        assertThatThrownBy(() -> service.create(request, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(campaignRepository, never()).save(any());
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Défauts ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sans aucun champ nouveau : défauts d'avant (UP_TO_N, arrêt après réponse, aucun ciblage), conditions = null")
    void defauts() {
        savesItself();

        VideoModalAdminResponse r = service.create(request(Extras.none()), adminId);

        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
        assertThat(r.frequencyDays()).isNull();
        assertThat(r.stopAfterAnswer()).isTrue();
        assertThat(r.allowDontShowAgain()).isFalse();
        assertThat(r.priority()).isZero();
        assertThat(r.connectedOnly()).isFalse();
        assertThat(r.conditions()).isNull(); // aucune condition : null
        assertThat(r.minAppVersion()).isNull();
        assertThat(r.maxAppVersion()).isNull();
        assertThat(r.platforms()).isEmpty();
        assertThat(r.samplePercent()).isEqualTo(100);
        assertThat(r.situations()).isEmpty();
    }

    @Test
    @DisplayName("tous les champs : enregistrés tels quels, relus dans la réponse, conditions normalisées par le moteur")
    void tousLesChamps() {
        savesItself();
        JsonNode conditions = json("{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}]}");
        String canonical = "{\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}],\"op\":\"AND\"}";
        when(targeting.normalizeConditions(conditions)).thenReturn(canonical);

        VideoModalAdminResponse r = service.create(request(List.of(), new Extras(
                VideoModalFrequency.EVERY_N_DAYS, 7, false, true, 5, true, conditions, "1.0.6", "2.0.0",
                List.of(VideoModalPlatform.IOS, VideoModalPlatform.IOS, VideoModalPlatform.ANDROID), 40,
                List.of(new SituationRequest("OFFER_UNANSWERED", 48), new SituationRequest("PROFILE_INCOMPLETE", 99)))),
                adminId);

        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.EVERY_N_DAYS);
        assertThat(r.frequencyDays()).isEqualTo(7);
        assertThat(r.stopAfterAnswer()).isFalse();
        assertThat(r.allowDontShowAgain()).isTrue();
        assertThat(r.priority()).isEqualTo(5);
        assertThat(r.connectedOnly()).isTrue();
        assertThat(r.conditions().get("children")).hasSize(1);
        assertThat(r.minAppVersion()).isEqualTo("1.0.6");
        assertThat(r.maxAppVersion()).isEqualTo("2.0.0");
        assertThat(r.platforms()).containsExactly(VideoModalPlatform.IOS, VideoModalPlatform.ANDROID);
        assertThat(r.samplePercent()).isEqualTo(40);
        // La valeur d'une situation sans valeur (PROFILE_INCOMPLETE) est ignorée.
        assertThat(r.situations()).containsExactly(
                new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 48),
                new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null));
        assertThat(r.triggers()).isEmpty();
    }

    @Test
    @DisplayName("modification : les réglages sont remplacés, retour aux défauts quand ils sont absents")
    void modification() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        c.setFrequency(VideoModalFrequency.EVERY_OPEN);
        c.setPriority(9);
        c.setConnectedOnly(true);
        c.setSamplePercent(10);
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null)));
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(any())).thenReturn(List.of());

        var r = service.update(c.getId(), request(Extras.none()), adminId);

        assertThat(r.version()).isEqualTo(2);
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
        assertThat(r.priority()).isZero();
        assertThat(r.connectedOnly()).isFalse();
        assertThat(r.samplePercent()).isEqualTo(100);
        assertThat(r.situations()).isEmpty();
    }

    // ── Validation 422 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("fréquence : frequencyDays obligatoire pour EVERY_N_DAYS, entre 1 et 365 partout")
    void frequencyDays() {
        assertRejected(request(new Extras(VideoModalFrequency.EVERY_N_DAYS, null, null, null, null, null, null,
                null, null, null, null, null)), "video-modal-frequency-days-required");
        for (int bad : new int[] {0, -1, 366, 10_000}) {
            assertRejected(request(new Extras(VideoModalFrequency.EVERY_N_DAYS, bad, null, null, null, null, null,
                    null, null, null, null, null)), "video-modal-invalid-frequency-days");
            assertRejected(request(new Extras(VideoModalFrequency.UP_TO_N, bad, null, null, null, null, null,
                    null, null, null, null, null)), "video-modal-invalid-frequency-days");
        }
    }

    @Test
    @DisplayName("fréquence : bornes 1 et 365 acceptées ; frequencyDays ignoré (null) hors EVERY_N_DAYS")
    void frequencyDaysBornes() {
        savesItself();
        for (int ok : new int[] {1, 365}) {
            var r = service.create(request(new Extras(VideoModalFrequency.EVERY_N_DAYS, ok, null, null, null, null,
                    null, null, null, null, null, null)), adminId);
            assertThat(r.frequencyDays()).isEqualTo(ok);
        }
        var once = service.create(request(new Extras(VideoModalFrequency.ONCE, 30, null, null, null, null, null,
                null, null, null, null, null)), adminId);
        assertThat(once.frequency()).isEqualTo(VideoModalFrequency.ONCE);
        assertThat(once.frequencyDays()).isNull();
    }

    @Test
    @DisplayName("tirage : hors 1..100 refusé, 1 et 100 acceptés")
    void samplePercent() {
        for (int bad : new int[] {0, -5, 101, 1000}) {
            assertRejected(request(Extras.none().with(x -> new Extras(null, null, null, null, null, null, null,
                    null, null, null, bad, null))), "video-modal-invalid-sample");
        }
        savesItself();
        for (int ok : new int[] {1, 100}) {
            assertThat(service.create(request(Extras.none().with(x -> new Extras(null, null, null, null, null, null,
                    null, null, null, null, ok, null))), adminId).samplePercent()).isEqualTo(ok);
        }
    }

    @Test
    @DisplayName("versions : x.y.z seulement, minimale <= maximale")
    void versions() {
        for (String bad : new String[] {"1.0", "1", "abc", "1.0.6+1", "v1.0.6", "1.0.0.0", "1.x.0", "-1.0.0"}) {
            assertRejected(request(new Extras(null, null, null, null, null, null, null, bad, null, null, null, null)),
                    "video-modal-invalid-version");
            assertRejected(request(new Extras(null, null, null, null, null, null, null, null, bad, null, null, null)),
                    "video-modal-invalid-version");
        }
        assertRejected(request(new Extras(null, null, null, null, null, null, null, "1.0.7", "1.0.6", null, null, null)),
                "video-modal-invalid-version-range");
        savesItself();
        var same = service.create(request(new Extras(null, null, null, null, null, null, null, "1.0.6", "1.0.6", null,
                null, null)), adminId);
        assertThat(same.minAppVersion()).isEqualTo("1.0.6");
        var blank = service.create(request(new Extras(null, null, null, null, null, null, null, "  ", "", null, null, null)),
                adminId);
        assertThat(blank.minAppVersion()).isNull();
        assertThat(blank.maxAppVersion()).isNull();
    }

    @Test
    @DisplayName("situations : genre inconnu, valeur hors bornes (1..720 h, 1..365 j), doublon contradictoire = 422")
    void situationsInvalides() {
        assertRejected(request(Extras.none().with(x -> withSituations(new SituationRequest("INCONNUE", 1)))),
                "video-modal-invalid-situation");
        assertRejected(request(Extras.none().with(x -> withSituations(new SituationRequest(null, 1)))),
                "video-modal-invalid-situation");
        assertRejected(request(Extras.none().with(x -> withSituations((SituationRequest) null))),
                "video-modal-invalid-situation");
        for (String hours : new String[] {"OFFER_ACCEPTED_UNPAID", "OFFER_UNANSWERED", "COUNTER_OFFER_PENDING"}) {
            for (int bad : new int[] {0, -1, 721}) {
                assertRejected(request(Extras.none().with(x -> withSituations(new SituationRequest(hours, bad)))),
                        "video-modal-invalid-situation-value");
            }
        }
        for (String days : new String[] {"RETURN_AFTER_ABSENCE", "DAYS_SINCE_INSTALL_NO_ACTION"}) {
            for (int bad : new int[] {0, 366}) {
                assertRejected(request(Extras.none().with(x -> withSituations(new SituationRequest(days, bad)))),
                        "video-modal-invalid-situation-value");
            }
        }
        assertRejected(request(Extras.none().with(x -> withSituations(
                new SituationRequest("OFFER_UNANSWERED", 24), new SituationRequest("OFFER_UNANSWERED", 48)))),
                "video-modal-duplicate-situation");
    }

    private static Extras withSituations(SituationRequest... situations) {
        return new Extras(null, null, null, null, null, null, null, null, null, null, null,
                java.util.Arrays.asList(situations));
    }

    @Test
    @DisplayName("situations : bornes acceptées, valeurs par défaut, doublon identique dédoublonné, valeur ignorée sans unité")
    void situationsValides() {
        savesItself();
        var r = service.create(request(Extras.none().with(x -> withSituations(
                new SituationRequest("OFFER_ACCEPTED_UNPAID", 1),
                new SituationRequest("OFFER_UNANSWERED", 720),
                new SituationRequest("COUNTER_OFFER_PENDING", null),
                new SituationRequest("RETURN_AFTER_ABSENCE", 365),
                new SituationRequest("DAYS_SINCE_INSTALL_NO_ACTION", null),
                new SituationRequest("KYC_ABANDONED", 3),
                new SituationRequest("FIRST_DELIVERY_NO_REFERRAL", 3),
                new SituationRequest("OFFER_ACCEPTED_UNPAID", 1)))), adminId);

        assertThat(r.situations()).containsExactly(
                new VideoModalSituation(VideoModalSituationKind.OFFER_ACCEPTED_UNPAID, 1),
                new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 720),
                new VideoModalSituation(VideoModalSituationKind.COUNTER_OFFER_PENDING, 24),
                new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 365),
                new VideoModalSituation(VideoModalSituationKind.DAYS_SINCE_INSTALL_NO_ACTION, 7),
                new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null),
                new VideoModalSituation(VideoModalSituationKind.FIRST_DELIVERY_NO_REFERRAL, null));
    }

    @Test
    @DisplayName("déclencheurs : vides refusés sauf s'il y a des situations ; nuls traités comme vides")
    void declencheurs() {
        assertRejected(request(List.of(), Extras.none()), "video-modal-triggers-required");
        assertRejected(request((List<VideoModalTrigger>) null, Extras.none()), "video-modal-triggers-required");
        savesItself();
        var r = service.create(request((List<VideoModalTrigger>) null, Extras.none().with(x -> withSituations(
                new SituationRequest("PROFILE_INCOMPLETE", null)))), adminId);
        assertThat(r.triggers()).isEmpty();
        assertThat(r.situations()).hasSize(1);
    }

    @Test
    @DisplayName("conditions invalides : le 422 du moteur de la Diffusion remonte, rien n'est enregistré")
    void conditionsInvalides() {
        JsonNode bad = json("{\"op\":\"AND\",\"children\":[{\"field\":\"nope\",\"operator\":\"IS\",\"value\":true}]}");
        when(targeting.normalizeConditions(bad)).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "broadcast-rule-invalid", "Unprocessable Entity", "Champ d'audience inconnu"));

        assertRejected(request(Extras.none().with(x -> new Extras(null, null, null, null, null, null, bad,
                null, null, null, null, null))), "broadcast-rule-invalid");
    }

    // ── Audit ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("audit : réglages journalisés, règle réduite à son empreinte (jamais ses valeurs)")
    void audit() {
        savesItself();
        JsonNode conditions = json("{\"op\":\"AND\",\"children\":[{\"field\":\"country\",\"operator\":\"IN\",\"value\":[\"SN\"]}]}");
        String canonical = "{\"children\":[{\"field\":\"country\",\"operator\":\"IN\",\"value\":[\"SN\"]}],\"op\":\"AND\"}";
        when(targeting.normalizeConditions(conditions)).thenReturn(canonical);

        var r = service.create(request(new Extras(VideoModalFrequency.EVERY_OPEN, null, null, true, 3, null,
                conditions, "1.0.0", null, List.of(VideoModalPlatform.ANDROID), 60,
                List.of(new SituationRequest("OFFER_UNANSWERED", 12), new SituationRequest("KYC_ABANDONED", null)))), adminId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        Map<String, Object> p = payload.getValue();
        assertThat(p).containsEntry("frequency", "EVERY_OPEN").containsEntry("priority", 3)
                .containsEntry("allowDontShowAgain", true).containsEntry("stopAfterAnswer", true)
                .containsEntry("connectedOnly", false).containsEntry("samplePercent", 60)
                .containsEntry("minAppVersion", "1.0.0").containsEntry("conditionCount", 1)
                .containsEntry("platforms", List.of("ANDROID"))
                .containsEntry("situations", List.of("OFFER_UNANSWERED:12", "KYC_ABANDONED"));
        assertThat((String) p.get("ruleHash")).hasSize(64);
        assertThat(p.toString()).doesNotContain("SN");
    }

    @Test
    @DisplayName("audit sans condition : empreinte nulle, zéro condition")
    void auditSansCondition() {
        savesItself();
        var r = service.create(request(Extras.none()), adminId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("conditionCount", 0).containsEntry("ruleHash", null);
    }

    // ── Statistiques ───────────────────────────────────────────────────────

    @Test
    @DisplayName("statistiques : dontShowAgain lu en sixième colonne ; une ligne ancienne à cinq colonnes vaut 0")
    void statistiques() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(responseRepository.currentVersionStats(any()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), 9L, 4L, 2L, 3L, 5L}));
        assertThat(service.get(c.getId()).stats()).isEqualTo(new VideoModalAdminResponse.Stats(9, 4, 2, 3, 5));

        when(responseRepository.currentVersionStats(any()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), 9L, 4L, 2L, 3L}));
        assertThat(service.get(c.getId()).stats().dontShowAgain()).isZero();

        when(responseRepository.currentVersionStats(any()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), 1L, null, null, null, null}));
        assertThat(service.get(c.getId()).stats()).isEqualTo(new VideoModalAdminResponse.Stats(1, 0, 0, 0, 0));
    }

    // ── Estimation d'audience ──────────────────────────────────────────────

    @Test
    @DisplayName("estimation : valide comme à la création puis délègue au moteur d'audience")
    void estimation() {
        JsonNode conditions = json("{\"op\":\"AND\",\"children\":[]}");
        when(targeting.normalizeConditions(conditions)).thenReturn(null);
        when(targeting.estimate(null, List.of(VideoModalPlatform.IOS), 50))
                .thenReturn(new VideoModalTargeting.Estimate(200, 100));

        AudienceEstimateResponse r = service.estimateAudience(new AudienceEstimateRequest(
                conditions, true, List.of(VideoModalPlatform.IOS, VideoModalPlatform.IOS), 50, "1.0.0", "1.1.0"));

        assertThat(r).isEqualTo(new AudienceEstimateResponse(200, 100));
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("estimation : tirage par défaut 100, listes nulles acceptées")
    void estimationDefauts() {
        when(targeting.normalizeConditions(null)).thenReturn(null);
        when(targeting.estimate(null, List.of(), 100)).thenReturn(new VideoModalTargeting.Estimate(7, 7));
        assertThat(service.estimateAudience(new AudienceEstimateRequest(null, null, null, null, null, null)))
                .isEqualTo(new AudienceEstimateResponse(7, 7));
    }

    @Test
    @DisplayName("estimation : tirage hors 1..100 ou version mal formée = 422")
    void estimationInvalide() {
        assertThatThrownBy(() -> service.estimateAudience(new AudienceEstimateRequest(null, null, null, 0, null, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-sample"));
        assertThatThrownBy(() -> service.estimateAudience(new AudienceEstimateRequest(null, null, null, 101, null, null)))
                .isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service.estimateAudience(new AudienceEstimateRequest(null, null, null, null, "1.0", null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-version"));
        assertThatThrownBy(() -> service.estimateAudience(new AudienceEstimateRequest(null, null, null, null, null, "x")))
                .isInstanceOf(YadonyBusinessException.class);
    }

    // ── Public à la création et priorité ───────────────────────────────────

    private static VideoModalRequest withAudience(VideoModalType type, VideoModalAudience audience) {
        boolean info = type == VideoModalType.INFO;
        return new VideoModalRequest("Campagne", type, info ? null : "https://youtu.be/dQw4w9WgXcQ", null, "Titre", null,
                null, null, null, null, null, null, info ? "Texte" : null, null, null,
                info ? new VideoModalRequest.ActionRequest("CLOSE", null, null) : null, null, List.of(),
                List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, audience);
    }

    @Test
    @DisplayName("public à la création : celui du corps ; sinon EVERYONE pour une information, NEVER_ANSWERED pour une vidéo")
    void audienceALaCreation() {
        savesItself();
        assertThat(service.create(withAudience(VideoModalType.VIDEO, null), adminId).audience())
                .isEqualTo(VideoModalAudience.NEVER_ANSWERED);
        assertThat(service.create(withAudience(VideoModalType.INFO, null), adminId).audience())
                .isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(service.create(withAudience(VideoModalType.INFO, VideoModalAudience.NOT_WATCHED_PREVIOUS), adminId).audience())
                .isEqualTo(VideoModalAudience.NOT_WATCHED_PREVIOUS);
        assertThat(service.create(withAudience(VideoModalType.VIDEO, VideoModalAudience.EVERYONE), adminId).audience())
                .isEqualTo(VideoModalAudience.EVERYONE);
    }

    @Test
    @DisplayName("public à la modification : changé s'il est donné, conservé sinon")
    void audienceALaModification() {
        var c = campaign(2, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(any())).thenReturn(List.of());

        assertThat(service.update(c.getId(), withAudience(VideoModalType.VIDEO, null), adminId).audience())
                .isEqualTo(VideoModalAudience.NEVER_ANSWERED);
        assertThat(service.update(c.getId(), withAudience(VideoModalType.VIDEO, VideoModalAudience.EVERYONE), adminId).audience())
                .isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(service.update(c.getId(), withAudience(VideoModalType.VIDEO, null), adminId).audience())
                .isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(c.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("priorité : -1000 à 1000 acceptée, au-delà 422")
    void priorite() {
        savesItself();
        for (int ok : new int[] {-1000, 0, 1000}) {
            assertThat(service.create(request(new Extras(null, null, null, null, ok, null, null, null, null, null, null,
                    null)), adminId).priority()).isEqualTo(ok);
        }
        org.mockito.Mockito.clearInvocations(campaignRepository);
        for (int bad : new int[] {-1001, 1001, Integer.MAX_VALUE}) {
            assertRejected(request(new Extras(null, null, null, null, bad, null, null, null, null, null, null, null)),
                    "video-modal-invalid-priority");
        }
    }

    @Test
    @DisplayName("« ONCE » avec maxDisplays 1 et frequencyDays nul ; situations à valeur nulle explicite")
    void corpsDeLAdmin() {
        savesItself();
        var once = service.create(new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", null, "Titre",
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(), List.of(),
                1, null, null, null, null, null, null, null, null, VideoModalFrequency.ONCE, null, null, null, null, null,
                null, null, null, null, null,
                List.of(new SituationRequest("PROFILE_INCOMPLETE", null), new SituationRequest("OFFER_UNANSWERED", null)),
                null), adminId);
        assertThat(once.frequency()).isEqualTo(VideoModalFrequency.ONCE);
        assertThat(once.maxDisplays()).isEqualTo(1);
        assertThat(once.frequencyDays()).isNull();
        assertThat(once.triggers()).isEmpty();
        assertThat(once.situations()).containsExactly(
                new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null),
                new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 24));
    }
}
