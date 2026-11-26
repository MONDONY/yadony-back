package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.ActionRequest;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static com.yadony.api.videomodal.VideoModalFixtures.parcelCampaign;
import static com.yadony.api.videomodal.VideoModalTrigger.APP_OPEN;
import static com.yadony.api.videomodal.VideoModalTrigger.DELIVERY_CONFIRMED;
import static com.yadony.api.videomodal.VideoModalTrigger.PARCEL_ACCEPTED;
import static com.yadony.api.videomodal.VideoModalTrigger.PARCEL_ARRIVED;
import static com.yadony.api.videomodal.VideoModalTrigger.PARCEL_DELIVERED;
import static com.yadony.api.videomodal.VideoModalTrigger.PARCEL_HANDED_OVER;
import static com.yadony.api.videomodal.VideoModalTrigger.PARCEL_IN_TRANSIT;
import static com.yadony.api.videomodal.VideoModalTrigger.SEARCH_TAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Modals d'étape du colis (§6.7) : déclencheurs {@code PARCEL_*}, rôles sur le colis, fréquence « une fois
 * par colis », boutons liés au colis, variables des textes et capacité {@code parcel}. Le rôle et la
 * déduplication par colis sont l'affaire de l'app : le back stocke, valide et sert.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals d'étape du colis : données et validation")
class VideoModalParcelStepsTest {

    private static final List<VideoModalTrigger> PARCEL_STEPS =
            List.of(PARCEL_ACCEPTED, PARCEL_HANDED_OVER, PARCEL_IN_TRANSIT, PARCEL_ARRIVED, PARCEL_DELIVERED);

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock AuditService auditService;

