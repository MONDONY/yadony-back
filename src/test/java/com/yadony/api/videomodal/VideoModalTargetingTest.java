package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.rule.CompiledRule;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Ciblage d'un compte : conditions de la Diffusion, situations, tirage stable, estimation. */
@DisplayName("Ciblage des modals : conditions, situations, tirage, estimation")
class VideoModalTargetingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LANG_FR = "{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}]}";

    private RuleAudienceRepository rules;
    private VideoModalSituationQueries queries;
    private VideoModalTargeting targeting;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rules = mock(RuleAudienceRepository.class);
        queries = mock(VideoModalSituationQueries.class);
        targeting = new VideoModalTargeting(rules, queries, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    // ── Conditions ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("normalizeConditions : absent, nul ou groupe vide donnent null ; sinon JSON canonique")
    void normalizeVide() throws Exception {
        assertThat(targeting.normalizeConditions(null)).isNull();
        assertThat(targeting.normalizeConditions(MAPPER.nullNode())).isNull();
        assertThat(targeting.normalizeConditions(MAPPER.missingNode())).isNull();
        assertThat(targeting.normalizeConditions(json("{\"op\":\"AND\",\"children\":[]}"))).isNull();
        String canonical = targeting.normalizeConditions(json(LANG_FR));
        assertThat(canonical).isEqualTo("{\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}],\"op\":\"AND\"}");
    }

    @Test
    @DisplayName("normalizeConditions : forme, champ, opérateur ou valeur invalides = 422 du moteur de la Diffusion")
    void normalizeInvalide() {
        for (String bad : List.of(
                "{\"op\":\"AND\"}",
                "{\"op\":\"XOR\",\"children\":[]}",
                "{\"op\":\"AND\",\"children\":[{\"field\":\"inconnu\",\"operator\":\"IS\",\"value\":true}]}",
                "{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IS\",\"value\":\"fr\"}]}",
                "{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"zz\"]}]}",
                "{\"op\":\"AND\",\"children\":[{\"field\":\"completed_trips\",\"operator\":\"GTE\",\"value\":-1}]}")) {
            assertThatThrownBy(() -> targeting.normalizeConditions(json(bad)))
                    .as(bad).isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        var ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus().value()).isEqualTo(422);
                        assertThat(ex.getErrorCode()).isEqualTo("broadcast-rule-invalid");
                    });
        }
        assertThatThrownBy(() -> targeting.normalizeConditions(json("[1]")))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("readConditions : groupe ET vide quand rien n'est stocké, sinon l'arbre stocké")
    void readConditions() {
        assertThat(VideoModalTargeting.readConditions(null).toString()).isEqualTo("{\"op\":\"AND\",\"children\":[]}");
        assertThat(VideoModalTargeting.readConditions(" ").get("children")).isEmpty();
        assertThat(VideoModalTargeting.readConditions(LANG_FR).get("children")).hasSize(1);
        assertThatThrownBy(() -> VideoModalTargeting.readConditions("{pas du json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("matchesConditions : compile l'arbre stocké et demande au moteur si CE compte en fait partie")
    void matchesConditions() {
        when(rules.matches(any(), eq(user))).thenReturn(true);
        assertThat(targeting.matchesConditions(LANG_FR, user)).isTrue();

        ArgumentCaptor<CompiledRule> compiled = ArgumentCaptor.forClass(CompiledRule.class);
        verify(rules).matches(compiled.capture(), eq(user));
        assertThat(compiled.getValue().where()).contains("u.preferred_language IN (:p0)");
        assertThat(compiled.getValue().params().getValue("p0")).isEqualTo(List.of("fr"));

        when(rules.matches(any(), eq(user))).thenReturn(false);
        assertThat(targeting.matchesConditions(LANG_FR, user)).isFalse();
        assertThatThrownBy(() -> targeting.matchesConditions("{pas du json", user))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Situations fondées sur le moteur de la Diffusion ───────────────────

    @Test
    @DisplayName("KYC_ABANDONED : la règle kyc_abandoned de la Diffusion, telle quelle")
    void kycAbandoned() {
        when(rules.matches(any(), eq(user))).thenReturn(true);
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null), user)).isTrue();
        ArgumentCaptor<CompiledRule> compiled = ArgumentCaptor.forClass(CompiledRule.class);
        verify(rules).matches(compiled.capture(), eq(user));
        assertThat(compiled.getValue().where()).contains("kyc_schema.kyc_verifications").contains("u.kyc_status");
    }

    @Test
    @DisplayName("DAYS_SINCE_INSTALL_NO_ACTION : signed_up + published_trip + created_request, valeur par défaut 7 jours")
    void joursDepuisInstall() {
        when(rules.matches(any(), eq(user))).thenReturn(false);
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.DAYS_SINCE_INSTALL_NO_ACTION, 30), user)).isFalse();
        ArgumentCaptor<CompiledRule> compiled = ArgumentCaptor.forClass(CompiledRule.class);
        verify(rules).matches(compiled.capture(), eq(user));
        String where = compiled.getValue().where();
        assertThat(where).contains("u.created_at < now()").contains("FROM announcements a").contains("FROM bids b");
        assertThat(where).contains("NOT EXISTS");
        assertThat(compiled.getValue().params().getValues().values()).contains(30);

        targeting.holds(new VideoModalSituation(VideoModalSituationKind.DAYS_SINCE_INSTALL_NO_ACTION, null), user);
        ArgumentCaptor<CompiledRule> second = ArgumentCaptor.forClass(CompiledRule.class);
        org.mockito.Mockito.verify(rules, org.mockito.Mockito.times(2)).matches(second.capture(), eq(user));
        assertThat(second.getAllValues().get(1).params().getValues().values()).contains(7);
    }

    // ── Situations lues dans les offres, le profil et le parrainage ────────

    @Test
    @DisplayName("heures : la borne est maintenant moins N heures pile (N = 1, 24 et 720)")
    void bornesHeures() {
        when(queries.offerAcceptedUnpaid(eq(user), any())).thenReturn(true);
        when(queries.offerUnanswered(eq(user), any())).thenReturn(true);
        when(queries.counterOfferPending(eq(user), any())).thenReturn(true);

        for (int hours : new int[] {1, 24, 720}) {
            OffsetDateTime expected = OffsetDateTime.ofInstant(NOW.minusSeconds(hours * 3600L), ZoneOffset.UTC);
            assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.OFFER_ACCEPTED_UNPAID, hours), user)).isTrue();
            assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, hours), user)).isTrue();
            assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.COUNTER_OFFER_PENDING, hours), user)).isTrue();
            verify(queries).offerAcceptedUnpaid(user, expected);
            verify(queries).offerUnanswered(user, expected);
            verify(queries).counterOfferPending(user, expected);
        }
    }

    @Test
    @DisplayName("heures : valeur absente = 24 h par défaut")
    void heuresParDefaut() {
        OffsetDateTime h24 = OffsetDateTime.ofInstant(NOW.minusSeconds(24 * 3600L), ZoneOffset.UTC);
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, null), user)).isFalse();
        verify(queries).offerUnanswered(user, h24);
    }

    @Test
    @DisplayName("aucune donnée : toutes les situations sont fausses")
    void aucuneDonnee() {
        when(rules.matches(any(), any())).thenReturn(false);
        when(queries.lastAbsence(user)).thenReturn(Optional.empty());
        for (VideoModalSituationKind kind : VideoModalSituationKind.values()) {
            assertThat(targeting.holds(new VideoModalSituation(kind, kind.defaultValue()), user)).as(kind.name()).isFalse();
        }
    }

    @Test
    @DisplayName("profil incomplet et première livraison sans parrainage : lisent les requêtes dédiées")
    void profilEtParrainage() {
        when(queries.profileIncomplete(user)).thenReturn(true);
        when(queries.firstDeliveryNoReferral(user)).thenReturn(true);
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null), user)).isTrue();
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.FIRST_DELIVERY_NO_REFERRAL, null), user)).isTrue();
        verifyNoInteractions(rules);
    }

    // ── Retour après une absence ───────────────────────────────────────────

    private static VideoModalSituationQueries.Absence absence(Instant started, Instant returned) {
        return new VideoModalSituationQueries.Absence(LocalDateTime.ofInstant(started, ZoneOffset.UTC),
                LocalDateTime.ofInstant(returned, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("retour : exactement N jours d'absence = vrai, une seconde de moins = faux")
    void retourBorneExacte() {
        Instant returned = NOW.minusSeconds(3600);
        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(returned.minusSeconds(14 * 86400L), returned)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), user)).isTrue();

        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(returned.minusSeconds(14 * 86400L - 1), returned)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), user)).isFalse();
    }

    @Test
    @DisplayName("retour : plus de 24 h après le retour, la situation est passée ; exactement 24 h, encore vraie")
    void retourFenetre() {
        Instant returned = NOW.minus(VideoModalTargeting.RETURN_WINDOW);
        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(returned.minusSeconds(30 * 86400L), returned)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), user)).isTrue();

        Instant tooOld = returned.minusSeconds(1);
        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(tooOld.minusSeconds(30 * 86400L), tooOld)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), user)).isFalse();
    }

    @Test
    @DisplayName("retour : valeur par défaut 14 jours")
    void retourDefaut() {
        Instant returned = NOW.minusSeconds(60);
        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(returned.minusSeconds(13 * 86400L), returned)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, null), user)).isFalse();
        when(queries.lastAbsence(user)).thenReturn(Optional.of(absence(returned.minusSeconds(14 * 86400L), returned)));
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, null), user)).isTrue();
    }

    // ── Tirage stable ──────────────────────────────────────────────────────

    @Test
    @DisplayName("tirage : même personne, même campagne, même résultat sur 1000 appels")
    void tirageStable() {
        UUID campaign = UUID.randomUUID();
        for (int percent : new int[] {1, 25, 50, 99}) {
            boolean first = VideoModalTargeting.inSample(user, campaign, percent);
            for (int i = 0; i < 1000; i++) {
                assertThat(VideoModalTargeting.inSample(user, campaign, percent)).isEqualTo(first);
            }
        }
    }

    @Test
    @DisplayName("tirage : 100 % = tout le monde ; le seau est entre 0 et 99 ; monotone en pourcentage")
    void tirageBornes() {
        UUID campaign = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            UUID u = UUID.randomUUID();
            int bucket = VideoModalTargeting.bucket(u, campaign);
            assertThat(bucket).isBetween(0, 99);
            assertThat(VideoModalTargeting.inSample(u, campaign, 100)).isTrue();
            assertThat(VideoModalTargeting.inSample(u, campaign, 1000)).isTrue();
            assertThat(VideoModalTargeting.inSample(u, campaign, bucket)).isFalse();
            assertThat(VideoModalTargeting.inSample(u, campaign, bucket + 1)).isTrue();
        }
    }

    @Test
    @DisplayName("tirage : ~50 % sur 10 000 comptes, et deux campagnes ne tirent pas les mêmes comptes")
    void tirageRepartition() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        int inA = 0;
        int inBoth = 0;
        for (int i = 0; i < 10_000; i++) {
            UUID u = UUID.randomUUID();
            boolean ia = VideoModalTargeting.inSample(u, a, 50);
            boolean ib = VideoModalTargeting.inSample(u, b, 50);
            if (ia) inA++;
            if (ia && ib) inBoth++;
        }
        assertThat(inA).isBetween(4700, 5300);
        assertThat(inBoth).isBetween(2200, 2800); // indépendantes : ~25 %
    }

    // ── Estimation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("estimation sans condition : tous les comptes actifs ; après tirage = arrondi du pourcentage")
    void estimationSansCondition() {
        when(rules.count(any())).thenReturn(1234L);
        var estimate = targeting.estimate(null, List.of(), 25);
        assertThat(estimate.accounts()).isEqualTo(1234);
        assertThat(estimate.afterSample()).isEqualTo(309); // 308,5 arrondi
        ArgumentCaptor<CompiledRule> compiled = ArgumentCaptor.forClass(CompiledRule.class);
        verify(rules).count(compiled.capture());
        assertThat(compiled.getValue().where()).isEqualTo("u.deleted_at IS NULL AND u.status = 'ACTIVE'");
    }

    @Test
    @DisplayName("estimation avec conditions et plateformes : règle + appareils enregistrés (ios/android)")
    void estimationAvecConditionsEtPlateformes() {
        when(rules.count(any())).thenReturn(10L);
        var estimate = targeting.estimate(
                "{\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}],\"op\":\"AND\"}",
                List.of(VideoModalPlatform.IOS, VideoModalPlatform.ANDROID), 100);
        assertThat(estimate.accounts()).isEqualTo(10);
        assertThat(estimate.afterSample()).isEqualTo(10);
        ArgumentCaptor<CompiledRule> compiled = ArgumentCaptor.forClass(CompiledRule.class);
        verify(rules).count(compiled.capture());
        assertThat(compiled.getValue().where()).contains("preferred_language").contains("FROM user_devices d");
        assertThat(compiled.getValue().params().getValue("vmPlatforms")).isEqualTo(List.of("ios", "android"));
        assertThat(compiled.getValue().params().getValue("p0")).isEqualTo(List.of("fr"));
    }

    @Test
    @DisplayName("estimation : pourcentage borné à 1..100, conditions illisibles refusées")
    void estimationBornes() {
        when(rules.count(any())).thenReturn(1000L);
        assertThat(targeting.estimate(null, null, 0).afterSample()).isEqualTo(10);
        assertThat(targeting.estimate(null, null, 500).afterSample()).isEqualTo(1000);
        assertThatThrownBy(() -> targeting.estimate("{pas du json", List.of(), 100))
                .isInstanceOf(IllegalStateException.class);
    }
}
