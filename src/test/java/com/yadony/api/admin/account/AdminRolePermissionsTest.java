package com.yadony.api.admin.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRolePermissionsTest {

    @Test
    @DisplayName("SUPER_ADMIN et ADMIN peuvent supprimer un compte")
    void superAdminAndAdmin_haveUserDelete() {
        assertThat(AdminRole.SUPER_ADMIN.permissions()).contains(AdminPermission.USER_DELETE);
        assertThat(AdminRole.ADMIN.permissions()).contains(AdminPermission.USER_DELETE);
    }

    // Traiter une demande RGPD reçue et décider soi-même de supprimer un compte
    // sont deux gestes distincts : le support fait le premier, jamais le second.
    @Test
    @DisplayName("SUPPORT ne peut pas supprimer un compte")
    void support_hasNoUserDelete() {
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.USER_DELETE);
    }

    @Test
    @DisplayName("USER_DELETE reste distincte de USER_GDPR_DELETE")
    void support_hasNeitherDeletionPermission() {
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.USER_DELETE);
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.USER_GDPR_DELETE);
    }

    // Offrir ou révoquer un accès PRO gratuit est un geste commercial de la même portée
    // que USER_COMMISSION : accordé à ADMIN et SUPER_ADMIN, jamais au support.
    @Test
    @DisplayName("SUPER_ADMIN et ADMIN peuvent offrir/révoquer un accès PRO, pas SUPPORT")
    void onlyAdminsHaveProGrant() {
        assertThat(AdminRole.SUPER_ADMIN.permissions()).contains(AdminPermission.USER_PRO_GRANT);
        assertThat(AdminRole.ADMIN.permissions()).contains(AdminPermission.USER_PRO_GRANT);
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.USER_PRO_GRANT);
    }

    // Valider, refuser ou révoquer une identité engage la plateforme (un compte vérifié
    // ouvre les paiements) : ADMIN et SUPER_ADMIN seulement. Le support garde la lecture
    // de la file via USER_KYC.
    @Test
    @DisplayName("KYC_DECIDE : ADMIN et SUPER_ADMIN, jamais SUPPORT qui garde USER_KYC")
    void onlyAdminsDecideKyc() {
        assertThat(AdminRole.SUPER_ADMIN.permissions()).contains(AdminPermission.KYC_DECIDE);
        assertThat(AdminRole.ADMIN.permissions()).contains(AdminPermission.KYC_DECIDE);
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.KYC_DECIDE);
        assertThat(AdminRole.SUPPORT.permissions()).contains(AdminPermission.USER_KYC);
    }
}