    private VideoModalAdminService admin;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        admin = new VideoModalAdminService(campaignRepository, responseRepository, auditService,
                mock(VideoModalImageUrls.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Corps de requête ───────────────────────────────────────────────────

    private static ActionRequest screen(String screen) {
        return new ActionRequest("SCREEN", screen, null);
    }

    private static ActionRequest close() {
        return new ActionRequest("CLOSE", null, null);
    }

    /** Requête INFO complète (contrat avec {@code parcelRoles}). */
    private static VideoModalRequest info(List<VideoModalTrigger> triggers, VideoModalFrequency frequency,
                                          List<String> roles, String titleFr, String titleEn, String bodyFr,
                                          String bodyEn, ActionRequest primary, ActionRequest secondary,
                                          List<VideoModalRequest.SituationRequest> situations, Integer days) {
        return new VideoModalRequest("Étape", VideoModalType.INFO, null, null, titleFr, titleEn, null, null,
                "Ouvrir", null, secondary == null ? null : "Plus tard", null, bodyFr, bodyEn, null,
                primary, secondary, null, triggers, null, null, null,
                null, null, null, null, null, null,
                frequency, days, null, null, null, null, null, null, null, null, null, situations, null, null, null,
                roles);
    }

    private static VideoModalRequest info(List<VideoModalTrigger> triggers, VideoModalFrequency frequency, List<String> roles) {
        return info(triggers, frequency, roles, "Colis arrivé", null, "Le voyageur est arrivé.", null,
                screen("PARCEL_DETAIL"), close(), null, null);
    }

    private static VideoModalRequest texts(String titleFr, String titleEn, String bodyFr, String bodyEn) {
        return info(List.of(PARCEL_ARRIVED), null, null, titleFr, titleEn, bodyFr, bodyEn,
                screen("PARCEL_DETAIL"), null, null, null);
    }

    private static VideoModalRequest withActions(List<VideoModalTrigger> triggers, ActionRequest primary,
                                                 ActionRequest secondary) {
        return info(triggers, null, null, "Titre", null, "Texte", null, primary, secondary, null, null);
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

    // ── Enums : noms de fil et place des nouvelles valeurs ─────────────────

    @Test
    @DisplayName("les cinq étapes du colis sont ajoutées à la fin et exigent les capacités moments et parcel")
    void parcelTriggersNeedMomentsAndParcelCapability() {
        List<VideoModalTrigger> all = List.of(VideoModalTrigger.values());
        // Ajoutées après APP_UPDATED : les ordinaux d'avant (et needsMomentsCapability) ne bougent pas.
        assertThat(all.indexOf(VideoModalTrigger.APP_UPDATED)).isEqualTo(20);
        assertThat(all.subList(21, all.size())).containsExactlyElementsOf(PARCEL_STEPS);
        assertThat(PARCEL_STEPS).extracting(Enum::name).containsExactly(
                "PARCEL_ACCEPTED", "PARCEL_HANDED_OVER", "PARCEL_IN_TRANSIT", "PARCEL_ARRIVED", "PARCEL_DELIVERED");
        for (VideoModalTrigger t : all) {
            assertThat(t.isParcelStep()).as(t.name()).isEqualTo(PARCEL_STEPS.contains(t));
        }
        for (VideoModalTrigger step : PARCEL_STEPS) {
            assertThat(step.needsMomentsCapability()).as(step.name()).isTrue();
            assertThat(step.usesCorridors()).as(step.name()).isFalse();
            var c = campaign(1, VideoModalAudience.EVERYONE);
            c.setTriggers(List.of(SEARCH_TAB, step));
            assertThat(c.needsParcelCapability()).as(step.name()).isTrue();
            // Sans caps=moments, l'étape est retirée des déclencheurs servis.
            assertThat(c.servedTriggers(false)).containsExactly(SEARCH_TAB);
        }
    }

    @Test
    @DisplayName("rôles, fréquence et écrans : noms de fil identiques au back, à l'admin et à l'app")
    void wireNames() {
        assertThat(Arrays.stream(VideoModalParcelRole.values()).map(Enum::name))
                .containsExactly("SENDER", "TRAVELER", "RECIPIENT");

        List<VideoModalFrequency> frequencies = List.of(VideoModalFrequency.values());
        assertThat(frequencies.get(frequencies.size() - 1).name()).isEqualTo("ONCE_PER_PARCEL");
        assertThat(frequencies.subList(0, frequencies.size() - 1)).extracting(Enum::name)
                .containsExactly("ONCE", "UP_TO_N", "EVERY_OPEN", "EVERY_N_DAYS", "EVERY_TIME");
        assertThat(VideoModalFrequency.ONCE_PER_PARCEL.needsTargetingCapability()).isTrue();

        List<InfoScreen> screens = List.of(InfoScreen.values());
        assertThat(screens.subList(screens.size() - 4, screens.size())).extracting(Enum::name)
                .containsExactly("PARCEL_DETAIL", "PARCEL_QR", "PARCEL_SEND_CODE", "PARCEL_TRACKING");
        assertThat(screens.subList(0, screens.size() - 4)).extracting(Enum::name).containsExactly(
                "SEARCH", "ACTIVITIES", "TRACKING", "MESSAGES", "PROFILE", "PUBLISH_TRIP", "SEND_PARCEL",
                "SETTINGS", "MY_MONEY", "REFERRAL", "TRIP_ALERTS", "HELP");
        for (InfoScreen s : screens) {
            assertThat(s.isParcelScreen()).as(s.name()).isEqualTo(s.name().startsWith("PARCEL_"));
        }
    }

    @Test
    @DisplayName("capacité parcel : étape, ONCE_PER_PARCEL, rôles ou bouton PARCEL_* suffisent, chacun seul")
    void needsParcelCapabilityForEachReason() {
        var plain = infoCampaign(1, VideoModalAudience.EVERYONE);
        assertThat(plain.needsParcelCapability()).isFalse();

        var once = infoCampaign(1, VideoModalAudience.EVERYONE);
        once.setFrequency(VideoModalFrequency.ONCE_PER_PARCEL);
        assertThat(once.needsParcelCapability()).isTrue();
        assertThat(once.needsTargetingCapability()).isTrue();
        // Servie comme EVERY_TIME : aucun plafond d'affichages côté back, l'app dédoublonne par colis.
        assertThat(once.displayCap()).isNull();

        var roles = infoCampaign(1, VideoModalAudience.EVERYONE);
        roles.setParcelRoles(Set.of(VideoModalParcelRole.TRAVELER));
        assertThat(roles.needsParcelCapability()).isTrue();

        var primary = infoCampaign(1, VideoModalAudience.EVERYONE);
        primary.setPrimaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_SEND_CODE, null));
        assertThat(primary.needsParcelCapability()).isTrue();

        var secondary = infoCampaign(1, VideoModalAudience.EVERYONE);
        secondary.setSecondaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_DETAIL, null));
        assertThat(secondary.needsParcelCapability()).isTrue();

