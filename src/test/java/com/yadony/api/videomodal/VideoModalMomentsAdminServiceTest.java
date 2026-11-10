package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.CorridorRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.TriggerOptionsRequest;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Création et modification d'une campagne avec moments d'action, seuils et corridors. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : moments d'action (service admin)")
class VideoModalMomentsAdminServiceTest {

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

    private static VideoModalRequest request(List<VideoModalTrigger> triggers, TriggerOptionsRequest options,
                                             List<CorridorRequest> corridors) {
        return new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), triggers, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, options, corridors);
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private void assertRejected(VideoModalRequest request, String code) {
        clearInvocations(campaignRepository);
        assertThatThrownBy(() -> service.create(request, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(campaignRepository, never()).save(any());
    }

    private static final List<VideoModalTrigger> MOMENT = List.of(VideoModalTrigger.SEARCH_NO_TRIP_RESULTS);

    // ── Déclencheurs et défauts ────────────────────────────────────────────

    @Test
    @DisplayName("tous les nouveaux déclencheurs sont acceptés et relus tels quels")
    void nouveauxDeclencheurs() {
        savesItself();
        List<VideoModalTrigger> all = List.of(VideoModalTrigger.values());

        VideoModalAdminResponse r = service.create(request(all, null, null), adminId);

        assertThat(r.triggers()).containsExactlyElementsOf(all);
    }

    @Test
    @DisplayName("sans seuils ni corridors : défauts 2 et 3, aucun corridor")
    void defauts() {
        savesItself();

        VideoModalAdminResponse r = service.create(request(MOMENT, null, null), adminId);

        assertThat(r.triggerOptions()).isEqualTo(new VideoModalTriggerOptions(2, 3));
        assertThat(r.corridors()).isEmpty();
    }

    @Test
    @DisplayName("seuils partiels : le champ absent reprend son défaut")
    void seuilsPartiels() {
        savesItself();

        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(5, null), null), adminId).triggerOptions())
                .isEqualTo(new VideoModalTriggerOptions(5, 3));
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(null, 8), null), adminId).triggerOptions())
                .isEqualTo(new VideoModalTriggerOptions(2, 8));
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(null, null), null), adminId).triggerOptions())
                .isEqualTo(VideoModalTriggerOptions.DEFAULTS);
    }

    // ── Bornes de triggerOptions ───────────────────────────────────────────

    @Test
    @DisplayName("fewResultsMax : 1 et 20 acceptés ; 0, 21, négatif refusés en 422")
    void bornesFewResultsMax() {
        savesItself();
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(1, null), null), adminId)
                .triggerOptions().fewResultsMax()).isEqualTo(1);
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(20, null), null), adminId)
                .triggerOptions().fewResultsMax()).isEqualTo(20);
        for (int bad : new int[] {0, -3, 21, 1000}) {
            assertRejected(request(MOMENT, new TriggerOptionsRequest(bad, null), null), "video-modal-invalid-few-results-max");
        }
    }

    @Test
    @DisplayName("tripViewedTimes : 2 et 10 acceptés ; 1, 0, 11, négatif refusés en 422")
    void bornesTripViewedTimes() {
        savesItself();
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(null, 2), null), adminId)
                .triggerOptions().tripViewedTimes()).isEqualTo(2);
        assertThat(service.create(request(MOMENT, new TriggerOptionsRequest(null, 10), null), adminId)
                .triggerOptions().tripViewedTimes()).isEqualTo(10);
        for (int bad : new int[] {1, 0, -1, 11, 500}) {
            assertRejected(request(MOMENT, new TriggerOptionsRequest(null, bad), null), "video-modal-invalid-trip-viewed-times");
        }
    }

    @Test
    @DisplayName("les seuils sont contrôlés même si le déclencheur concerné n'est pas coché")
    void seuilsToujoursControles() {
        assertRejected(request(List.of(VideoModalTrigger.SEARCH_TAB), new TriggerOptionsRequest(99, null), null),
                "video-modal-invalid-few-results-max");
    }

    // ── Corridors ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("corridors : texte rogné, côté vide ou blanc devenu nul, doublons fusionnés")
    void corridorsNormalises() {
        savesItself();

        VideoModalAdminResponse r = service.create(request(MOMENT, null, List.of(
                new CorridorRequest("  Paris ", "Abidjan"),
                new CorridorRequest(null, "Sénégal"),
                new CorridorRequest("Lyon", "   "),
                new CorridorRequest("Paris", "Abidjan"))), adminId);

        assertThat(r.corridors()).containsExactly(
                new VideoModalCorridor("Paris", "Abidjan"),
                new VideoModalCorridor(null, "Sénégal"),
                new VideoModalCorridor("Lyon", null));
    }

    @Test
    @DisplayName("corridors : une entrée sans aucun côté (nulle, vide ou blanche) est refusée en 422")
    void corridorSansCote() {
        for (CorridorRequest empty : List.of(new CorridorRequest(null, null), new CorridorRequest(" ", ""),
                new CorridorRequest("", null))) {
            assertRejected(request(MOMENT, null, List.of(new CorridorRequest("Paris", null), empty)),
                    "video-modal-invalid-corridor");
        }
    }

    @Test
    @DisplayName("corridors : 10 entrées au plus ; la onzième est refusée en 422")
    void dixCorridorsAuPlus() {
        savesItself();
        List<CorridorRequest> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(new CorridorRequest("Ville" + i, null));
        assertThat(service.create(request(MOMENT, null, ten), adminId).corridors()).hasSize(10);

        List<CorridorRequest> eleven = new ArrayList<>(ten);
        eleven.add(new CorridorRequest("Ville10", null));
        assertRejected(request(MOMENT, null, eleven), "video-modal-too-many-corridors");
    }

    @Test
    @DisplayName("corridors : 80 caractères au plus par côté ; 81 refusé en 422, aussi côté arrivée")
    void quatreVingtsCaracteres() {
        savesItself();
        String ok = "a".repeat(80);
        String tooLong = "a".repeat(81);
        assertThat(service.create(request(MOMENT, null, List.of(new CorridorRequest(ok, ok))), adminId).corridors())
                .containsExactly(new VideoModalCorridor(ok, ok));
        assertRejected(request(MOMENT, null, List.of(new CorridorRequest(tooLong, null))), "video-modal-corridor-too-long");
        assertRejected(request(MOMENT, null, List.of(new CorridorRequest(null, tooLong))), "video-modal-corridor-too-long");
        // Les espaces autour ne comptent pas.
        assertThat(service.create(request(MOMENT, null, List.of(new CorridorRequest(" " + ok + " ", null))), adminId)
                .corridors()).containsExactly(new VideoModalCorridor(ok, null));
    }

    @Test
    @DisplayName("corridors : une entrée nulle est refusée en 422 ; une liste nulle ou vide veut dire « tous »")
    void entreesNulles() {
        savesItself();
        assertRejected(request(MOMENT, null, Arrays.asList(new CorridorRequest("Paris", null), null)),
                "video-modal-invalid-corridor");
        assertThat(service.create(request(MOMENT, null, null), adminId).corridors()).isEmpty();
        assertThat(service.create(request(MOMENT, null, List.of()), adminId).corridors()).isEmpty();
    }

    @Test
    @DisplayName("corridors sans effet pour les autres déclencheurs : acceptés et conservés")
    void corridorsSansEffet() {
        savesItself();
        VideoModalAdminResponse r = service.create(request(List.of(VideoModalTrigger.SEARCH_TAB), null,
                List.of(new CorridorRequest("Paris", "Dakar"))), adminId);
        assertThat(r.corridors()).containsExactly(new VideoModalCorridor("Paris", "Dakar"));
    }

    // ── Modification, audit ────────────────────────────────────────────────

    @Test
    @DisplayName("modification : seuils et corridors remplacés ; retour aux défauts quand absents")
    void modification() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        c.setTriggerOptions(new VideoModalTriggerOptions(9, 7));
        c.setCorridors(List.of(new VideoModalCorridor("Paris", "Abidjan")));
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(any())).thenReturn(List.of());

        var kept = service.update(c.getId(), request(MOMENT, new TriggerOptionsRequest(4, 6),
                List.of(new CorridorRequest("Lyon", "Dakar"))), adminId);
        assertThat(kept.triggerOptions()).isEqualTo(new VideoModalTriggerOptions(4, 6));
        assertThat(kept.corridors()).containsExactly(new VideoModalCorridor("Lyon", "Dakar"));

        var reset = service.update(c.getId(), request(MOMENT, null, null), adminId);
        assertThat(reset.triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(reset.corridors()).isEmpty();
        assertThat(reset.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("audit : seuils et corridors journalisés, un côté nul écrit « * »")
    @SuppressWarnings("unchecked")
    void audit() {
        savesItself();

        VideoModalAdminResponse r = service.create(request(MOMENT, new TriggerOptionsRequest(6, 4), List.of(
                new CorridorRequest("Paris", "Abidjan"), new CorridorRequest(null, "Dakar"))), adminId);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue().get("triggerOptions")).isEqualTo(Map.of("fewResultsMax", 6, "tripViewedTimes", 4));
        assertThat(payload.getValue().get("corridors")).isEqualTo(List.of("Paris>Abidjan", "*>Dakar"));
        assertThat(payload.getValue().get("triggers")).isEqualTo(List.of("SEARCH_NO_TRIP_RESULTS"));
    }

    @Test
    @DisplayName("l'ancien constructeur du corps (sans moments) donne les défauts")
    void anciensConstructeurs() {
        savesItself();
        var old = new VideoModalRequest("Campagne", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), List.of(VideoModalTrigger.APP_OPEN),
                null, null, null, null, null, null, null, null, null);
        assertThat(old.triggerOptions()).isNull();
        assertThat(old.corridors()).isNull();
        VideoModalAdminResponse r = service.create(old, adminId);
        assertThat(r.triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(r.corridors()).isEmpty();
    }
}
