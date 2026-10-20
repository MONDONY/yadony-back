package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte de versement mobile money d'un voyageur : un compte actif par utilisateur et par code
 * opérateur pawaPay (opérateur + pays). Soft delete uniquement (V343).
 */
@Entity
@Table(name = "mobile_money_payout_accounts")
@SQLRestriction("deleted_at IS NULL")
public class MobileMoneyPayoutAccountEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Code opérateur pawaPay, ex. {@code ORANGE_CIV}. */
    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** MSISDN chiffré (AES-256-GCM randomisé) : jamais dans un WHERE, un tri, un log ni une réponse. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "msisdn", nullable = false, length = 255)
    private String msisdn;

    @Column(name = "msisdn_masked", nullable = false, length = 32)
    private String msisdnMasked;

    /** Vrai pour le compte repris du compte unique historique (repli de versement). */
    @Column(name = "migrated", nullable = false)
    private boolean migrated;

    /** Réseaux cochés en plus sur l'ancien compte, CSV ; nul hors reprise. */
    @Column(name = "legacy_accepted_providers", length = 255)
    private String legacyAcceptedProviders;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    protected MobileMoneyPayoutAccountEntity() {}

    public MobileMoneyPayoutAccountEntity(UUID userId, String provider, String country, String currency,
                                          String msisdn, String msisdnMasked, Instant verifiedAt) {
        this.userId = userId;
        this.provider = provider;
        this.country = country;
        this.currency = currency;
        this.msisdn = msisdn;
        this.msisdnMasked = msisdnMasked;
        this.verifiedAt = verifiedAt;
    }

    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getCountry() { return country; }
    public String getCurrency() { return currency; }
    public String getMsisdn() { return msisdn; }
    public String getMsisdnMasked() { return msisdnMasked; }
    public boolean isMigrated() { return migrated; }
    public String getLegacyAcceptedProviders() { return legacyAcceptedProviders; }
    public Instant getVerifiedAt() { return verifiedAt; }

    // Opérateur, pays et devise sont figés à la création ; migrated et legacyAcceptedProviders ne
    // sont écrits que par la reprise V343. Seul le numéro d'un compte change.
    public void setMsisdn(String msisdn) { this.msisdn = msisdn; }
    public void setMsisdnMasked(String msisdnMasked) { this.msisdnMasked = msisdnMasked; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
}
