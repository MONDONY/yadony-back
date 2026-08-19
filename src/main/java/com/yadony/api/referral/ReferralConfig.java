package com.yadony.api.referral;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Business configuration for the referral system.
 * All limits are externalized in application.yml under {@code yadony.referral}.
 */
@ConfigurationProperties(prefix = "yadony.referral")
@Configuration
public class ReferralConfig {

    // Le montant versé au premier envoi du filleul (rewardAmountCents) n'a plus
    // d'objet depuis le lot 3 (2026-08-19/20) : le parrainage octroie un bon de
    // réduction de commission, configuré séparément sous yadony.voucher.* — voir
    // com.yadony.api.voucher.VoucherConfig.

    /** Maximum number of invitations a user may send. */
    private int maxInvitationsPerUser = 50;

    /** Minimum days between two consecutive code regenerations. */
    private int codeRegenerationCooldownDays = 30;

    public int getMaxInvitationsPerUser() { return maxInvitationsPerUser; }
    public void setMaxInvitationsPerUser(int maxInvitationsPerUser) { this.maxInvitationsPerUser = maxInvitationsPerUser; }

    public int getCodeRegenerationCooldownDays() { return codeRegenerationCooldownDays; }
    public void setCodeRegenerationCooldownDays(int codeRegenerationCooldownDays) {
        this.codeRegenerationCooldownDays = codeRegenerationCooldownDays;
    }
}
