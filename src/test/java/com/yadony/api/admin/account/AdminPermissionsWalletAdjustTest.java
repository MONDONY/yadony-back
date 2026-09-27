package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WALLET_ADJUST : corriger à la main le solde wallet d'un utilisateur. Écrire de l'argent
 * n'est pas un geste de support : ADMIN et SUPER_ADMIN seulement, escalade possible au cas
 * par cas via {@code permissionOverrides}.
 */
class AdminPermissionsWalletAdjustTest {

    @Test
    void adminEtSuperAdmin_peuventCorrigerUnSolde() {
        assertThat(AdminPermissions.effective(AdminRole.ADMIN, Map.of())).contains(AdminPermission.WALLET_ADJUST);
        assertThat(AdminPermissions.effective(AdminRole.SUPER_ADMIN, Map.of())).contains(AdminPermission.WALLET_ADJUST);
    }

    @Test
    void support_neLePeutPas_saufOverrideExplicite() {
        assertThat(AdminPermissions.effective(AdminRole.SUPPORT, Map.of())).doesNotContain(AdminPermission.WALLET_ADJUST);
        assertThat(AdminPermissions.effective(AdminRole.SUPPORT, Map.of("WALLET_ADJUST", true)))
                .contains(AdminPermission.WALLET_ADJUST);
    }
}
