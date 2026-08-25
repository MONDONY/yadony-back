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
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.USER_GDPR_DELETE);
    }
}
