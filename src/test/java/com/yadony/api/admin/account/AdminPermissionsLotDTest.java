package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D — deux permissions neuves pour la plateforme :
 *
 * <ul>
 *   <li>{@code NOTIFICATION_SEND} : envoi d'un broadcast de notifications.</li>
 *   <li>{@code CONFIG_MANAGE} : modification des parametres plateforme.</li>
 * </ul>
 *
 * <p>Aucune des deux ne va au support : couper les SMS, c'est couper la connexion par
 * OTP ({@code SmsOtpService:76,105}), et ecrire a tous les utilisateurs n'est pas un
 * geste de support. L'escalade reste possible au cas par cas via
 * {@code permissionOverrides}.
 */
class AdminPermissionsLotDTest {

    @Test
    void adminAndSuperAdmin_receiveBothNewPermissions() {
        Set<AdminPermission> admin = AdminPermissions.effective(AdminRole.ADMIN, Map.of());
        Set<AdminPermission> superAdmin = AdminPermissions.effective(AdminRole.SUPER_ADMIN, Map.of());

        assertThat(admin).contains(AdminPermission.NOTIFICATION_SEND, AdminPermission.CONFIG_MANAGE);
        assertThat(superAdmin).contains(AdminPermission.NOTIFICATION_SEND, AdminPermission.CONFIG_MANAGE);
    }

    @Test
    void support_receivesNeitherNewPermission() {
        Set<AdminPermission> support = AdminPermissions.effective(AdminRole.SUPPORT, Map.of());

        assertThat(support).doesNotContain(
                AdminPermission.NOTIFICATION_SEND, AdminPermission.CONFIG_MANAGE);
    }

    @Test
    void support_canReceiveConfigManageViaExplicitOverride() {
        Set<AdminPermission> support = AdminPermissions.effective(
                AdminRole.SUPPORT, Map.of("CONFIG_MANAGE", true));

        assertThat(support).contains(AdminPermission.CONFIG_MANAGE);
        assertThat(support).doesNotContain(AdminPermission.NOTIFICATION_SEND);
    }

    @Test
    void enumHolds29Permissions() {
        assertThat(AdminPermission.values()).hasSize(29);
    }
}
