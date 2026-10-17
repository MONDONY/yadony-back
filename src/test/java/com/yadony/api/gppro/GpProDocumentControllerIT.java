package com.yadony.api.gppro;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GpProDocumentControllerIT {

    private static final String UID = "uid-gppro-docs";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired GpProDocumentRepository documentRepository;
    @Autowired GpProDocumentService documentService;

    @Autowired GpProAccountFinalizedListener finalizedListener;
    @Autowired GpProDocumentReferenceSource referenceSource;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockBean StorageService storageService;
    @org.springframework.boot.test.mock.mockito.SpyBean UserRepository spyUsers;

    private UUID userId;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        userRepository.deleteAll();
        when(storageService.storeImage(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1, StorageService.MediaUpload.class).prefix() + "k.jpg");
        UserEntity user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setUsername("gppro-docs");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.TRAVELER));
        userId = userRepository.save(user).getId();
        // H2 ne connaît pas « FOR NO KEY UPDATE » : on neutralise le SQL du verrou (l'appel reste vérifié).
        doReturn(java.util.Optional.empty()).when(spyUsers).findByIdForUpdate(any());
    }

    private org.springframework.test.web.servlet.ResultActions post(boolean auth) throws Exception {
        var req = multipart("/gp-pro/documents")
                .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .param("type", "TRAVEL_PROOF");
        if (auth) {
            req = req.with(authentication(new UsernamePasswordAuthenticationToken(
                    UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")))));
        }
        return mockMvc.perform(req);
    }

    @Test
    void uploadImageReturns201WithDocumentId() throws Exception {
        post(true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").exists())
                .andExpect(jsonPath("$.type").value("TRAVEL_PROOF"))
                .andExpect(jsonPath("$.url").doesNotExist());
        assertThat(documentRepository.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getOwnerId()).isEqualTo(userId);
            assertThat(d.getStorageKey()).startsWith("gp_pro/" + userId + "/");
            assertThat(d.getApplicationId()).isNull();
        });
    }

    @Test
    void uploadPdfReturns422InvalidFileType() throws Exception {
        doThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE_TYPE", "Invalid File Type", "Type refusé"))
                .when(storageService).storeImage(any(), any());
        post(true).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"));
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        post(false).andExpect(status().isUnauthorized());
    }

    @Test
    void fifthUnattachedDocumentReturns422() throws Exception {
        for (int i = 0; i < 4; i++) {
            post(true).andExpect(status().isCreated());
        }
        post(true).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("gp-pro-too-many-documents"));
        assertThat(documentRepository.count()).isEqualTo(4);
    }

    @Test
    void purgeClearsKeyAndKeepsItWhenStorageFails() {
        GpProDocumentEntity ok = save("gp_pro/ok.jpg");
        assertThat(documentService.purge(ok)).isTrue();
        verify(storageService).deleteFile("gp_pro/ok.jpg");
        assertThat(ok.getStorageKey()).isNull();
        assertThat(ok.getPurgedAt()).isNotNull();

        GpProDocumentEntity ko = save("gp_pro/ko.jpg");
        doThrow(new RuntimeException("r2 down")).when(storageService).deleteFile("gp_pro/ko.jpg");
        assertThat(documentService.purge(ko)).isFalse();
        assertThat(ko.getStorageKey()).isEqualTo("gp_pro/ko.jpg");
        assertThat(ko.getPurgedAt()).isNull();
    }

    @Test
    void purgeAllCountsPurgedDocuments() {
        UUID appId = UUID.randomUUID();
        GpProDocumentEntity a = save("gp_pro/a.jpg");
        GpProDocumentEntity b = save("gp_pro/b.jpg");
        a.setApplicationId(appId);
        b.setApplicationId(appId);
        documentRepository.saveAll(List.of(a, b));
        doThrow(new RuntimeException("r2 down")).when(storageService).deleteFile("gp_pro/b.jpg");
        assertThat(documentService.purgeAll(appId)).isEqualTo(1);
    }

    @Test
    void uploadTakesOwnerRowLockBeforeCounting() throws Exception {
        post(true).andExpect(status().isCreated());
        verify(spyUsers).findByIdForUpdate(userId);
    }

    @Test
    void purgedAndAttachedDocumentsDoNotCountInLimit() throws Exception {
        GpProDocumentEntity purged = save("gp_pro/p.jpg");
        purged.setStorageKey(null);
        purged.setPurgedAt(java.time.LocalDateTime.now());
        GpProDocumentEntity attached = save("gp_pro/at.jpg");
        attached.setApplicationId(UUID.randomUUID());
        documentRepository.saveAll(List.of(purged, attached));
        for (int i = 0; i < 4; i++) {
            post(true).andExpect(status().isCreated());
        }
        post(true).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void accountFinalizationPurgesAllUnpurgedDocuments() {
        GpProDocumentEntity a = save("gp_pro/a.jpg");
        GpProDocumentEntity b = save("gp_pro/b.jpg");
        finalizedListener.onUserFinalized(new com.yadony.api.auth.events.UserFinalizedEvent(
                userId, com.yadony.api.auth.FinalizationReason.values()[0]));
        // listener @Async : on attend la fin du traitement
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(documentRepository.findByOwnerIdAndPurgedAtIsNull(userId)).isEmpty());
        verify(storageService).deleteFile("gp_pro/a.jpg");
        verify(storageService).deleteFile("gp_pro/b.jpg");
    }

    @Test
    void referenceSourceReturnsUnattachedAndSoftDeletedKeys() {
        save("gp_pro/live.jpg");
        GpProDocumentEntity gone = save("gp_pro/gone.jpg");
        jdbc.update("UPDATE gp_pro_documents SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", gone.getId());
        assertThat(referenceSource.referencedKeys("gp_pro/"))
                .containsExactlyInAnyOrder("gp_pro/live.jpg", "gp_pro/gone.jpg");
    }

    private GpProDocumentEntity save(String key) {
        GpProDocumentEntity d = new GpProDocumentEntity();
        d.setOwnerId(userId);
        d.setDocType(GpProDocumentType.OTHER);
        d.setStorageKey(key);
        return documentRepository.save(d);
    }
}
