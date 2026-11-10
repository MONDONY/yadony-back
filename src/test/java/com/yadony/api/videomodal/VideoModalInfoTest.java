package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static com.yadony.api.videomodal.VideoModalFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Modals INFO côté app : textes, éligibilité identique à VIDEO, événements, JSON des actions. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals INFO : éligibilité, textes et événements")
class VideoModalInfoTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;

    private VideoModalService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoModalService(campaignRepository, responseRepository, userRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void knownUser(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("INFO en français : champs INFO, pas de vidéo, libellés et actions")
    void infoFr() {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(infoCampaign(1, VideoModalAudience.NEVER_ANSWERED)));

        VideoModalEligibleResponse r = service.eligible(null, "fr").get(0);

        assertThat(r.type()).isEqualTo(VideoModalType.INFO);
        assertThat(r.title()).isEqualTo("Votre colis arrive");
        assertThat(r.subtitle()).isEqualTo("Bonne nouvelle");
        assertThat(r.body()).isEqualTo("Suivez-le en direct.");
        assertThat(r.icon()).isEqualTo(VideoModalIcon.BELL);
        assertThat(r.primaryLabel()).isEqualTo("Suivre");
        assertThat(r.primaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.TRACKING, null));
        assertThat(r.secondaryLabel()).isEqualTo("OK");
        assertThat(r.secondaryAction().kind()).isEqualTo(VideoModalActionKind.CLOSE);
        assertThat(r.youtubeVideoId()).isNull();
        assertThat(r.durationSeconds()).isNull();
        assertThat(r.highlights()).isEmpty();
        assertThat(r.maxDisplays()).isEqualTo(3);
    }

    @Test
    @DisplayName("INFO en anglais, repli FR quand l'anglais est vide ; icône par défaut INFO ; sans secondaire : null")
    void infoEnEtReplis() {
        var c = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        var sparse = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        sparse.setBodyEn(null);
        sparse.setPrimaryLabelEn(" ");
        sparse.setIcon(null);
        sparse.setSecondaryAction(null);
        sparse.setSecondaryLabelFr("ignoré");
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c, sparse));

        List<VideoModalEligibleResponse> r = service.eligible(null, "en-US");
        assertThat(r.get(0).title()).isEqualTo("Your parcel is coming");
        assertThat(r.get(0).body()).isEqualTo("Follow it live.");
        assertThat(r.get(0).primaryLabel()).isEqualTo("Track");
        assertThat(r.get(1).body()).isEqualTo("Suivez-le en direct.");
        assertThat(r.get(1).primaryLabel()).isEqualTo("Suivre");
        assertThat(r.get(1).icon()).isEqualTo(VideoModalIcon.INFO);
        assertThat(r.get(1).secondaryAction()).isNull();
        assertThat(r.get(1).secondaryLabel()).isNull();
    }

    @Test
    @DisplayName("libellé : CLOSE sans libellé = « OK », SCREEN et URL sans libellé = null")
    void libelleParDefaut() {
        var c = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        c.setPrimaryLabelFr(null);
        c.setPrimaryLabelEn(null);
        c.setPrimaryAction(new VideoModalAction(VideoModalActionKind.CLOSE, null, null));
        var url = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        url.setPrimaryLabelFr(null);
        url.setPrimaryLabelEn(null);
        url.setPrimaryAction(new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com"));
        var sansAction = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        sansAction.setPrimaryLabelFr(null);
        sansAction.setPrimaryLabelEn(null);
        sansAction.setPrimaryAction(null);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c, url, sansAction));

        List<VideoModalEligibleResponse> r = service.eligible(null, "en");
        assertThat(r.get(0).primaryLabel()).isEqualTo("OK");
        assertThat(r.get(1).primaryLabel()).isNull();
        assertThat(r.get(2).primaryLabel()).isNull();
    }

    @Test
    @DisplayName("non-régression VIDEO : type VIDEO, champs INFO null, vidéo inchangée")
    void videoInchange() {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(campaign(1, VideoModalAudience.NEVER_ANSWERED)));
        VideoModalEligibleResponse r = service.eligible(null, "fr").get(0);
        assertThat(r.type()).isEqualTo(VideoModalType.VIDEO);
        assertThat(r.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(r.durationSeconds()).isEqualTo(204);
        assertThat(r.primaryLabel()).isEqualTo("Voir la vidéo (3 min)");
        assertThat(r.secondaryLabel()).isEqualTo("Commencer");
        assertThat(r.body()).isNull();
        assertThat(r.icon()).isNull();
        assertThat(r.primaryAction()).isNull();
        assertThat(r.secondaryAction()).isNull();
        assertThat(r.highlights()).hasSize(1);
    }

    @Test
    @DisplayName("éligibilité identique pour INFO : répondu exclu, plafond, public EVERYONE")
    void eligibiliteIdentique() {
        var answered = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        var capped = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        var shownTwice = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        var everyone = infoCampaign(2, VideoModalAudience.EVERYONE);
        knownUser("uid");
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(answered, capped, shownTwice, everyone));
        when(responseRepository.findByUserIdAndCampaignIdIn(any(), any())).thenReturn(List.of(
                response(answered, 1, 1, VideoModalEvent.OPENED),
                response(capped, 1, 3, VideoModalEvent.SHOWN),
                response(shownTwice, 1, 2, VideoModalEvent.SHOWN),
                response(everyone, 1, 1, VideoModalEvent.OPENED)));

        List<VideoModalEligibleResponse> r = service.eligible("uid", "fr");
        assertThat(r).extracting(VideoModalEligibleResponse::id).containsExactly(shownTwice.getId(), everyone.getId());
        assertThat(r.get(0).displays()).isEqualTo(2);
        assertThat(r.get(1).displays()).isZero();
    }

    @Test
    @DisplayName("COMPLETED sur un INFO : accepté (204) mais jamais enregistré ; OPENED et DISMISSED enregistrés")
    void completedIgnorePourInfo() {
        var c = infoCampaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));

        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.COMPLETED, null));
        verify(responseRepository, never()).saveAndFlush(any());

        knownUser("uid");
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.OPENED, null));
        verify(responseRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("COMPLETED sur un VIDEO : toujours enregistré")
    void completedVideoInchange() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        knownUser("uid");
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.COMPLETED, null));
        verify(responseRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("JSON de l'action : forme du contrat, aller-retour du convertisseur, colonne vide = null")
    void convertisseurAction() throws Exception {
        var converter = new VideoModalJsonConverters.Action();
        String json = converter.convertToDatabaseColumn(
                new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com"));
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("kind").asText()).isEqualTo("URL");
        assertThat(node.get("screen").isNull()).isTrue();
        assertThat(node.get("url").asText()).isEqualTo("https://yadony.com");
        assertThat(converter.convertToEntityAttribute(json))
                .isEqualTo(new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com"));
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute(" ")).isNull();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> converter.convertToEntityAttribute("{pas du json"));
    }
}
