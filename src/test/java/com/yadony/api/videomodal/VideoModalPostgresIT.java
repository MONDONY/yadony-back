package com.yadony.api.videomodal;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V366 et V369 (type INFO) sur Postgres embarqué, toutes migrations Flyway et ddl-auto=validate : les entités collent
 * au schéma, les colonnes JSON (TEXT) se relisent, la contrainte d'unicité campagne/utilisateur/version
 * et les CHECK tiennent, la requête de statistiques passe.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Modals vidéo : schéma V366 + V369 (Postgres)")
class VideoModalPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    private UUID persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-video-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        return userRepository.save(u).getId();
    }

    private VideoModalCampaignEntity persistCampaign() {
        VideoModalCampaignEntity c = new VideoModalCampaignEntity();
        c.setName("Vidéo d'accueil");
        c.setYoutubeUrl("https://youtu.be/dQw4w9WgXcQ");
        c.setYoutubeVideoId("dQw4w9WgXcQ");
        c.setTitleFr("Bienvenue");
        c.setHighlights(List.of(new VideoModalHighlight(VideoModalIcon.QR, "Suivi par QR", "QR tracking")));
        c.setTriggers(List.of(VideoModalTrigger.APP_OPEN, VideoModalTrigger.PUBLISH_TRIP));
        c.setActive(true);
        c.setVersionPublishedAt(Instant.now());
        return campaignRepository.save(c);
    }

    @Test
    @DisplayName("JSON relu, unicité par version, statistiques de la version courante, suppression douce")
    void schema() {
        VideoModalCampaignEntity c = persistCampaign();
        UUID user = persistUser();
        UUID other = persistUser();

        VideoModalCampaignEntity reread = campaignRepository.findById(c.getId()).orElseThrow();
        assertThat(reread.getHighlights()).containsExactly(new VideoModalHighlight(VideoModalIcon.QR, "Suivi par QR", "QR tracking"));
        assertThat(reread.getTriggers()).containsExactly(VideoModalTrigger.APP_OPEN, VideoModalTrigger.PUBLISH_TRIP);
        assertThat(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).extracting(VideoModalCampaignEntity::getId)
                .contains(c.getId());

        VideoModalResponseEntity r1 = new VideoModalResponseEntity(c.getId(), user, 1);
        r1.apply(VideoModalEvent.SHOWN, Instant.now());
        r1.apply(VideoModalEvent.COMPLETED, Instant.now());
        responseRepository.saveAndFlush(r1);
        VideoModalResponseEntity r2 = new VideoModalResponseEntity(c.getId(), other, 1);
        r2.apply(VideoModalEvent.DISMISSED, Instant.now());
        responseRepository.saveAndFlush(r2);
        // Ligne d'une autre version : pas comptée.
        responseRepository.saveAndFlush(new VideoModalResponseEntity(c.getId(), other, 2));

        assertThatThrownBy(() -> responseRepository.saveAndFlush(new VideoModalResponseEntity(c.getId(), user, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        List<Object[]> stats = responseRepository.currentVersionStats(List.of(c.getId()));
        assertThat(stats).hasSize(1);
        Object[] row = stats.get(0);
        assertThat(row[0]).isEqualTo(c.getId());
        assertThat(((Number) row[1]).longValue()).isEqualTo(2);
        assertThat(((Number) row[2]).longValue()).isEqualTo(1);
        assertThat(((Number) row[3]).longValue()).isEqualTo(1);
        assertThat(((Number) row[4]).longValue()).isEqualTo(1);

        // Instants relus à l'identique (hiver comme été), quel que soit le fuseau de la JVM.
        Instant winter = Instant.parse("2026-01-10T10:00:00Z");
        Instant summer = Instant.parse("2026-07-10T10:00:00Z");
        VideoModalResponseEntity r3 = new VideoModalResponseEntity(c.getId(), user, 5);
        r3.apply(VideoModalEvent.SHOWN, winter);
        r3.apply(VideoModalEvent.OPENED, summer);
        responseRepository.saveAndFlush(r3);
        VideoModalResponseEntity reread3 = responseRepository.findByCampaignIdAndUserIdAndVersion(c.getId(), user, 5).orElseThrow();
        assertThat(reread3.getLastShownAt()).isEqualTo(winter);
        assertThat(reread3.getOpenedAt()).isEqualTo(summer);

        assertThat(responseRepository.softDeleteByUserId(user, LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(2);
        assertThat(responseRepository.findByUserIdAndCampaignIdIn(user, List.of(c.getId()))).isEmpty();
    }

    @Test
    @DisplayName("CHECK : identifiant YouTube, plafond d'affichages, période, public")
    void checks() {
        VideoModalCampaignEntity c = persistCampaign();
        String id = c.getId().toString();
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET youtube_video_id = 'bad id!' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET max_displays = 11 WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE video_modal_campaigns SET starts_at = now(), ends_at = now() - interval '1 day' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET audience = 'VIP' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V369 : une campagne INFO (sans vidéo) se relit avec ses actions JSON ; une VIDEO garde ses colonnes obligatoires")
    void infoType() {
        VideoModalCampaignEntity info = new VideoModalCampaignEntity();
        info.setType(VideoModalType.INFO);
        info.setName("Info");
        info.setTitleFr("Titre");
        info.setBodyFr("Texte");
        info.setBodyEn("Text");
        info.setIcon(VideoModalIcon.BELL);
        info.setPrimaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.MY_MONEY, null));
        info.setSecondaryAction(new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com/aide"));
        info.setTriggers(List.of(VideoModalTrigger.APP_OPEN));
        info.setActive(true);
        info.setVersionPublishedAt(Instant.now());
        VideoModalCampaignEntity saved = campaignRepository.saveAndFlush(info);

        VideoModalCampaignEntity reread = campaignRepository.findById(saved.getId()).orElseThrow();
        assertThat(reread.getType()).isEqualTo(VideoModalType.INFO);
        assertThat(reread.getYoutubeVideoId()).isNull();
        assertThat(reread.getYoutubeUrl()).isNull();
        assertThat(reread.getIcon()).isEqualTo(VideoModalIcon.BELL);
        assertThat(reread.getPrimaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.MY_MONEY, null));
        assertThat(reread.getSecondaryAction().url()).isEqualTo("https://yadony.com/aide");
        assertThat(jdbc.queryForObject("SELECT primary_action FROM video_modal_campaigns WHERE id = ?::uuid",
                String.class, saved.getId().toString())).contains("\"kind\":\"SCREEN\"").contains("\"screen\":\"MY_MONEY\"");

        String id = saved.getId().toString();
        // INFO : texte et bouton principal obligatoires.
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET body_fr = NULL WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET primary_action = NULL WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET type = 'AUTRE' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // VIDEO : lien et identifiant obligatoires.
        VideoModalCampaignEntity video = persistCampaign();
        assertThat(campaignRepository.findById(video.getId()).orElseThrow().getType()).isEqualTo(VideoModalType.VIDEO);
        String vid = video.getId().toString();
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET youtube_video_id = NULL WHERE id = ?::uuid", vid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_modal_campaigns SET youtube_url = NULL WHERE id = ?::uuid", vid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V369 : une ligne insérée sans les nouvelles colonnes (campagne vidéo existante) devient une VIDEO inchangée")
    void existingRowsStayVideo() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, version_published_at) "
                + "VALUES (?::uuid, 'Ancienne', 'https://youtu.be/XLgkHom38_Y', 'XLgkHom38_Y', 'Vidéo d''accueil', now())", id);
        VideoModalCampaignEntity c = campaignRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(c.getType()).isEqualTo(VideoModalType.VIDEO);
        assertThat(c.getYoutubeVideoId()).isEqualTo("XLgkHom38_Y");
        assertThat(c.getBodyFr()).isNull();
        assertThat(c.getPrimaryAction()).isNull();
        assertThat(c.getSecondaryAction()).isNull();
        assertThat(c.getIcon()).isNull();
    }
}
