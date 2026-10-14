package com.yadony.api.referral;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Business configuration for the referral system.
 * All limits are externalized in application.yml under {@code yadony.referral}.
 */
@ConfigurationProperties(prefix = "yadony.referral")
@Configuration
public class ReferralConfig {

    /**
     * Récompense « solde » : montant crédité sur le portefeuille EUR du parrain quand il
     * la choisit, après le premier envoi livré de son filleul.
     */
    private BigDecimal rewardWalletAmount = new BigDecimal("5.00");

    /** Récompense « code » : remise en pourcentage du prix payé (5 = 5 %). */
    private int rewardDiscountPercent = 5;

    /** Durée de validité du code personnel, en mois, à compter du choix. */
    private int rewardDiscountValidityMonths = 6;

    /** Maximum number of invitations a user may send. */
    private int maxInvitationsPerUser = 50;

    /** Minimum days between two consecutive code regenerations. */
    private int codeRegenerationCooldownDays = 30;

    public BigDecimal getRewardWalletAmount() { return rewardWalletAmount; }
    public void setRewardWalletAmount(BigDecimal rewardWalletAmount) { this.rewardWalletAmount = rewardWalletAmount; }

    public int getRewardDiscountPercent() { return rewardDiscountPercent; }
    public void setRewardDiscountPercent(int rewardDiscountPercent) { this.rewardDiscountPercent = rewardDiscountPercent; }

    public int getRewardDiscountValidityMonths() { return rewardDiscountValidityMonths; }
    public void setRewardDiscountValidityMonths(int rewardDiscountValidityMonths) {
        this.rewardDiscountValidityMonths = rewardDiscountValidityMonths;
    }

    public int getMaxInvitationsPerUser() { return maxInvitationsPerUser; }
    public void setMaxInvitationsPerUser(int maxInvitationsPerUser) { this.maxInvitationsPerUser = maxInvitationsPerUser; }

    public int getCodeRegenerationCooldownDays() { return codeRegenerationCooldownDays; }
    public void setCodeRegenerationCooldownDays(int codeRegenerationCooldownDays) {
        this.codeRegenerationCooldownDays = codeRegenerationCooldownDays;
    }
}
