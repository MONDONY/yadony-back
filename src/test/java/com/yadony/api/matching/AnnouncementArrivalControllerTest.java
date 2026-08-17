package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration pour {@code POST /announcements/{id}/mark-arrived} et
 * {@code PATCH /announcements/{id}/arrival-instructions} (Task B10).
 *
 * <p>Contrairement à {@link AnnouncementControllerIntegrationTest} (qui seed de vraies
 * entités via les repositories), ce test mocke {@link AnnouncementService} avec
 * {@code @MockitoBean} — même pattern que {@link BidPhotoControllerIntegrationTest}. Choix
 * délibéré : {@code AnnouncementService.markArrived}/{@code updateArrivalInstructions}
 * passent par {@code AnnouncementRepository.findByIdForUpdate}
 * ({@code @Lock(PESSIMISTIC_WRITE)}), qui compile en {@code SELECT ... FOR NO KEY UPDATE}
 * sous {@code PostgreSQLDialect} (dialect appliqué en profil test — {@code application.yml}
 * force {@code spring.jpa.properties.hibernate.dialect=PostgreSQLDialect}, qui prime sur
 * {@code database-platform: H2Dialect} de {@code application-test.yml}). H2 (2.3.232 et
 * 2.4.240 testés) ne supporte pas cette clause Postgres : toute requête verrouillée en
 * pessimiste échoue en 500 dès qu'un vrai H2 est sollicité — bug d'infra de test
 * pré-existant (aucun autre test du repo n'exerçait ce chemin de bout en bout jusqu'ici,
 * seulement des unit tests avec repository mocké). Corriger ce bug globalement casse
 * {@code CorridorRepository.incrementUsageCount} (mismatch de type sous H2Dialect) — hors
 * scope de B10 (Controller + DTO uniquement). Ce test mocke donc le service pour valider le
 * câblage du controller (routing, désérialisation du DTO, mapping de statut HTTP), sans
 * dépendre de ce chemin de verrouillage.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementArrivalControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AnnouncementService announcementService;

    private static UsernamePasswordAuthenticationToken traveler(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static AnnouncementDetailResponse detailWithInstructions(UUID id, String arrivalInstructions) {
        return new AnnouncementDetailResponse(
                id, UUID.randomUUID(), "Paris", "Dakar", null, null, null,
                null, null, null, null, null, null, null,
                "ACTIVE", 0L, 0L, null, null,
                List.of(), List.of(), List.of(), null, false,
                null, null, null, List.of(), null,
                false, false, null, "EUR", arrivalInstructions
        );
    }

    @Test
    void markArrived_success_returns200() throws Exception {
        UUID announcementId = UUID.randomUUID();
        when(announcementService.markArrived(eq(announcementId), anyString(), eq("Métro Châtelet, sortie 3")))
                .thenReturn(detailWithInstructions(announcementId, "Métro Châtelet, sortie 3"));

        mockMvc.perform(post("/announcements/" + announcementId + "/mark-arrived")
                        .with(authentication(traveler("uid-traveler-mark-arrived")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"Métro Châtelet, sortie 3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalInstructions").value("Métro Châtelet, sortie 3"));
    }

    @Test
    void markArrived_notAllInTransit_returns422() throws Exception {
        UUID announcementId = UUID.randomUUID();
        when(announcementService.markArrived(eq(announcementId), anyString(), isNull()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "trip/not-all-in-transit",
                        "Not All In Transit", "Tous les colis doivent être en transit avant de marquer l'arrivée"));

        mockMvc.perform(post("/announcements/" + announcementId + "/mark-arrived")
                        .with(authentication(traveler("uid-traveler-mark-arrived-422")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateArrivalInstructions_success_returns200() throws Exception {
        UUID announcementId = UUID.randomUUID();
        when(announcementService.updateArrivalInstructions(eq(announcementId), anyString(), eq("Nouveau texte")))
                .thenReturn(detailWithInstructions(announcementId, "Nouveau texte"));

        mockMvc.perform(patch("/announcements/" + announcementId + "/arrival-instructions")
                        .with(authentication(traveler("uid-traveler-update-arrival")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"Nouveau texte\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalInstructions").value("Nouveau texte"));
    }

    @Test
    void markArrived_noBody_passesNullInstructionsToService() throws Exception {
        // Le corps est optionnel côté controller (@RequestBody(required = false)) : sans
        // JSON envoyé, le service doit recevoir null plutôt qu'une NPE / 400.
        UUID announcementId = UUID.randomUUID();
        when(announcementService.markArrived(eq(announcementId), anyString(), isNull()))
                .thenReturn(detailWithInstructions(announcementId, null));

        mockMvc.perform(post("/announcements/" + announcementId + "/mark-arrived")
                        .with(authentication(traveler("uid-traveler-mark-arrived-nobody"))))
                .andExpect(status().isOk());
    }

    /** Régression I6 : le texte était non borné ET aucun {@code @Valid} n'était posé sur
     *  les deux endpoints qui consomment {@link com.yadony.api.matching.dto.ArrivalInstructionsRequest}
     *  — un champ libre exposé pouvait donc écrire n'importe quelle taille en base.
     *  {@code @Size(max = 1000)} + {@code @Valid} → 422 (convention du projet, cf.
     *  {@code GlobalExceptionHandler#handleValidation}) et le service n'est jamais appelé. */
    @Test
    void markArrived_instructionsTooLong_returns422_andServiceNeverCalled() throws Exception {
        UUID announcementId = UUID.randomUUID();
        String tooLong = "x".repeat(1001);

        mockMvc.perform(post("/announcements/" + announcementId + "/mark-arrived")
                        .with(authentication(traveler("uid-traveler-mark-arrived-toolong")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"" + tooLong + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        verify(announcementService, never()).markArrived(any(), anyString(), anyString());
    }

    @Test
    void updateArrivalInstructions_instructionsTooLong_returns422_andServiceNeverCalled() throws Exception {
        UUID announcementId = UUID.randomUUID();
        String tooLong = "x".repeat(1001);

        mockMvc.perform(patch("/announcements/" + announcementId + "/arrival-instructions")
                        .with(authentication(traveler("uid-traveler-update-arrival-toolong")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"" + tooLong + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        verify(announcementService, never()).updateArrivalInstructions(any(), anyString(), anyString());
    }

    /** Borne haute exactement atteinte : 1000 caractères doivent passer. */
    @Test
    void updateArrivalInstructions_exactlyMaxLength_isAccepted() throws Exception {
        UUID announcementId = UUID.randomUUID();
        String atLimit = "x".repeat(1000);
        when(announcementService.updateArrivalInstructions(eq(announcementId), anyString(), eq(atLimit)))
                .thenReturn(detailWithInstructions(announcementId, atLimit));

        mockMvc.perform(patch("/announcements/" + announcementId + "/arrival-instructions")
                        .with(authentication(traveler("uid-traveler-update-arrival-atlimit")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"" + atLimit + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void markArrived_unauthenticated_returns401() throws Exception {
        UUID announcementId = UUID.randomUUID();
        mockMvc.perform(post("/announcements/" + announcementId + "/mark-arrived"))
                .andExpect(status().isUnauthorized());
    }
}
