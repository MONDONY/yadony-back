package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Verrous du mode recette (FLUTTER-FA/FB) : propriété, profil prod, drapeau du compte. */
class RecetteModeTest {

    private final AuditService auditService = mock(AuditService.class);

    private RecetteMode mode(boolean property, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new RecetteMode(property, env, auditService);
    }

    private static UserEntity user(boolean tester) {
        UserEntity u = new UserEntity();
        u.setRecetteTester(tester);
        return u;
    }

    @Test
    void staging_proprieteVraie_testeur_appliquee() {
        RecetteMode m = mode(true, "staging");
        assertThat(m.isEnabled()).isTrue();
        assertThat(m.appliesTo(user(true))).isTrue();
        assertThat(m.appliesTo(user(false))).isFalse();
        assertThat(m.appliesTo(null)).isFalse();
    }

    @Test
    void proprieteFausse_fermePourTous() {
        RecetteMode m = mode(false, "staging");
        assertThat(m.isEnabled()).isFalse();
        assertThat(m.appliesTo(user(true))).isFalse();
    }

    @Test
    void profilProd_fermeMemeAvecLaProprieteEtUnTesteur() {
        RecetteMode m = mode(true, "prod");
        assertThat(m.isEnabled()).isFalse();
        assertThat(m.appliesTo(user(true))).isFalse();
        // Profils combinés : prod l'emporte.
        assertThat(mode(true, "staging", "prod").appliesTo(user(true))).isFalse();
    }

    @Test
    void recordBypass_ecritAuditLog_etDisabledNeFaitRien() {
        UUID entity = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        mode(true, "staging").recordBypass("RECETTE_X", entity, actor, Map.of("k", "v"));
        verify(auditService).log("RECETTE", entity, "RECETTE_X", actor, Map.of("k", "v"));

        AuditService other = mock(AuditService.class);
        RecetteMode.disabled().recordBypass("RECETTE_X", entity, actor, Map.of());
        assertThat(RecetteMode.disabled().isEnabled()).isFalse();
        assertThat(RecetteMode.disabled().appliesTo(user(true))).isFalse();
        verify(other, never()).log(any(), any(), any(), any(), any());
    }

    // ── Câblage Spring réel : propriété yadony.recette.enabled + profils actifs ──

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(AuditService.class, () -> auditService)
                .withBean(RecetteMode.class);
    }

    @Test
    void spring_sansPropriete_fermeParDefaut() {
        runner().run(ctx -> assertThat(ctx.getBean(RecetteMode.class).isEnabled()).isFalse());
    }

    @Test
    void spring_profilStaging_proprieteVraie_ouvert() {
        runner().withPropertyValues("yadony.recette.enabled=true")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("staging"))
                .run(ctx -> assertThat(ctx.getBean(RecetteMode.class).appliesTo(user(true))).isTrue());
    }

    @Test
    void spring_profilProd_proprieteVraie_fermePourUnTesteur() {
        runner().withPropertyValues("yadony.recette.enabled=true")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> {
                    RecetteMode m = ctx.getBean(RecetteMode.class);
                    assertThat(m.isEnabled()).isFalse();
                    assertThat(m.appliesTo(user(true))).isFalse();
                });
    }
}
