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
 * <p>{@code SMS_OTP_REGISTERED_NUMBERS_ONLY} : vrai, un code SMS de connexion ne part que vers
 * un numéro déjà porté par un compte, ou vers le numéro qu'un compte connecté ajoute à son
 * profil ; l'inscription se fait alors par email, Google ou Apple. Parade au pompage de SMS du
 * 08/11/2026 (des dizaines de numéros inconnus, un code chacun). Faux rouvre l'inscription par
 * SMS à tout numéro. Amorcé depuis {@code YADONY_SMS_OTP_REGISTERED_NUMBERS_ONLY} (vrai par
 * défaut), puis piloté depuis le back-office ; ligne absente : vrai, la position sûre.
 *
 * <p>{@code SMS_OTP_MAX_NUMBERS_PER_ACCOUNT} : nombre de numéros différents pour lesquels un
 * même compte connecté peut demander un code SMS sur 24 h. À ce nombre, le compte est suspendu
 * automatiquement et le back-office alerté (bornes 2 à 20, défaut 3).
 *
 * <p>{@code SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL} et {@code SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL} : plafond
 * des SMS de code envoyés par toute l'app, sur 1 h et sur 24 h glissantes. Au-delà, plus aucun code
 * ne part (503, alerte Sentry) et les utilisateurs passent par email, Google ou Apple : c'est la
 * facture SMS maximale, quoi que fasse un attaquant. Amorcés depuis
 * {@code YADONY_SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL} / {@code _PER_DAY_GLOBAL} (30 et 150), bornes
 * 1 à 1000 et 1 à 10000, puis pilotés depuis le back-office.
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
 *
 * <p>{@code PAYMENTS_PAYPAL_ENABLED}, {@code PAYMENTS_APPLE_PAY_ENABLED} et
 * {@code PAYMENTS_GOOGLE_PAY_ENABLED} sont les drapeaux des moyens de paiement de la feuille
 * de paiement (YADONY-FLUTTER-PROD-11). PayPal faux : « paypal » n'est jamais ajouté aux
 * {@code payment_method_types} des PaymentIntent (Stripe refuse sinon le PaymentIntent tant que
 * la capacité {@code paypal_payments} n'est pas active). Apple Pay ou Google Pay faux :
 * l'application, qui lit {@code GET /config/payment-methods} à chaque ouverture de la feuille,
 * ne montre pas le bouton (sans certificat de traitement Apple, la feuille Apple Pay se
 * referme sur un faux « annulé »). Faux par défaut, amorcés depuis
 * {@code PAYMENTS_*_ENABLED} au premier démarrage, puis pilotés depuis le back-office sans
 * redéploiement. À n'allumer qu'une fois le moyen configuré chez Stripe (et Apple).
 */
public enum PlatformSettingKey {

    COMMISSION_RATE("commission_rate", PlatformSettingType.DECIMAL),
    URGENCY_THRESHOLD_DAYS("urgency_threshold_days", PlatformSettingType.INTEGER),
    REIMBURSEMENT_CAP_EUR("reimbursement_cap_eur", PlatformSettingType.DECIMAL),
    SMS_ENABLED("sms_enabled", PlatformSettingType.BOOLEAN),
    SMS_OTP_REGISTERED_NUMBERS_ONLY("sms_otp_registered_numbers_only", PlatformSettingType.BOOLEAN),
    SMS_OTP_MAX_NUMBERS_PER_ACCOUNT("sms_otp_max_numbers_per_account", PlatformSettingType.INTEGER),
    SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL("sms_otp_max_sends_per_hour_global", PlatformSettingType.INTEGER),
    SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL("sms_otp_max_sends_per_day_global", PlatformSettingType.INTEGER),
    PRO_ENABLED("pro_enabled", PlatformSettingType.BOOLEAN),
    KYC_DIDIT_ENABLED("kyc_didit_enabled", PlatformSettingType.BOOLEAN),
    CRITICAL_SMS_FALLBACK_ENABLED("critical_sms_fallback_enabled", PlatformSettingType.BOOLEAN),
    PHOTO_RETENTION_DAYS_AFTER_DELIVERY("photo_retention_days_after_delivery", PlatformSettingType.INTEGER),
    PHOTO_UPLOAD_DAILY_QUOTA("photo_upload_daily_quota", PlatformSettingType.INTEGER),
    TRACKING_PHOTOS_MAX_PER_BID("tracking_photos_max_per_bid", PlatformSettingType.INTEGER),
    PHOTO_PURGE_ENABLED("photo_purge_enabled", PlatformSettingType.BOOLEAN),
    FLIGHTS_ENABLED("flights_enabled", PlatformSettingType.BOOLEAN),
    ASSISTANT_DAILY_QUOTA("assistant_daily_quota", PlatformSettingType.INTEGER),
    ASSISTANT_FORM_DRAFT_DAILY_QUOTA("assistant_form_draft_daily_quota", PlatformSettingType.INTEGER),
    PAYMENTS_PAYPAL_ENABLED("payments_paypal_enabled", PlatformSettingType.BOOLEAN),
    PAYMENTS_APPLE_PAY_ENABLED("payments_apple_pay_enabled", PlatformSettingType.BOOLEAN),
    PAYMENTS_GOOGLE_PAY_ENABLED("payments_google_pay_enabled", PlatformSettingType.BOOLEAN);

    /** Numéros différents par compte et par 24 h avant suspension (bornes admin 2 à 20). */
    public static final int DEFAULT_SMS_OTP_MAX_NUMBERS_PER_ACCOUNT = 3;
    public static final int MIN_SMS_OTP_MAX_NUMBERS_PER_ACCOUNT = 2;
    public static final int MAX_SMS_OTP_MAX_NUMBERS_PER_ACCOUNT = 20;

    /** Plafond global des SMS de code (toute l'app), sur 1 h puis sur 24 h. */
    public static final int DEFAULT_SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL = 30;
    public static final int MAX_SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL = 1000;
    public static final int DEFAULT_SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL = 150;
    public static final int MAX_SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL = 10000;

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
