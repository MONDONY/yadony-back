package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot C — granularité des permissions, arbitrée par le propriétaire du produit après audit :
 *
 * <ul>
 *   <li>{@code RATING_DELETE} est détachée de {@code RATING_MODERATE} : le support continue de
 *       consulter et d'exclure un avis, mais ne peut plus l'effacer définitivement.</li>
 *   <li>{@code USER_MESSAGE_MUTE} rejoint le support : il peut déjà bannir, geste bien plus
 *       sévère — couper la messagerie en est l'alternative proportionnée.</li>
 * </ul>
 */
class AdminPermissionsLotCTest {

    @Test
    void support_moderatesRatingsButCannotDeleteThem() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, Map.of());
        assertThat(perms).contains(AdminPermission.RATING_MODERATE);
        assertThat(perms).doesNotContain(AdminPermission.RATING_DELETE);
    }

    @Test
    void adminAndSuperAdmin_canDeleteRatings() {
        assertThat(AdminPermissions.effective(AdminRole.ADMIN, Map.of()))
                .contains(AdminPermission.RATING_DELETE);
        assertThat(AdminPermissions.effective(AdminRole.SUPER_ADMIN, Map.of()))
                .contains(AdminPermission.RATING_DELETE);
    }

    @Test
    void support_canDeleteRatingsViaExplicitOverride() {
        Set<AdminPermission> perms = AdminPermissions.effective(
                AdminRole.SUPPORT, Map.of("RATING_DELETE", true));
        assertThat(perms).contains(AdminPermission.RATING_DELETE);
    }

    @Test
    void support_canMuteMessagingSinceItAlreadyBans() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, Map.of());
        assertThat(perms).contains(AdminPermission.USER_BAN, AdminPermission.USER_MESSAGE_MUTE);
    }

    @Test
    void support_stillCannotRemoveContent() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, Map.of());
        assertThat(perms).doesNotContain(AdminPermission.CONTENT_REMOVE);
    }
}
