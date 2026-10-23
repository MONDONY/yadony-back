package com.yadony.api.payments.pawapay;

import com.yadony.api.common.EncryptedStringConverter;
import com.yadony.api.common.Msisdn;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Une opération pawaPay (deposit, payout, refund). L'{@code id} est l'identifiant envoyé
 * à pawaPay : généré ici, persisté AVANT l'appel HTTP, jamais réutilisé. Pas de
 * {@code BaseEntity} : l'id est assigné (pas généré) et une opération ne se supprime
 * jamais, même logiquement. {@code @Version} nullable rend l'entité « neuve » pour
 * Spring Data, qui fait alors un {@code persist} malgré l'id déjà posé.
 */
@Entity
@Table(name = "pawapay_operations")
public class PawapayOperationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Verrou optimiste JPA — ne protège QUE les écritures faites via une entité
     * gérée (save/persist classique). {@code applyTransition} et
     * {@code markSubmittedIfStillCreated} du repository sont des bulk UPDATE
     * JPQL : Hibernate ne les fait jamais passer par le cycle de vie de
     * l'entité et n'incrémente donc jamais cette colonne pour eux. Sur ces
     * deux méthodes, la protection contre l'écrasement concurrent est portée
     * par leur clause {@code WHERE} sur {@code status} (voir le commentaire de
     * chacune dans {@code PawapayOperationRepository}), pas par cette version.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10, updatable = false)
    private PawapayOperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PawapayOperationStatus status = PawapayOperationStatus.CREATED;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "msisdn", nullable = false, length = 255)
    private String msisdn;

    @Column(name = "msisdn_masked", nullable = false, length = 20)
    private String msisdnMasked;

    /** Lien qui fait autorité vers le paiement métier (spec §7.1). */
    @Column(name = "payment_id")
    private UUID paymentId;

    /** À quoi sert l'opération : paiement de colis, recharge ou remboursement de wallet. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private PawapayOperationPurpose purpose = PawapayOperationPurpose.BID_PAYMENT;

    /**
     * Garde « une recharge en cours par utilisateur et par devise » : l'index unique partiel
     * {@code uq_pawapay_ops_live_wallet_topup} (V331) ne compte que les lignes où elle est
     * vraie. Passée à faux par {@code releaseExpiredTopupGuards} une fois le délai de
     * validation écoulé ; jamais remise à vrai.
     */
    @Column(name = "topup_guard", nullable = false)
    private boolean topupGuard = true;

    /** Utilisateur concerné pour une opération de wallet (topup / refund) ; null pour un paiement de colis. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /**
     * Supplément de prix payé, versé ou remboursé par cette opération (purpose {@code BID_SUPPLEMENT},
     * V338). Jamais avec {@code payment_id} : l'index {@code uq_pawapay_ops_live_per_payment}
     * refuserait un second dépôt sur le paiement du colis, et les écouteurs du paiement principal
     * prendraient l'opération pour la leur.
     */
    @Column(name = "bid_supplement_id", updatable = false)
    private UUID bidSupplementId;

    /**
     * Modification de colis payée, versée ou remboursée par cette opération (purpose
     * {@code BID_AMENDMENT}, V347). Jamais avec {@code payment_id}, pour les mêmes raisons que
     * {@link #bidSupplementId}.
     */
    @Column(name = "bid_amendment_id", updatable = false)
    private UUID bidAmendmentId;

    /** Pour un REFUND : le DEPOSIT d'origine. */
    @Column(name = "related_operation_id")
    private UUID relatedOperationId;

    @Column(name = "authorization_url", columnDefinition = "TEXT")
    private String authorizationUrl;

    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    // Chiffré : ce JSON transporte accountDetails.phoneNumber en clair côté
    // pawaPay (corps du callback, réponse de statut) — sans
    // ce @Convert, le chiffrement de `msisdn` juste au-dessus serait
    // décoratif puisque le même numéro ressortirait ici en clair.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "raw_callback", columnDefinition = "TEXT")
    private String rawCallback;

    @Column(name = "submitted_at") private LocalDateTime submittedAt;
    @Column(name = "callback_received_at") private LocalDateTime callbackReceivedAt;
    @Column(name = "last_polled_at") private LocalDateTime lastPolledAt;
    @Column(name = "finalized_at") private LocalDateTime finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PawapayOperationEntity() {}

    public PawapayOperationEntity(UUID id, PawapayOperationKind kind, UUID paymentId, UUID relatedOperationId,
                                  BigDecimal amount, String currency, String provider, String country,
                                  String msisdn) {
        this.id = id;
        this.kind = kind;
        this.paymentId = paymentId;
        this.relatedOperationId = relatedOperationId;
        this.amount = amount;
        this.currency = currency;
        this.provider = provider;
        this.country = country;
        this.msisdn = Msisdn.normalize(msisdn);
        this.msisdnMasked = Msisdn.mask(msisdn);
    }

    public PawapayOperationEntity(UUID id, PawapayOperationKind kind, PawapayOperationPurpose purpose, UUID userId,
                                  UUID paymentId, UUID relatedOperationId, BigDecimal amount, String currency,
                                  String provider, String country, String msisdn) {
        this(id, kind, paymentId, relatedOperationId, amount, currency, provider, country, msisdn);
        this.purpose = purpose;
        this.userId = userId;
    }

    /** Opération d'un supplément de prix : purpose {@code BID_SUPPLEMENT}, jamais de paiement de colis. */
    public static PawapayOperationEntity forSupplement(UUID id, PawapayOperationKind kind, UUID userId,
                                                       UUID bidSupplementId, UUID relatedOperationId, BigDecimal amount,
                                                       String currency, String provider, String country, String msisdn) {
        PawapayOperationEntity op = new PawapayOperationEntity(id, kind, PawapayOperationPurpose.BID_SUPPLEMENT, userId,
                null, relatedOperationId, amount, currency, provider, country, msisdn);
        op.bidSupplementId = bidSupplementId;
        return op;
    }

    /** Opération d'une modification de colis : purpose {@code BID_AMENDMENT}, jamais de paiement de colis. */
    public static PawapayOperationEntity forAmendment(UUID id, PawapayOperationKind kind, UUID userId,
                                                      UUID bidAmendmentId, UUID relatedOperationId, BigDecimal amount,
                                                      String currency, String provider, String country, String msisdn) {
        PawapayOperationEntity op = new PawapayOperationEntity(id, kind, PawapayOperationPurpose.BID_AMENDMENT, userId,
                null, relatedOperationId, amount, currency, provider, country, msisdn);
        op.bidAmendmentId = bidAmendmentId;
        return op;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() { return id; }
    public Long getVersion() { return version; }
    public PawapayOperationKind getKind() { return kind; }
    public PawapayOperationStatus getStatus() { return status; }
    public void setStatus(PawapayOperationStatus status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getProvider() { return provider; }
    public String getCountry() { return country; }
    public String getMsisdn() { return msisdn; }
    public String getMsisdnMasked() { return msisdnMasked; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getRelatedOperationId() { return relatedOperationId; }
    public PawapayOperationPurpose getPurpose() { return purpose; }
    public UUID getUserId() { return userId; }
    public UUID getBidSupplementId() { return bidSupplementId; }
    public UUID getBidAmendmentId() { return bidAmendmentId; }
    public boolean isTopupGuard() { return topupGuard; }
    public String getAuthorizationUrl() { return authorizationUrl; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public String getRawCallback() { return rawCallback; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getFinalizedAt() { return finalizedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
