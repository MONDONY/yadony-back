package com.yadony.api.auth;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "users")
@Where(clause = "deleted_at IS NULL")
public class UserEntity extends BaseEntity {

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    private String firebaseUid;

    // Téléphone et email ne sont volontairement pas stockés ici : Firebase Auth en
    // est la seule source de vérité, lue à la demande par FirebaseContactService à
    // partir de firebaseUid. Un vol de la base Yadony ne les révèle donc pas.

    /**
     * Identifiant public généré à la création (« user » + horodatage), jamais vide.
     *
     * <p>Sert de repli d'affichage quand le compte n'a pas de prénom : sans lui, chaque
     * couche inventait le sien (numéro de téléphone ou email côté client, « Expéditeur »,
     * « Voyageur » ou « Utilisateur » côté serveur selon le service appelé), si bien qu'un
     * même compte portait plusieurs noms selon l'écran. Voir {@link #publicDisplayName()}.
     */
    @Column(name = "username", nullable = false, unique = true, length = 32)
    private String username;

    /**
     * Dernier rempart contre un {@code username} nul à l'insertion.
     *
     * <p>Le chemin nominal reste {@code UsernameGenerator}, appelé à l'inscription : lui seul
     * vérifie que la valeur est libre avant de la proposer. Ce repli couvre les créations qui
     * ne passent pas par lui — fixtures de test, futurs points d'entrée — pour lesquelles la
     * contrainte {@code NOT NULL} produirait sinon une erreur d'insertion opaque.
     *
     * <p>Le suffixe aléatoire n'est pas décoratif : sans lui, deux comptes créés dans la même
     * seconde produiraient la même valeur et se heurteraient à l'index unique.
     *
     * <p>Ce suffixe faisait 4 chiffres, soit 10 000 valeurs par seconde. Le paradoxe des
     * anniversaires rend la collision bien plus probable que ce chiffre ne le suggère :
     * ~12 % dès 50 insertions dans la même seconde. Une suite de tests d'intégration en crée
     * couramment davantage, et la CI est tombée sur le cas — insertion rejetée par l'index
     * unique, build rouge, déploiement bloqué. Avec 48 bits, la même rafale descend sous
     * 1 pour 10 milliards. La colonne fait 32 caractères, le format en occupe 26.
     */
    // Instance unique : réamorcer un SecureRandom à chaque insertion coûte cher et n'apporte
    // aucune entropie supplémentaire (SpotBugs DMI_RANDOM_USED_ONLY_ONCE).
    private static final java.security.SecureRandom FALLBACK_RANDOM = new java.security.SecureRandom();

    @jakarta.persistence.PrePersist
    void ensureUsername() {
        if (username == null || username.isBlank()) {
            long suffix = FALLBACK_RANDOM.nextLong() & 0xFFFF_FFFF_FFFFL;
            username = "user" + java.time.Instant.now().getEpochSecond()
                    + String.format("%012x", suffix);
        }
    }

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "kyc_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus = KycStatus.NOT_STARTED;

    @Column(name = "contact_kyc_only", nullable = false)
    private boolean contactKycOnly = true;

