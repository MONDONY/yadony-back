package com.yadony.api.gppro;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.yadony.api.common.StorageService;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GpProControllerIT {

    private static final String UID = "uid-gppro-ctrl";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applicationRepository;
    @Autowired GpProDocumentRepository documentRepository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @SpyBean UserRepository spyUsers;
    @MockBean StorageService storageService;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        UserEntity user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setUsername("gppro-ctrl");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setKycStatus(KycStatus.VERIFIED);
        userRepository.save(user);
        // H2 ne connaît pas « FOR NO KEY UPDATE » : on neutralise le SQL du verrou.
        doReturn(Optional.empty()).when(spyUsers).findByIdForUpdate(any());
    }

    private RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(
                UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"))));
    }

    private static final String BODY = "{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\"}";

    @Test
    void applyThenGetMeReturnsPending() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"));
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.travelFrequency").value("MONTHLY"));
    }

    @Test
    void deleteCurrentReturns204ThenGetMeReturnsNone() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/gp-pro/applications/current").with(auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void applyWithMissingFrequencyIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"PROFILE\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Formulaire unifié et sortie en un clic ──

    @Test
    void applyAvecEntreprise_201_etNumeroChiffreRelisibleEnBase() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\",\"commercialName\":\"Acme Express\","
                                + "\"businessRegistrationNumber\":\"SIREN 123 456 789\",\"businessCountry\":\"FR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.businessRegistrationNumber").doesNotExist());
        GpProApplicationEntity saved = applicationRepository.findFirstByUserIdOrderByCreatedAtDesc(me().getId())
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getBusinessRegistrationNumber())
                .isEqualTo("SIREN 123 456 789");
        org.assertj.core.api.Assertions.assertThat(saved.getBusinessCountry()).isEqualTo("FR");
    }

    @Test
    void applyAvecNumeroSansPays_422_gpProBusinessIncomplete() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\",\"commercialName\":\"Acme\","
                                + "\"businessRegistrationNumber\":\"SIREN 123 456 789\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("gp-pro-business-incomplete"));
    }

    @Test
    void applyAvecUnSeulDesDeuxChampsEntreprise_memeVideOuBlanc_422_gpProBusinessIncomplete() throws Exception {
        for (String extra : new String[]{
                "\"businessCountry\":\"FR\",\"businessRegistrationNumber\":\"\"",
                "\"businessCountry\":\"FR\",\"businessRegistrationNumber\":\"   \"",
                "\"businessCountry\":\"FR\"",
                "\"businessRegistrationNumber\":\"SIREN 123 456\",\"businessCountry\":\"\""}) {
            mockMvc.perform(post("/gp-pro/applications").with(auth()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\",\"commercialName\":\"Acme\","
                                    + extra + "}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("gp-pro-business-incomplete"));
        }
    }

    @Test
    void applyAvecNumeroInvalideOuPaysEnMinuscules_422() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\",\"commercialName\":\"Acme\","
                                + "\"businessRegistrationNumber\":\"AB1\",\"businessCountry\":\"FR\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/gp-pro/applications").with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\",\"commercialName\":\"Acme\","
                                + "\"businessRegistrationNumber\":\"SIREN 123\",\"businessCountry\":\"fr\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void resignAvecBadgeProfessionnel_200_revoked_puisDeuxiemeClic409() throws Exception {
        UserEntity u = me();
        u.setGpPro(true);
        u.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL);
        u.setBusinessName("Acme Express");
        u.setBusinessRegistrationNumber("SIREN 123 456 789");
        u.setBusinessCountry("FR");
        userRepository.save(u);

        mockMvc.perform(post("/gp-pro/resign").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVOKED"))
                .andExpect(jsonPath("$.reason").value("SELF"));
        UserEntity after = me();
        org.assertj.core.api.Assertions.assertThat(after.isGpPro()).isFalse();
        org.assertj.core.api.Assertions.assertThat(after.getTravelerStatus())
                .isEqualTo(com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL);
        org.assertj.core.api.Assertions.assertThat(after.getBusinessRegistrationNumber()).isNull();

        mockMvc.perform(post("/gp-pro/resign").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("gp-pro-not-active"));
    }

    @Test
    void resignAvecDemandeEnAttente_200_etDemandeAnnulee() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());

        mockMvc.perform(post("/gp-pro/resign").with(auth())).andExpect(status().isOk());

        mockMvc.perform(get("/gp-pro/me").with(auth())).andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void resignSansRien_409_etSansJeton_401() throws Exception {
        mockMvc.perform(post("/gp-pro/resign").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("gp-pro-not-active"));
        mockMvc.perform(post("/gp-pro/resign")).andExpect(status().isUnauthorized());
    }

    @Test
    void suppressionDuCompte_effaceNumeroEtPaysDesDemandes() {
        GpProApplicationEntity a = new GpProApplicationEntity();
        a.setUserId(me().getId());
        a.setStatus(GpProApplicationStatus.REJECTED);
        a.setSource(GpProApplicationSource.PROFILE);
        a.setTravelFrequency(GpProTravelFrequency.MONTHLY);
        a.setCommercialName("Acme");
        a.setBusinessRegistrationNumber("SIREN 123 456 789");
        a.setBusinessCountry("FR");
        applicationRepository.saveAndFlush(a);

        UUID userId = me().getId();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> applicationRepository.clearPersonalTextByUserId(userId));

        GpProApplicationEntity after = applicationRepository.findById(a.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getCommercialName()).isNull();
        org.assertj.core.api.Assertions.assertThat(after.getBusinessRegistrationNumber()).isNull();
        org.assertj.core.api.Assertions.assertThat(after.getBusinessCountry()).isNull();
    }

    // ── Justificatifs : ajout et retrait tant que la demande est ouverte ──

    private UserEntity me() {
        return userRepository.findByFirebaseUid(UID).orElseThrow();
    }

    private GpProDocumentEntity doc(UUID owner, UUID applicationId) {
        GpProDocumentEntity d = new GpProDocumentEntity();
        d.setOwnerId(owner);
        d.setApplicationId(applicationId);
        d.setDocType(GpProDocumentType.TRAVEL_PROOF);
        d.setStorageKey("gp_pro/" + owner + "/" + UUID.randomUUID() + ".jpg");
        return documentRepository.save(d);
    }

    private UUID otherUserId() {
        UserEntity other = new UserEntity();
        other.setFirebaseUid("uid-gppro-other");
        other.setUsername("gppro-other");
        other.setStatus(UserStatus.ACTIVE);
        other.setRoles(Set.of(Role.TRAVELER));
        return userRepository.save(other).getId();
    }

    private void apply() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    private static String ids(UUID... ids) {
        return "{\"documentIds\":[" + java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    @Test
    void attachDocumentsToOpenApplicationUpdatesCount() throws Exception {
        apply();
        GpProDocumentEntity d = doc(me().getId(), null);
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(ids(d.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].id").value(d.getId().toString()))
                .andExpect(jsonPath("$.documents[0].type").exists())
                .andExpect(jsonPath("$.documents[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.documents[0].url").doesNotExist());
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(jsonPath("$.documents[0].id").value(d.getId().toString()));
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(d.getId()).orElseThrow()
                .getApplicationId()).isNotNull();
    }

    @Test
    void attachWithoutOpenApplicationReturns404() throws Exception {
        GpProDocumentEntity d = doc(me().getId(), null);
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(ids(d.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("gp-pro-application-not-found"));
    }

    @Test
    void attachAnotherUsersDocumentReturns422AndAttachesNothing() throws Exception {
        apply();
        GpProDocumentEntity mine = doc(me().getId(), null);
        GpProDocumentEntity foreign = doc(otherUserId(), null);
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(ids(mine.getId(), foreign.getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("gp-pro-document-invalid"));
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(mine.getId()).orElseThrow()
                .getApplicationId()).isNull();
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(foreign.getId()).orElseThrow()
                .getApplicationId()).isNull();
    }

    @Test
    void attachBeyondFourDocumentsReturns422() throws Exception {
        apply();
        UUID owner = me().getId();
        GpProDocumentEntity[] d = new GpProDocumentEntity[5];
        for (int i = 0; i < 5; i++) {
            d[i] = doc(owner, null);
        }
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ids(d[0].getId(), d[1].getId(), d[2].getId(), d[3].getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(4));
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(ids(d[4].getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("gp-pro-too-many-documents"));
    }

    @Test
    void deleteOwnUnattachedDocumentReturns204AndPurgesIt() throws Exception {
        GpProDocumentEntity d = doc(me().getId(), null);
        mockMvc.perform(delete("/gp-pro/documents/" + d.getId()).with(auth()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(d.getId()).orElseThrow()
                .getPurgedAt()).isNotNull();
        mockMvc.perform(delete("/gp-pro/documents/" + d.getId()).with(auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDocumentOfOpenApplicationDecrementsCount() throws Exception {
        apply();
        GpProDocumentEntity d = doc(me().getId(), null);
        mockMvc.perform(post("/gp-pro/applications/current/documents").with(auth())
                .contentType(MediaType.APPLICATION_JSON).content(ids(d.getId()))).andExpect(status().isOk());
        mockMvc.perform(delete("/gp-pro/documents/" + d.getId()).with(auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.documents.length()").value(0));
    }

    @Test
    void deleteAnotherUsersDocumentReturns404() throws Exception {
        GpProDocumentEntity foreign = doc(otherUserId(), null);
        mockMvc.perform(delete("/gp-pro/documents/" + foreign.getId()).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("gp-pro-document-not-found"));
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(foreign.getId()).orElseThrow()
                .getPurgedAt()).isNull();
    }

    @Test
    void deleteDocumentOfDecidedApplicationReturns404() throws Exception {
        apply();
        GpProApplicationEntity app = applicationRepository.findAll().get(0);
        app.setStatus(GpProApplicationStatus.REJECTED);
        applicationRepository.save(app);
        GpProDocumentEntity d = doc(me().getId(), app.getId());
        mockMvc.perform(delete("/gp-pro/documents/" + d.getId()).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("gp-pro-document-not-found"));
    }
}
