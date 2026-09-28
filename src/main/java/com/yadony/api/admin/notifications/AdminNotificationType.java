package com.yadony.api.admin.notifications;

import com.yadony.api.admin.account.AdminPermission;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Sources du fil des nouveautés du panel, chacune lisible sous une permission : un administrateur
 * ne voit dans la cloche que ce que ses permissions lui ouvrent déjà dans le menu.
 */
public enum AdminNotificationType {
    REPORT_CREATED(AdminPermission.REPORT_VIEW),
    SUPPORT_TICKET_CREATED(AdminPermission.SUPPORT_TICKET_VIEW),
    SUPPORT_MESSAGE_RECEIVED(AdminPermission.SUPPORT_TICKET_VIEW),
    DISPUTE_OPENED(AdminPermission.DISPUTE_VIEW),
    NOSHOW_PENDING(AdminPermission.DISPUTE_VIEW),
    KYC_IN_REVIEW(AdminPermission.USER_KYC),
    PAYOUT_HELD(AdminPermission.PAYMENT_VIEW),
    WALLET_REFUND_REQUESTED(AdminPermission.PAYMENT_VIEW),
    GDPR_REQUESTED(AdminPermission.USER_GDPR_DELETE),
    ADMIN_ALERT(AdminPermission.ALERT_VIEW);

    private final AdminPermission permission;

    AdminNotificationType(AdminPermission permission) {
        this.permission = permission;
    }

    public AdminPermission permission() {
        return permission;
    }

    /** Sources lisibles avec ces permissions, dans l'ordre de déclaration. */
    public static List<AdminNotificationType> visibleTo(Set<AdminPermission> permissions) {
        return Arrays.stream(values()).filter(t -> permissions.contains(t.permission)).toList();
    }
}
