package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportActionPolicyTest {

    private static final Set<String> TOUTES = Set.copyOf(Arrays.stream(AdminPermission.values())
            .map(Enum::name).toList());

    private static ReportEntity report(ReportTargetType type, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(type);
        r.setTargetId(type == ReportTargetType.APP ? null : UUID.randomUUID());
        r.setStatus(status);
        return r;
    }

    private static UserEntity user() {
        UserEntity u = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        return u;
    }

    private static ResolvedReportTarget found(UserEntity author) {
        return new ResolvedReportTarget(true, false, author, null, null);
    }

    // ---- permissions requises ----

    @Test
    void permissionsRequises_parAction() {
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.RESOLVE)).containsExactly("REPORT_RESOLVE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.DISMISS)).containsExactly("REPORT_RESOLVE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.WARN)).containsExactly("REPORT_RESOLVE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.WARN_AUTHOR)).containsExactly("REPORT_RESOLVE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.SUSPEND_TARGET))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "USER_SUSPEND");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.SUSPEND_AUTHOR))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "USER_SUSPEND");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.REMOVE_CONTENT))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "CONTENT_REMOVE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.DELETE_MESSAGE))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "MESSAGE_DELETE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.EXCLUDE_RATING))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "RATING_MODERATE");
        assertThat(ReportActionPolicy.requiredAuthorities(ReportAction.DELETE_RATING))
                .containsExactlyInAnyOrder("REPORT_RESOLVE", "RATING_DELETE");
    }

    @Test
    void requireAuthorities_permissionSpecifiqueManquante_403() {
        List<GrantedAuthority> seulementResolve = List.of(new SimpleGrantedAuthority("REPORT_RESOLVE"));
        var auth = new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.com", AdminRole.SUPPORT, false, "uid"),
                null, seulementResolve);

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> ReportActionPolicy.requireAuthorities(ReportAction.DELETE_RATING, auth));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("authority-required");
        assertThatCode(() -> ReportActionPolicy.requireAuthorities(ReportAction.WARN_AUTHOR, auth))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAuthorities_nIgnorePasReportResolveAbsentCarPorteParLAnnotation() {
        // REPORT_RESOLVE est exigé par @PreAuthorize sur l'endpoint : seule la permission
        // propre au geste est vérifiée ici.
        var auth = new UsernamePasswordAuthenticationToken("x", null, List.of(new SimpleGrantedAuthority("USER_SUSPEND")));
        assertThatCode(() -> ReportActionPolicy.requireAuthorities(ReportAction.SUSPEND_AUTHOR, auth))
                .doesNotThrowAnyException();
    }

    // ---- actions disponibles ----

    @Test
    void disponibles_signalementDejaTraite_listeVide() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.RESOLVED),
                found(user()), TOUTES)).isEmpty();
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.APP, ReportStatus.DISMISSED),
                ResolvedReportTarget.none(), TOUTES)).isEmpty();
    }

    @Test
    void disponibles_sansReportResolve_listeVide() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.APP, ReportStatus.OPEN),
                ResolvedReportTarget.none(), Set.of("REPORT_VIEW"))).isEmpty();
    }

    @Test
    void disponibles_app_traiterEtRejeter() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.APP, ReportStatus.OPEN),
                ResolvedReportTarget.none(), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    @Test
    void disponibles_avis_toutesPermissions() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.OPEN),
                found(user()), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN_AUTHOR,
                        ReportAction.SUSPEND_AUTHOR, ReportAction.EXCLUDE_RATING, ReportAction.DELETE_RATING);
    }

    @Test
    void disponibles_avis_supportSansPermissionsDeModeration() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.OPEN),
                found(user()), Set.of("REPORT_VIEW", "REPORT_RESOLVE")))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN_AUTHOR);
    }

    @Test
    void disponibles_avisDejaExclu_pasDExclusionMaisSuppression() {
        var target = new ResolvedReportTarget(true, true, user(), null, null);
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.OPEN), target, TOUTES))
                .doesNotContain(ReportAction.EXCLUDE_RATING)
                .contains(ReportAction.DELETE_RATING);
    }

    @Test
    void disponibles_avisAnonyme_pasDActionSurLAuteur() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.OPEN),
                found(null), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS,
                        ReportAction.EXCLUDE_RATING, ReportAction.DELETE_RATING);
    }

    @Test
    void disponibles_avisIntrouvable_seulementTraiterEtRejeter() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.RATING, ReportStatus.OPEN),
                ResolvedReportTarget.none(), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    @Test
    void disponibles_messageRetrouve() {
        ConversationEntity conv = new ConversationEntity();
        var target = new ResolvedReportTarget(true, false, user(), conv, "msg-1");
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.MESSAGE, ReportStatus.OPEN), target, TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN_AUTHOR,
                        ReportAction.SUSPEND_AUTHOR, ReportAction.DELETE_MESSAGE);
    }

    @Test
    void disponibles_messageDejaSupprime_pasDeSuppression() {
        var target = new ResolvedReportTarget(true, true, user(), new ConversationEntity(), "msg-1");
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.MESSAGE, ReportStatus.OPEN), target, TOUTES))
                .doesNotContain(ReportAction.DELETE_MESSAGE)
                .contains(ReportAction.WARN_AUTHOR);
    }

    @Test
    void disponibles_messageIntrouvable_seulementTraiterEtRejeter() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.MESSAGE, ReportStatus.OPEN),
                ResolvedReportTarget.none(), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    @Test
    void disponibles_offre_partieAdverseRetrouvee() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.BID, ReportStatus.OPEN),
                found(user()), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN_AUTHOR,
                        ReportAction.SUSPEND_AUTHOR);
    }

    @Test
    void disponibles_utilisateur() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.USER, ReportStatus.OPEN),
                found(user()), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN,
                        ReportAction.SUSPEND_TARGET);
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.USER, ReportStatus.OPEN),
                ResolvedReportTarget.none(), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    @Test
    void disponibles_annonce_retraitSelonPermission() {
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.ANNOUNCEMENT, ReportStatus.OPEN),
                found(user()), TOUTES))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.REMOVE_CONTENT);
        assertThat(ReportActionPolicy.availableActions(report(ReportTargetType.PACKAGE_REQUEST, ReportStatus.OPEN),
                found(user()), Set.of("REPORT_RESOLVE")))
                .containsExactly(ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    // ---- exécutabilité d'un appel direct ----

    @Test
    void executable_actionsNouvellesExigentUneCibleRetrouvee() {
        assertThat(ReportActionPolicy.isExecutable(ReportAction.DELETE_MESSAGE, ResolvedReportTarget.none())).isFalse();
        assertThat(ReportActionPolicy.isExecutable(ReportAction.DELETE_MESSAGE,
                new ResolvedReportTarget(true, true, null, new ConversationEntity(), "m"))).isTrue();
        assertThat(ReportActionPolicy.isExecutable(ReportAction.EXCLUDE_RATING, ResolvedReportTarget.none())).isFalse();
        assertThat(ReportActionPolicy.isExecutable(ReportAction.DELETE_RATING, found(null))).isTrue();
        assertThat(ReportActionPolicy.isExecutable(ReportAction.WARN_AUTHOR, found(null))).isFalse();
        assertThat(ReportActionPolicy.isExecutable(ReportAction.SUSPEND_AUTHOR, found(user()))).isTrue();
    }

    @Test
    void executable_actionsHistoriquesInchangees() {
        // RESOLVE/DISMISS ne touchent pas la cible ; WARN, SUSPEND_TARGET et REMOVE_CONTENT
        // gardent leur comportement d'avant (les services délégués répondent 404 eux-mêmes).
        for (ReportAction a : List.of(ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN,
                ReportAction.SUSPEND_TARGET, ReportAction.REMOVE_CONTENT)) {
            assertThat(ReportActionPolicy.isExecutable(a, ResolvedReportTarget.none())).as(a.name()).isTrue();
        }
    }
}
