package com.yadony.api.videomodal;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class VideoModalFixtures {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private VideoModalFixtures() {
    }

    static VideoModalCampaignEntity campaign(int version, VideoModalAudience audience) {
        VideoModalCampaignEntity c = new VideoModalCampaignEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setName("Vidéo d'accueil");
        c.setYoutubeUrl("https://youtu.be/dQw4w9WgXcQ");
        c.setYoutubeVideoId("dQw4w9WgXcQ");
        c.setDurationSeconds(204);
        c.setTitleFr("Bienvenue sur Yadony");
        c.setTitleEn("Welcome to Yadony");
        c.setSubtitleFr("Sous-titre");
        c.setHighlights(List.of(new VideoModalHighlight(VideoModalIcon.PARCEL, "Envoyer un colis", null)));
        c.setTriggers(List.of(VideoModalTrigger.SEARCH_TAB));
        c.setMaxDisplays(3);
        c.setActive(true);
        c.setVersion(version);
        c.setAudience(audience);
        c.setVersionPublishedAt(NOW.minusSeconds(3600));
        return c;
    }

    /** Campagne INFO : texte, icône, bouton principal SCREEN et bouton secondaire CLOSE. */
    static VideoModalCampaignEntity infoCampaign(int version, VideoModalAudience audience) {
        VideoModalCampaignEntity c = campaign(version, audience);
        c.setType(VideoModalType.INFO);
        c.setYoutubeUrl(null);
        c.setYoutubeVideoId(null);
        c.setDurationSeconds(null);
        c.setHighlights(List.of());
        c.setName("Info colis");
        c.setTitleFr("Votre colis arrive");
        c.setTitleEn("Your parcel is coming");
        c.setSubtitleFr("Bonne nouvelle");
        c.setBodyFr("Suivez-le en direct.");
        c.setBodyEn("Follow it live.");
        c.setIcon(VideoModalIcon.BELL);
        c.setPrimaryLabelFr("Suivre");
        c.setPrimaryLabelEn("Track");
        c.setPrimaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.TRACKING, null));
        c.setSecondaryAction(new VideoModalAction(VideoModalActionKind.CLOSE, null, null));
        return c;
    }

    /**
     * Campagne INFO d'étape du colis : déclencheurs d'étape, une fois par colis, rôles expéditeur et
     * destinataire, bouton principal « QR du colis » et bouton secondaire « suivi ».
     */
    static VideoModalCampaignEntity parcelCampaign(VideoModalTrigger... steps) {
        VideoModalCampaignEntity c = infoCampaign(1, VideoModalAudience.EVERYONE);
        c.setName("Étape colis");
        c.setTitleFr("Colis arrivé à {ville_arrivee}");
        c.setTitleEn("Parcel arrived in {ville_arrivee}");
        c.setBodyFr("{voyageur} a déposé le colis de {expéditeur} pour {destinataire}.");
        c.setBodyEn("{voyageur} dropped off {expéditeur}'s parcel for {destinataire}.");
        c.setTriggers(List.of(steps));
        c.setFrequency(VideoModalFrequency.ONCE_PER_PARCEL);
        c.setParcelRoles(java.util.Set.of(VideoModalParcelRole.RECIPIENT, VideoModalParcelRole.SENDER));
        c.setPrimaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_QR, null));
        c.setSecondaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_TRACKING, null));
        return c;
    }

    static VideoModalResponseEntity response(VideoModalCampaignEntity c, int version, int displays, VideoModalEvent status) {
        VideoModalResponseEntity r = new VideoModalResponseEntity(c.getId(), UUID.randomUUID(), version);
        for (int i = 0; i < displays; i++) r.apply(VideoModalEvent.SHOWN, NOW);
        if (status != VideoModalEvent.SHOWN) r.apply(status, NOW);
        return r;
    }
}
