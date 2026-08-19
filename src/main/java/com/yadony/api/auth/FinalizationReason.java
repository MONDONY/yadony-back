package com.yadony.api.auth;

public enum FinalizationReason {
    SOFT_GRACE_EXPIRED,
    HARD_IMMEDIATE,
    /** Lot C — suppression RGPD déclenchée par un administrateur depuis le back-office. */
    ADMIN_INITIATED
}