        // Un écran ordinaire, un lien ou « Fermer » ne demandent rien.
        var ordinary = infoCampaign(1, VideoModalAudience.EVERYONE);
        ordinary.setPrimaryAction(new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com"));
        ordinary.setSecondaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.TRACKING, null));
        assertThat(ordinary.needsParcelCapability()).isFalse();
    }

    // ── Rôles ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rôles sans déclencheur d'étape : 422 ; rôle inconnu ou vide : 422")
    void rolesRejectedWithoutParcelTrigger() {
        assertRejected(info(List.of(APP_OPEN), null, List.of("SENDER")),
                "video-modal-parcel-roles-need-parcel-trigger");
        assertRejected(info(List.of(DELIVERY_CONFIRMED), null, List.of("RECIPIENT")),
                "video-modal-parcel-roles-need-parcel-trigger");
        // Situations seules : servies avec APP_OPEN, ce n'est pas une étape du colis.
        assertRejected(info(List.of(), null, List.of("TRAVELER"), "Titre", null, "Texte", null, close(), null,
                List.of(new VideoModalRequest.SituationRequest("PROFILE_INCOMPLETE", null)), null),
                "video-modal-parcel-roles-need-parcel-trigger");

        assertRejected(info(PARCEL_STEPS, null, List.of("PILOTE")), "video-modal-invalid-parcel-role");
        assertRejected(info(PARCEL_STEPS, null, List.of("sender")), "video-modal-invalid-parcel-role");
        assertRejected(info(PARCEL_STEPS, null, Arrays.asList("SENDER", null)), "video-modal-invalid-parcel-role");
    }

    @Test
    @DisplayName("rôles avec au moins une étape (même mêlée à d'autres déclencheurs) : acceptés, dédoublonnés, ordre fixe")
    void rolesAcceptedWithParcelTrigger() {
        savesItself();

        VideoModalAdminResponse r = admin.create(info(List.of(APP_OPEN, PARCEL_ARRIVED), null,
                List.of(" RECIPIENT ", "SENDER", "RECIPIENT")), adminId);

        assertThat(r.parcelRoles()).containsExactly(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
        assertThat(r.triggers()).containsExactly(APP_OPEN, PARCEL_ARRIVED);

        // Liste vide ou absente : tous les rôles (aucune restriction).
        assertThat(admin.create(info(PARCEL_STEPS, null, List.of()), adminId).parcelRoles()).isEmpty();
        assertThat(admin.create(info(PARCEL_STEPS, null, null), adminId).parcelRoles()).isEmpty();
    }

    // ── Fréquence « une fois par colis » ───────────────────────────────────

    @Test
    @DisplayName("ONCE_PER_PARCEL : 422 dès qu'un déclencheur n'est pas une étape du colis (ou situations seules)")
    void oncePerParcelRejectedWithMixedTriggers() {
        assertRejected(info(List.of(PARCEL_ARRIVED, APP_OPEN), VideoModalFrequency.ONCE_PER_PARCEL, null),
                "video-modal-once-per-parcel-parcel-triggers-only");
        // Un moment d'action qui n'est pas une étape ne suffit pas.
        assertRejected(info(List.of(PARCEL_DELIVERED, DELIVERY_CONFIRMED), VideoModalFrequency.ONCE_PER_PARCEL, null),
                "video-modal-once-per-parcel-parcel-triggers-only");
        assertRejected(info(List.of(), VideoModalFrequency.ONCE_PER_PARCEL, null, "Titre", null, "Texte", null,
                close(), null, List.of(new VideoModalRequest.SituationRequest("PROFILE_INCOMPLETE", null)), null),
                "video-modal-once-per-parcel-parcel-triggers-only");
    }

    @Test
    @DisplayName("ONCE_PER_PARCEL avec seulement des étapes : acceptée, sans nombre de jours")
    void oncePerParcelAcceptedWithOnlyParcelSteps() {
        savesItself();

        VideoModalAdminResponse r = admin.create(info(PARCEL_STEPS, VideoModalFrequency.ONCE_PER_PARCEL,
                List.of("TRAVELER")), adminId);

        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.ONCE_PER_PARCEL);
        assertThat(r.frequencyDays()).isNull();
        assertThat(r.triggers()).containsExactlyElementsOf(PARCEL_STEPS);
        assertThat(r.parcelRoles()).containsExactly(VideoModalParcelRole.TRAVELER);
    }

    // ── Variables des textes ───────────────────────────────────────────────

    @Test
    @DisplayName("variable inconnue ({code}, casse différente, vide) dans un titre ou un texte, FR ou EN : 422")
    void unknownPlaceholderRejected() {
        assertRejected(texts("Titre", null, "Votre code : {code}", null), "video-modal-unknown-variable");
        assertRejected(texts("Code {code}", null, "Texte", null), "video-modal-unknown-variable");
        assertRejected(texts("Titre", "Code {code}", "Texte", null), "video-modal-unknown-variable");
        assertRejected(texts("Titre", null, "Texte", "Your code: {code}"), "video-modal-unknown-variable");
        assertRejected(texts("Titre", null, "Bonjour {Destinataire}", null), "video-modal-unknown-variable");
        assertRejected(texts("Titre", null, "Bonjour { destinataire }", null), "video-modal-unknown-variable");
        assertRejected(texts("Titre", null, "Bonjour {}", null), "video-modal-unknown-variable");
        assertRejected(texts("Titre", null, "{destinataire} {code_retrait}", null), "video-modal-unknown-variable");

        // Le message nomme la variable refusée et la liste permise.
        assertThatThrownBy(() -> admin.create(texts("Titre", null, "Votre code : {code}", null), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getMessage())
                        .contains("{code}").contains("{destinataire}").contains("{voyageur}")
                        .contains("{expéditeur}").contains("{ville_arrivee}"));

        // Vaut aussi pour une vidéo (titre) et une campagne sans étape.
        assertRejected(new VideoModalRequest("V", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Code {code}", null, null,
                null, null, null, null, null, null, null, null, null, null, List.of(), List.of(APP_OPEN), null, null,
                null, null, null, null, null, null, null), "video-modal-unknown-variable");
    }

    @Test
    @DisplayName("les quatre variables permises sont acceptées et gardées telles quelles (FR et EN)")
    void allowedPlaceholdersAccepted() {
        savesItself();

        VideoModalAdminResponse r = admin.create(texts(
                "Colis arrivé à {ville_arrivee}",
                "Parcel arrived in {ville_arrivee}",
                "{voyageur} a remis le colis de {expéditeur} à {destinataire}.",
                "{voyageur} handed {expéditeur}'s parcel to {destinataire}."), adminId);

        assertThat(r.titleFr()).isEqualTo("Colis arrivé à {ville_arrivee}");
        assertThat(r.titleEn()).isEqualTo("Parcel arrived in {ville_arrivee}");
        assertThat(r.bodyFr()).isEqualTo("{voyageur} a remis le colis de {expéditeur} à {destinataire}.");
        assertThat(r.bodyEn()).isEqualTo("{voyageur} handed {expéditeur}'s parcel to {destinataire}.");

        // Une accolade isolée n'est pas une variable.
        assertThat(admin.create(texts("Titre", null, "Tarif { à partir de 5 €", null), adminId).bodyFr())
                .isEqualTo("Tarif { à partir de 5 €");
    }

    // ── Boutons liés au colis ──────────────────────────────────────────────

    @Test
    @DisplayName("bouton PARCEL_* (principal ou secondaire) sans déclencheur d'étape : 422")
    void parcelActionRejectedWithoutParcelTrigger() {
        assertRejected(withActions(List.of(APP_OPEN), screen("PARCEL_QR"), null),
                "video-modal-parcel-action-needs-parcel-trigger");
        assertRejected(withActions(List.of(SEARCH_TAB, DELIVERY_CONFIRMED), close(), screen("PARCEL_TRACKING")),
                "video-modal-parcel-action-needs-parcel-trigger");
        assertRejected(withActions(List.of(), screen("PARCEL_SEND_CODE"), null), "video-modal-triggers-required");
    }

    @Test
    @DisplayName("les quatre boutons PARCEL_* sont acceptés avec une étape du colis, relus tels quels")
    void parcelActionsAcceptedWithParcelTrigger() {
        savesItself();
        for (InfoScreen s : List.of(InfoScreen.PARCEL_DETAIL, InfoScreen.PARCEL_QR, InfoScreen.PARCEL_SEND_CODE,
                InfoScreen.PARCEL_TRACKING)) {
            VideoModalAdminResponse r = admin.create(withActions(List.of(PARCEL_HANDED_OVER, APP_OPEN),
                    screen(s.name()), screen(s.name())), adminId);
            assertThat(r.primaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.SCREEN, s, null));
            assertThat(r.secondaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.SCREEN, s, null));
        }
        // Le JSON stocké et servi garde ses trois champs : opensParcelScreen() n'en devient pas un.
        assertThat(new VideoModalJsonConverters.Action().convertToDatabaseColumn(
                new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_QR, null)))
                .isEqualTo("{\"kind\":\"SCREEN\",\"screen\":\"PARCEL_QR\",\"url\":null}");
    }

    // ── Campagnes existantes ───────────────────────────────────────────────

    @Test
    @DisplayName("campagnes existantes : sans parcelRoles, rien ne change et la capacité parcel n'est pas exigée")
    void existingCampaignsUnchanged() {
        var video = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        var info = infoCampaign(1, VideoModalAudience.EVERYONE);
        for (VideoModalCampaignEntity c : List.of(video, info)) {
            assertThat(c.getParcelRoles()).isEmpty();
            assertThat(c.needsParcelCapability()).isFalse();
            VideoModalEligibleResponse served = VideoModalTexts.toEligible(c, false, 0, mock(VideoModalImageUrls.class));
            assertThat(served.parcelRoles()).isNotNull().isEmpty();
        }

        // Colonne nulle (ligne d'avant V401) : ensemble vide ; ensemble vide : colonne laissée nulle.
        var converter = new VideoModalJsonConverters.ParcelRoles();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(" ")).isEmpty();
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
        assertThat(converter.convertToDatabaseColumn(Set.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();

        // L'ancien corps (sans parcelRoles) reste accepté et ne pose aucun rôle.
        savesItself();
        VideoModalAdminResponse r = admin.create(new VideoModalRequest("Info", VideoModalType.INFO, null, null,
                "Titre", null, null, null, "Ouvrir", null, null, null, "Texte", null, null, screen("TRACKING"), null,
                null, List.of(APP_OPEN), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), adminId);
        assertThat(r.parcelRoles()).isEmpty();
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
    }

    @Test
    @DisplayName("convertisseur des rôles : JSON en ordre fixe, relu à l'identique")
    void parcelRolesConverterRoundTrip() {
        var converter = new VideoModalJsonConverters.ParcelRoles();
        String json = converter.convertToDatabaseColumn(Set.of(VideoModalParcelRole.RECIPIENT, VideoModalParcelRole.SENDER));
        assertThat(json).isEqualTo("[\"SENDER\",\"RECIPIENT\"]");
        assertThat(converter.convertToEntityAttribute(json))
                .containsExactly(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("[\"PILOTE\"]"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Réponses admin et app, audit ───────────────────────────────────────

    @Test
    @DisplayName("réponse de l'app : rôles, étapes, ONCE_PER_PARCEL et boutons PARCEL_* servis tels quels")
    void eligibleResponseCarriesParcelData() {
        var c = parcelCampaign(PARCEL_ARRIVED, PARCEL_DELIVERED);

        VideoModalEligibleResponse r = VideoModalTexts.toEligible(c, true, 0, null, mock(VideoModalImageUrls.class),
                c.servedTriggers(true));

        assertThat(r.parcelRoles()).containsExactly(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
        assertThat(r.triggers()).containsExactly(PARCEL_ARRIVED, PARCEL_DELIVERED);
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.ONCE_PER_PARCEL);
        assertThat(r.primaryAction().screen()).isEqualTo(InfoScreen.PARCEL_QR);
        assertThat(r.secondaryAction().screen()).isEqualTo(InfoScreen.PARCEL_TRACKING);
        // Les variables partent telles quelles, dans la langue demandée : c'est l'app qui les remplit.
        assertThat(r.title()).isEqualTo("Parcel arrived in {ville_arrivee}");
        assertThat(r.body()).isEqualTo("{voyageur} dropped off {expéditeur}'s parcel for {destinataire}.");
        // withTargeting et withMoments gardent les rôles.
        assertThat(r.withTargeting(1, VideoModalFrequency.EVERY_OPEN, null, true, false, null).parcelRoles())
                .containsExactly(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
        assertThat(r.withMoments(List.of(PARCEL_ARRIVED), VideoModalTriggerOptions.DEFAULTS, List.of()).parcelRoles())
                .containsExactly(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
    }

    @Test
    @DisplayName("audit : les rôles visés figurent dans le journal, sans aucun texte du modal en clair inutile")
    void auditCarriesParcelRoles() {
        savesItself();

        VideoModalAdminResponse r = admin.create(info(List.of(PARCEL_IN_TRANSIT), VideoModalFrequency.ONCE_PER_PARCEL,
                List.of("RECIPIENT", "TRAVELER")), adminId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("parcelRoles", List.of("TRAVELER", "RECIPIENT"))
                .containsEntry("frequency", "ONCE_PER_PARCEL")
                .containsEntry("triggers", List.of("PARCEL_IN_TRANSIT"));
    }

    @Test
    @DisplayName("modification : retirer les étapes en gardant les rôles est refusé ; corps sans rôles = rôles effacés")
    void updateKeepsRulesAndClearsRoles() {
        var c = parcelCampaign(PARCEL_ARRIVED);
        when(campaignRepository.findById(c.getId())).thenReturn(java.util.Optional.of(c));

        assertThatThrownBy(() -> admin.update(c.getId(), info(List.of(APP_OPEN), null, List.of("SENDER")), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo("video-modal-parcel-roles-need-parcel-trigger"));
        verify(campaignRepository, never()).save(any());
        clearInvocations(campaignRepository);

        savesItself();
        VideoModalAdminResponse r = admin.update(c.getId(), info(List.of(PARCEL_ARRIVED), null, null), adminId);
        assertThat(r.parcelRoles()).isEmpty();
        assertThat(c.getParcelRoles()).isEqualTo(EnumSet.noneOf(VideoModalParcelRole.class));
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
    }
}
