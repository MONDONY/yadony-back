package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Lot B : CONTENT_REMOVE et USER_MESSAGE_MUTE ne sont pas des permissions de support. */
class AdminPermissionsLotBTest {

    @Test
    void superAdmin_hasBothNewPermissions() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPER_ADMIN, Map.of());
        assertThat(perms).contains(AdminPermission.CONTENT_REMOVE, AdminPermission.USER_MESSAGE_MUTE);
    }

    @Test
    void admin_hasBothNewPermissions() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.ADMIN, Map.of());
        assertThat(perms).contains(AdminPermission.CONTENT_REMOVE, AdminPermission.USER_MESSAGE_MUTE);
    }

    @Test
    // Lot C : USER_MESSAGE_MUTE a ete accordee au support sur decision du proprietaire du
    // produit — il peut deja bannir, la coupure de messagerie en est l'alternative
    // proportionnee. CONTENT_REMOVE, elle, lui reste fermee.
    void support_hasNoContentRemove() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, Map.of());
        assertThat(perms).doesNotContain(AdminPermission.CONTENT_REMOVE);
    }

    @Test
    void support_canReceiveContentRemoveViaOverride() {
        Set<AdminPermission> perms = AdminPermissions.effective(
                AdminRole.SUPPORT, Map.of("CONTENT_REMOVE", true));
        assertThat(perms).contains(AdminPermission.CONTENT_REMOVE);
    }

    @Test
    // 27 depuis le Lot C, qui detache RATING_DELETE de RATING_MODERATE.
    void enumHasExactlyTwentySevenValues() {
        assertThat(AdminPermission.values()).hasSize(27);
    }
}
