package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AdminPermission, AdminRole, and AdminPermissions (Task 2 TDD).
 */
@DisplayName("AdminPermissions — Enums and overrides logic")
class AdminPermissionsTest {

    @Test
    @DisplayName("AdminRole.SUPER_ADMIN.permissions() contains all AdminPermission values")
    void testSuperAdminHasAllPermissions() {
        Set<AdminPermission> perms = AdminRole.SUPER_ADMIN.permissions();

        assertEquals(AdminPermission.values().length, perms.size(),
                "SUPER_ADMIN should have all 25 permissions");

        for (AdminPermission p : AdminPermission.values()) {
            assertTrue(perms.contains(p),
                    "SUPER_ADMIN should contain " + p);
        }
    }

    @Test
    @DisplayName("AdminRole.ADMIN.permissions() contains all except ADMIN_MANAGE")
    void testAdminHasAllExceptAdminManage() {
        Set<AdminPermission> perms = AdminRole.ADMIN.permissions();

        // Should have 24 permissions (all minus ADMIN_MANAGE)
        assertEquals(AdminPermission.values().length - 1, perms.size(),
                "ADMIN should have 24 permissions (all except ADMIN_MANAGE)");

        assertFalse(perms.contains(AdminPermission.ADMIN_MANAGE),
                "ADMIN should not have ADMIN_MANAGE");

        for (AdminPermission p : AdminPermission.values()) {
            if (p != AdminPermission.ADMIN_MANAGE) {
                assertTrue(perms.contains(p),
                        "ADMIN should contain " + p);
            }
        }
    }

    @Test
    @DisplayName("AdminRole.SUPPORT.permissions() contains exactly 16 specific permissions")
    // 16 depuis le Lot C : USER_MESSAGE_MUTE accordee au support sur decision du
    // proprietaire du produit (il peut deja bannir, geste bien plus severe).
    void testSupportHasExactly16Permissions() {
        Set<AdminPermission> perms = AdminRole.SUPPORT.permissions();

        assertEquals(16, perms.size(),
                "SUPPORT should have exactly 16 permissions");

        // Verify exact set
        assertTrue(perms.contains(AdminPermission.METRICS_VIEW));
        assertTrue(perms.contains(AdminPermission.USER_VIEW));
        assertTrue(perms.contains(AdminPermission.USER_SUSPEND));
        assertTrue(perms.contains(AdminPermission.USER_BAN));
        assertTrue(perms.contains(AdminPermission.USER_KYC));
        assertTrue(perms.contains(AdminPermission.PAYMENT_VIEW));
        assertTrue(perms.contains(AdminPermission.BID_VIEW));
        assertTrue(perms.contains(AdminPermission.DISPUTE_VIEW));
        assertTrue(perms.contains(AdminPermission.ALERT_VIEW));
        assertTrue(perms.contains(AdminPermission.ALERT_RESOLVE));
        assertTrue(perms.contains(AdminPermission.MODERATION_VIEW));
        assertTrue(perms.contains(AdminPermission.MESSAGE_DELETE));
        assertTrue(perms.contains(AdminPermission.REPORT_VIEW));
        assertTrue(perms.contains(AdminPermission.REPORT_RESOLVE));
        assertTrue(perms.contains(AdminPermission.RATING_MODERATE));
        // Lot C : le support modere les avis mais ne les efface pas definitivement.
        assertFalse(perms.contains(AdminPermission.RATING_DELETE));
        assertTrue(perms.contains(AdminPermission.USER_MESSAGE_MUTE));
    }

    @Test
    @DisplayName("AdminPermissions.effective() — SUPPORT + grant PAYMENT_REFUND via override")
    void testEffectiveSupport_GrantPaymentRefundOverride() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("PAYMENT_REFUND", true);

        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, overrides);

        assertTrue(perms.contains(AdminPermission.PAYMENT_REFUND),
                "SUPPORT + override should contain PAYMENT_REFUND");
        assertTrue(perms.contains(AdminPermission.USER_VIEW),
                "SUPPORT should still have USER_VIEW");
    }

    @Test
    @DisplayName("AdminPermissions.effective() — SUPPORT + revoke ALERT_RESOLVE via override")
    void testEffectiveSupport_RevokeAlertResolveOverride() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("ALERT_RESOLVE", false);

        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPPORT, overrides);

        assertFalse(perms.contains(AdminPermission.ALERT_RESOLVE),
                "SUPPORT + override should NOT contain ALERT_RESOLVE");
        assertTrue(perms.contains(AdminPermission.ALERT_VIEW),
                "SUPPORT should still have ALERT_VIEW");
    }

    @Test
    @DisplayName("AdminPermissions.effective() — SUPER_ADMIN ignores all overrides (revoke ADMIN_MANAGE)")
    void testEffectiveSuperAdmin_IgnoresOverrides() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("ADMIN_MANAGE", false);

        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.SUPER_ADMIN, overrides);

        assertTrue(perms.contains(AdminPermission.ADMIN_MANAGE),
                "SUPER_ADMIN should contain ADMIN_MANAGE even with revoke override");
        assertEquals(AdminPermission.values().length, perms.size(),
                "SUPER_ADMIN should always have all permissions regardless of overrides");
    }

    @Test
    @DisplayName("AdminPermissions.effective() — null overrides treated gracefully")
    void testEffective_NullOverrides() {
        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.ADMIN, null);

        assertEquals(AdminPermission.values().length - 1, perms.size(),
                "null overrides should not affect role base permissions");
        assertFalse(perms.contains(AdminPermission.ADMIN_MANAGE));
    }

    @Test
    @DisplayName("AdminPermissions.effective() — invalid permission name in override ignored")
    void testEffective_InvalidPermissionNameIgnored() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("INVALID_PERMISSION", true);
        overrides.put("USER_VIEW", false);

        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.ADMIN, overrides);

        assertFalse(perms.contains(AdminPermission.USER_VIEW),
                "valid override should apply");
        // INVALID_PERMISSION should be silently ignored (not throw)
    }

    @Test
    @DisplayName("AdminPermissions.effective() — mixed overrides (grant and revoke)")
    void testEffective_MixedOverrides() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("PAYMENT_RELEASE", true);  // grant (ADMIN has this, so no change)
        overrides.put("ADMIN_MANAGE", true);     // grant (ADMIN lacks this, should add)
        overrides.put("USER_VIEW", false);       // revoke

        Set<AdminPermission> perms = AdminPermissions.effective(AdminRole.ADMIN, overrides);

        assertTrue(perms.contains(AdminPermission.ADMIN_MANAGE),
                "override should grant ADMIN_MANAGE to ADMIN");
        assertFalse(perms.contains(AdminPermission.USER_VIEW),
                "override should revoke USER_VIEW from ADMIN");
    }
}