    /**
     * L'utilisateur refuse que son numéro soit révélé à sa contrepartie, même une
     * fois l'offre acceptée. Il reste joignable par la messagerie Yadony, seul canal
     * de contact dans ce cas. Opt-in explicite : false pour tous les comptes.
     */
    @Column(name = "hide_phone_number", nullable = false)
    private boolean hidePhoneNumber = false;

    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "cancellation_count", nullable = false)
    private int cancellationCount = 0;

    // Incidents de remise imputables à l'expéditeur (D5/D6) : no-show confirmé ou
    // annulation après remise. Compteur de réputation distinct des compteurs voyageur
    // (noShowCount/cancellationCount) — n'incrémente qu'au statut CONFIRMED (D8).
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "sender_handover_incident_count", nullable = false)
    private int senderHandoverIncidentCount = 0;

    // Suspension de publication de trajets (D4) — décidée par l'admin, n'impacte
    // pas le login. Distincte de UserStatus.SUSPENDED.
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "publishing_suspended", nullable = false)
    private boolean publishingSuspended = false;

    @Column(name = "publishing_suspended_at")
    private java.time.Instant publishingSuspendedAt;

    @Column(name = "publishing_suspended_reason", length = 255)
    private String publishingSuspendedReason;

    // Coupure de la messagerie (Lot B, modération) — échéance décidée par l'admin.
    // NULL = pas de coupure. Distincte de publishingSuspended : coupe les échanges,
    // pas la publication de trajets/annonces.
    @Column(name = "messaging_muted_until")
    private Instant messagingMutedUntil;

    @Column(name = "stripe_account_id", length = 64)
    private String stripeAccountId;

    @Column(name = "stripe_account_status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private StripeAccountStatus stripeAccountStatus = StripeAccountStatus.NOT_CREATED;

    // ── Compte de versement mobile money (rail pawaPay) ─────────────────────
    // Sur l'entité et non dans une table jointe : AnnouncementPaymentRails.availableFor
    // est appelé en mappant des listes d'annonces (fil de recherche) et lit ce champ
    // déjà chargé, comme hasActiveStripeConnect(). DEFAULT porté par @ColumnDefault pour
    // que le schéma H2 des tests (généré des entités) accepte les INSERT bruts existants.
    @Enumerated(EnumType.STRING)
    @Column(name = "mobile_money_status", nullable = false, length = 32)
    @org.hibernate.annotations.ColumnDefault("'NOT_CONFIGURED'")
    private MobileMoneyPayoutStatus mobileMoneyStatus = MobileMoneyPayoutStatus.NOT_CONFIGURED;

    /** MSISDN chiffré (AES-256-GCM randomisé) : jamais dans un WHERE, un tri ni un UNIQUE. */
    @jakarta.persistence.Convert(converter = com.yadony.api.common.EncryptedStringConverter.class)
    @Column(name = "mobile_money_msisdn", length = 255)
    private String mobileMoneyMsisdn;

    @Column(name = "mobile_money_msisdn_masked", length = 32)
    private String mobileMoneyMsisdnMasked;

    /** Code opérateur pawaPay, ex. {@code ORANGE_SEN}, {@code WAVE_CIV}. */
    @Column(name = "mobile_money_provider", length = 30)
    private String mobileMoneyProvider;

    @Column(name = "mobile_money_country", length = 2)
    private String mobileMoneyCountry;

    @Column(name = "mobile_money_currency", length = 3)
    private String mobileMoneyCurrency;

    @Column(name = "mobile_money_verified_at")
    private Instant mobileMoneyVerifiedAt;

    /** Réseaux acceptés sur le numéro de versement, codes pawaPay séparés par des virgules (V255). */
    @Column(name = "mobile_money_providers", length = 255)
    private String mobileMoneyProviders;

    @Column(name = "stripe_account_created_at")
    private Instant stripeAccountCreatedAt;

    @Column(name = "stripe_onboarding_completed_at")
    private Instant stripeOnboardingCompletedAt;

    /**
     * Dernière relance envoyée pour un onboarding Connect resté inachevé.
     * {@code null} = jamais relancé. Voir {@code StripeOnboardingReminderScheduler}.
     */
    @Column(name = "stripe_onboarding_last_reminder_at")
    private Instant stripeOnboardingLastReminderAt;

    @Column(name = "is_pro_account", nullable = false)
    private boolean isProAccount = false;

    /** Override de taux de commission Yadony (null = taux global {@code yadony.commission.rate}). */
    @Column(name = "commission_rate_override")
    private java.math.BigDecimal commissionRateOverride;

    @Column(name = "pro_company_name", length = 255)
    private String proCompanyName;

    // Chiffré au repos (AES-256-GCM) : le SIRET identifie un entrepreneur (PII RGPD).
    // Jamais utilisé dans un WHERE → chiffrement randomisé sûr.
    @Column(name = "pro_siret", length = 255)
    @jakarta.persistence.Convert(converter = com.yadony.api.common.EncryptedStringConverter.class)
    private String proSiret;

    @Column(name = "bio", length = 280)
    private String bio;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_languages", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "language", length = 32)
    private Set<String> languages = new HashSet<>();

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "residence_street", length = 255)
    private String residenceStreet;

    @Column(name = "residence_line2", length = 100)
    private String residenceLine2;

    @Column(name = "residence_postal_code", length = 20)
    private String residencePostalCode;

    @Column(name = "onboarding_seen_at")
    private Instant onboardingSeenAt;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "kilo_pro", nullable = false)
    private boolean kiloPro = false;

    @Column(name = "kilo_pro_granted_at")
    private LocalDateTime kiloProGrantedAt;

    @Column(name = "total_trips", nullable = false)
    private int totalTrips = 0;

    @Column(name = "total_shipments", nullable = false)
    private int totalShipments = 0;

    @Column(name = "no_show_count", nullable = false)
    private int noShowCount = 0;

    @Column(name = "refused_count", nullable = false)
    private int refusedCount = 0;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Version
    @Column(name = "version")
    private Long version = 0L;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "analytics_consent")
    private Boolean analyticsConsent; // null = jamais répondu

    @Column(name = "analytics_consent_at")
    private Instant analyticsConsentAt;

    @Column(name = "analytics_consent_version", length = 32)
    private String analyticsConsentVersion;

    @Column(name = "analytics_consent_source", length = 32)
    private String analyticsConsentSource;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "commission_payment_method_id", length = 255)
    private String commissionPaymentMethodId;

    @Column(name = "commission_card_brand", length = 20)
    private String commissionCardBrand;

    @Column(name = "commission_card_last4", length = 4)
    private String commissionCardLast4;

    @Column(name = "commission_card_exp_month")
    private Integer commissionCardExpMonth;

    @Column(name = "commission_card_exp_year")
    private Integer commissionCardExpYear;

    public String getFirebaseUid() { return firebaseUid; }
    public void setFirebaseUid(String firebaseUid) { this.firebaseUid = firebaseUid; }



    /**
     * Nom affiché quand le compte est introuvable — typiquement supprimé, donc invisible au
     * {@code @Where(deleted_at IS NULL)}.
     *
     * <p>Neutre à dessein : les anciens replis nommaient le <em>rôle</em> tenu dans le fil
     * (« Expéditeur », « Voyageur »), si bien qu'un compte supprimé changeait de nom d'un écran
     * à l'autre et se confondait avec un compte vivant sans prénom.
     */
    public static final String UNKNOWN_DISPLAY_NAME = "Utilisateur";

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    /**
     * Nom affiché à une contrepartie : « Prénom N. », ou le {@link #getUsername() username}
     * si le compte n'a pas de prénom.
     *
     * <p>Le nom de famille est réduit à son initiale : une contrepartie n'a pas besoin de
     * l'identité civile complète pour traiter une offre.
     *
     * <p>Ne jamais retomber ici sur le numéro de téléphone ni sur l'email — c'est ce que
     * faisaient le client et plusieurs services avant l'ajout du username, ce qui exposait
     * une coordonnée personnelle comme nom public et contredisait le réglage
     * « Masquer mon numéro ».
     */
    public String publicDisplayName() {
        if (firstName != null && !firstName.isBlank()) {
            if (lastName != null && !lastName.isBlank()) {
                return firstName + " " + lastName.charAt(0) + ".";
            }
            return firstName;
        }
        return username;
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public boolean isContactKycOnly() { return contactKycOnly; }
    public void setContactKycOnly(boolean contactKycOnly) { this.contactKycOnly = contactKycOnly; }

    public boolean isHidePhoneNumber() { return hidePhoneNumber; }
    public void setHidePhoneNumber(boolean hidePhoneNumber) { this.hidePhoneNumber = hidePhoneNumber; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    public int getCancellationCount() { return cancellationCount; }
    public void setCancellationCount(int cancellationCount) { this.cancellationCount = cancellationCount; }

    public int getSenderHandoverIncidentCount() { return senderHandoverIncidentCount; }
    public void setSenderHandoverIncidentCount(int senderHandoverIncidentCount) { this.senderHandoverIncidentCount = senderHandoverIncidentCount; }

    public boolean isPublishingSuspended() { return publishingSuspended; }
    public void setPublishingSuspended(boolean publishingSuspended) { this.publishingSuspended = publishingSuspended; }

    public java.time.Instant getPublishingSuspendedAt() { return publishingSuspendedAt; }
    public void setPublishingSuspendedAt(java.time.Instant publishingSuspendedAt) { this.publishingSuspendedAt = publishingSuspendedAt; }

    public String getPublishingSuspendedReason() { return publishingSuspendedReason; }
    public void setPublishingSuspendedReason(String publishingSuspendedReason) { this.publishingSuspendedReason = publishingSuspendedReason; }

    public Instant getMessagingMutedUntil() { return messagingMutedUntil; }
    public void setMessagingMutedUntil(Instant messagingMutedUntil) { this.messagingMutedUntil = messagingMutedUntil; }

    /** Vrai si la messagerie est coupée à l'instant donné. */
    public boolean isMessagingMuted(Instant now) {
        return messagingMutedUntil != null && messagingMutedUntil.isAfter(now);
    }

    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }

    public StripeAccountStatus getStripeAccountStatus() { return stripeAccountStatus; }
    public void setStripeAccountStatus(StripeAccountStatus stripeAccountStatus) { this.stripeAccountStatus = stripeAccountStatus; }

    /**
     * Vrai si ce compte a un compte Stripe Connect actif (onboarding terminé,
     * {@code charges_enabled = true}). Source unique de cette condition : évite de
     * répéter {@code getStripeAccountStatus() == StripeAccountStatus.ONBOARDING_COMPLETE}
     * à chaque site qui doit savoir si l'utilisateur peut recevoir un versement Connect
     * (ex. {@code AnnouncementPaymentRails.availableFor}).
     */
    public boolean hasActiveStripeConnect() {
        return stripeAccountStatus == StripeAccountStatus.ONBOARDING_COMPLETE;
    }

    /**
     * Vrai si l'utilisateur peut recevoir un versement mobile money. Source unique de la
     * condition, jumelle de {@link #hasActiveStripeConnect()}.
     */
    public boolean hasActiveMobileMoney() {
        return mobileMoneyStatus == MobileMoneyPayoutStatus.ACTIVE;
    }

    /**
     * Compte de versement actif ET dans la devise donnée (celle de l'annonce ou du paiement) :
     * la seule combinaison qui permet un payout. Source unique de cette règle pour les trois
     * portails qui la vérifient (création du bid, acceptation, versement) — un compte
     * réactivé dans une autre devise entre deux portails doit être refusé partout pareil.
     */
    public boolean canReceiveMobileMoney(String currency) {
        return hasActiveMobileMoney() && mobileMoneyCurrency != null && mobileMoneyCurrency.equalsIgnoreCase(currency);
    }

    public MobileMoneyPayoutStatus getMobileMoneyStatus() { return mobileMoneyStatus; }
    public void setMobileMoneyStatus(MobileMoneyPayoutStatus mobileMoneyStatus) { this.mobileMoneyStatus = mobileMoneyStatus; }
    public String getMobileMoneyMsisdn() { return mobileMoneyMsisdn; }
    public void setMobileMoneyMsisdn(String mobileMoneyMsisdn) { this.mobileMoneyMsisdn = mobileMoneyMsisdn; }
    public String getMobileMoneyMsisdnMasked() { return mobileMoneyMsisdnMasked; }
    public void setMobileMoneyMsisdnMasked(String mobileMoneyMsisdnMasked) { this.mobileMoneyMsisdnMasked = mobileMoneyMsisdnMasked; }
    public String getMobileMoneyProvider() { return mobileMoneyProvider; }
    public void setMobileMoneyProvider(String mobileMoneyProvider) { this.mobileMoneyProvider = mobileMoneyProvider; }
    public String getMobileMoneyCountry() { return mobileMoneyCountry; }
    public void setMobileMoneyCountry(String mobileMoneyCountry) { this.mobileMoneyCountry = mobileMoneyCountry; }
    public String getMobileMoneyCurrency() { return mobileMoneyCurrency; }
    public void setMobileMoneyCurrency(String mobileMoneyCurrency) { this.mobileMoneyCurrency = mobileMoneyCurrency; }
    public Instant getMobileMoneyVerifiedAt() { return mobileMoneyVerifiedAt; }
    public void setMobileMoneyVerifiedAt(Instant mobileMoneyVerifiedAt) { this.mobileMoneyVerifiedAt = mobileMoneyVerifiedAt; }

    public String getMobileMoneyProviders() { return mobileMoneyProviders; }
    public void setMobileMoneyProviders(String mobileMoneyProviders) { this.mobileMoneyProviders = mobileMoneyProviders; }

    /** Réseaux acceptés dans l'ordre enregistré ; liste vide quand la colonne est nulle ou blanche. */
    public List<String> getMobileMoneyProviderList() {
        if (mobileMoneyProviders == null || mobileMoneyProviders.isBlank()) {
            return List.of();
        }
        return Arrays.stream(mobileMoneyProviders.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Remplace la liste ; {@code null} ou vide efface la colonne. */
    public void setMobileMoneyProviderList(List<String> providers) {
        this.mobileMoneyProviders = providers == null || providers.isEmpty() ? null : String.join(",", providers);
    }

    public Instant getStripeAccountCreatedAt() { return stripeAccountCreatedAt; }
    public void setStripeAccountCreatedAt(Instant stripeAccountCreatedAt) { this.stripeAccountCreatedAt = stripeAccountCreatedAt; }

    public Instant getStripeOnboardingCompletedAt() { return stripeOnboardingCompletedAt; }
    public void setStripeOnboardingCompletedAt(Instant stripeOnboardingCompletedAt) { this.stripeOnboardingCompletedAt = stripeOnboardingCompletedAt; }

    public Instant getStripeOnboardingLastReminderAt() { return stripeOnboardingLastReminderAt; }
    public void setStripeOnboardingLastReminderAt(Instant stripeOnboardingLastReminderAt) { this.stripeOnboardingLastReminderAt = stripeOnboardingLastReminderAt; }

    public boolean isProAccount() { return isProAccount; }
    public void setProAccount(boolean proAccount) { isProAccount = proAccount; }

    public java.math.BigDecimal getCommissionRateOverride() { return commissionRateOverride; }
    public void setCommissionRateOverride(java.math.BigDecimal commissionRateOverride) { this.commissionRateOverride = commissionRateOverride; }

    public String getProCompanyName() { return proCompanyName; }
    public void setProCompanyName(String proCompanyName) { this.proCompanyName = proCompanyName; }

    public String getProSiret() { return proSiret; }
    public void setProSiret(String proSiret) { this.proSiret = proSiret; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }


    public Set<String> getLanguages() { return languages; }
    public void setLanguages(Set<String> languages) { this.languages = languages; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getResidenceStreet() { return residenceStreet; }
    public void setResidenceStreet(String residenceStreet) { this.residenceStreet = residenceStreet; }

    public String getResidenceLine2() { return residenceLine2; }
    public void setResidenceLine2(String residenceLine2) { this.residenceLine2 = residenceLine2; }

    public String getResidencePostalCode() { return residencePostalCode; }
    public void setResidencePostalCode(String residencePostalCode) { this.residencePostalCode = residencePostalCode; }

    public Instant getOnboardingSeenAt() { return onboardingSeenAt; }
    public void setOnboardingSeenAt(Instant onboardingSeenAt) { this.onboardingSeenAt = onboardingSeenAt; }

    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }

    public boolean isKiloPro() { return kiloPro; }
    public void setKiloPro(boolean kiloPro) { this.kiloPro = kiloPro; }

    public LocalDateTime getKiloProGrantedAt() { return kiloProGrantedAt; }
    public void setKiloProGrantedAt(LocalDateTime kiloProGrantedAt) { this.kiloProGrantedAt = kiloProGrantedAt; }

    public int getTotalTrips() { return totalTrips; }
    public void setTotalTrips(int totalTrips) { this.totalTrips = totalTrips; }

    public int getTotalShipments() { return totalShipments; }
    public void setTotalShipments(int totalShipments) { this.totalShipments = totalShipments; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }

    public int getRefusedCount() { return refusedCount; }
    public void setRefusedCount(int refusedCount) { this.refusedCount = refusedCount; }

    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getDeletionRequestedAt() { return deletionRequestedAt; }
    public void setDeletionRequestedAt(Instant deletionRequestedAt) { this.deletionRequestedAt = deletionRequestedAt; }

    public Boolean getAnalyticsConsent() { return analyticsConsent; }
    public void setAnalyticsConsent(Boolean analyticsConsent) { this.analyticsConsent = analyticsConsent; }

    public Instant getAnalyticsConsentAt() { return analyticsConsentAt; }
    public void setAnalyticsConsentAt(Instant analyticsConsentAt) { this.analyticsConsentAt = analyticsConsentAt; }

    public String getAnalyticsConsentVersion() { return analyticsConsentVersion; }
    public void setAnalyticsConsentVersion(String analyticsConsentVersion) { this.analyticsConsentVersion = analyticsConsentVersion; }

    public String getAnalyticsConsentSource() { return analyticsConsentSource; }
    public void setAnalyticsConsentSource(String analyticsConsentSource) { this.analyticsConsentSource = analyticsConsentSource; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getCommissionPaymentMethodId() { return commissionPaymentMethodId; }
    public void setCommissionPaymentMethodId(String commissionPaymentMethodId) { this.commissionPaymentMethodId = commissionPaymentMethodId; }

    public String getCommissionCardBrand() { return commissionCardBrand; }
    public void setCommissionCardBrand(String commissionCardBrand) { this.commissionCardBrand = commissionCardBrand; }

    public String getCommissionCardLast4() { return commissionCardLast4; }
    public void setCommissionCardLast4(String commissionCardLast4) { this.commissionCardLast4 = commissionCardLast4; }

    public Integer getCommissionCardExpMonth() { return commissionCardExpMonth; }
    public void setCommissionCardExpMonth(Integer commissionCardExpMonth) { this.commissionCardExpMonth = commissionCardExpMonth; }

    public Integer getCommissionCardExpYear() { return commissionCardExpYear; }
    public void setCommissionCardExpYear(Integer commissionCardExpYear) { this.commissionCardExpYear = commissionCardExpYear; }
}
