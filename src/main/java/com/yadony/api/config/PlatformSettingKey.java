package com.yadony.api.config;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

/**
 * Les parametres plateforme editables.
 *
 * <p>⚠️ {@code URGENCY_THRESHOLD_DAYS} est bien en JOURS : la property historique est
 * {@code yadony.urgency.threshold-days} (defaut 3) et le contrat public expose
 * {@code {"thresholdDays": 3}}. Renommer en heures multiplierait le seuil par 24.
 *
 * <p>{@code PRO_ENABLED} est le feature flag de l'offre PRO : faux, l'application mobile
 * masque toute entree PRO ET le serveur cesse d'appliquer les quotas reserves aux comptes
 * standard (il n'y a plus rien a vendre pour les depasser). Il permet de livrer
 * l'application avant l'ouverture commerciale du PRO, puis d'allumer l'offre depuis le
 * back-office sans redeploiement.
 *
 * <p>{@code KYC_DIDIT_ENABLED} choisit le fournisseur des NOUVELLES verifications d'identite :
 * faux, Stripe Identity ; vrai, Didit. Les sessions deja ouvertes continuent d'etre relues
 * chez le fournisseur qui les a produites, quel que soit ce reglage. Cle temporaire, a
 * supprimer avec l'implementation Stripe une fois Didit eprouve.
 *
 * <p>{@code CRITICAL_SMS_FALLBACK_ENABLED} controle UNIQUEMENT le SMS de repli envoye par
 * {@code SmsFallbackScheduler} quand une notification push critique (remise a J-2,
 * livraison confirmee, versement libere, litige ouvert, trajet reporte) reste sans accuse
 * de reception 60 secondes — chaque envoi est facture. Distinct de {@code SMS_ENABLED}, qui
 * coupe en plus les codes OTP de connexion : desactiver celui-ci coupe UNIQUEMENT ce repli,
 * jamais l'authentification. Faux par defaut : a activer depuis le back-office au besoin.
 *
 * <p>{@code PHOTO_PURGE_ENABLED} : faux, la purge des photos (après livraison, orphelins,
 * historique) tourne à blanc et ne supprime rien de R2. Le premier passage réel est
 * irréversible : on l'allume après avoir lu le décompte à blanc dans le panel.
 *
 * <p>{@code FLIGHTS_ENABLED} est le feature flag des billets d'avion en affiliation
 * (package {@code flights}) : faux, toutes les routes {@code /flights/**} et
 * {@code /me/flight-alerts} répondent 503 {@code flights-unavailable}, le scheduler des
 * alertes ne fait rien et l'application garde sa barre de navigation d'avant (sans
 * l'onglet Billets). Faux par défaut : on l'allume depuis le back-office, sans
 * redéploiement, une fois le compte Travelpayouts prêt.
 */
public enum PlatformSettingKey {

    COMMISSION_RATE("commission_rate", PlatformSettingType.DECIMAL),
    URGENCY_THRESHOLD_DAYS("urgency_threshold_days", PlatformSettingType.INTEGER),
    REIMBURSEMENT_CAP_EUR("reimbursement_cap_eur", PlatformSettingType.DECIMAL),
    SMS_ENABLED("sms_enabled", PlatformSettingType.BOOLEAN),
    PRO_ENABLED("pro_enabled", PlatformSettingType.BOOLEAN),
    KYC_DIDIT_ENABLED("kyc_didit_enabled", PlatformSettingType.BOOLEAN),
    CRITICAL_SMS_FALLBACK_ENABLED("critical_sms_fallback_enabled", PlatformSettingType.BOOLEAN),
    PHOTO_RETENTION_DAYS_AFTER_DELIVERY("photo_retention_days_after_delivery", PlatformSettingType.INTEGER),
    PHOTO_UPLOAD_DAILY_QUOTA("photo_upload_daily_quota", PlatformSettingType.INTEGER),
    TRACKING_PHOTOS_MAX_PER_BID("tracking_photos_max_per_bid", PlatformSettingType.INTEGER),
    PHOTO_PURGE_ENABLED("photo_purge_enabled", PlatformSettingType.BOOLEAN),
    FLIGHTS_ENABLED("flights_enabled", PlatformSettingType.BOOLEAN),
    ASSISTANT_DAILY_QUOTA("assistant_daily_quota", PlatformSettingType.INTEGER),
    ASSISTANT_FORM_DRAFT_DAILY_QUOTA("assistant_form_draft_daily_quota", PlatformSettingType.INTEGER);

    /** Défauts des réglages photos : aucune property, le panel admin est le seul levier. */
    public static final int DEFAULT_PHOTO_RETENTION_DAYS = 7;
    public static final int DEFAULT_PHOTO_UPLOAD_DAILY_QUOTA = 60;
    /** Photos de suivi par bid sur 24 h glissantes (bornes admin 1 à 20). */
    public static final int DEFAULT_TRACKING_PHOTOS_MAX_PER_BID = 12;
    /**
     * Quotas de l'assistant IA, par utilisateur et par jour UTC : questions d'aide, et tours de
     * remplissage des formulaires trajet et demande d'envoi. Amorcés depuis ASSISTANT_DAILY_QUOTA et
     * ASSISTANT_FORM_DRAFT_DAILY_QUOTA, puis réglés depuis le panel admin (bornes 1 à 1000).
     */
    public static final int DEFAULT_ASSISTANT_DAILY_QUOTA = 20;
    public static final int DEFAULT_ASSISTANT_FORM_DRAFT_DAILY_QUOTA = 30;

    private final String key;
    private final PlatformSettingType type;

    PlatformSettingKey(String key, PlatformSettingType type) {
        this.key = key;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public PlatformSettingType type() {
        return type;
    }

    public static PlatformSettingKey fromKey(String key) {
        return Arrays.stream(values())
                .filter(k -> k.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "platform-setting-unknown", "Unprocessable Entity",
                        "Parametre plateforme inconnu : " + key));
    }
}
