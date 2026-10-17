package com.yadony.api.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Limites de débit appliquées par l'application, en complément de nginx.
 *
 * <p>nginx compte par IP, et derrière le CGNAT d'un opérateur mobile africain des
 * centaines d'abonnés partagent une seule adresse publique : sa limite reste donc
 * large (anti-flood). Ici, la clé est le <b>compte</b> (uid Firebase) pour tout ce qui
 * est authentifié : un abus est freiné sans pénaliser les voisins du même opérateur.
 * Seuls les points d'entrée OTP, appelés avant toute connexion, comptent par IP.
 *
 * <p>Les chemins sont relatifs au context-path {@code /api/v1}. Les valeurs par défaut
 * se surchargent par {@code yadony.rate-limit.rules.<nom>.limit|window}.
 */
public enum RateLimitRule {

    /**
     * Garde-fou général par compte. L'app tire une dizaine d'appels au lancement puis à
     * chaque écran : 300/min (5/s soutenus) laisse une navigation rapide intacte et borne
     * un script qui tournerait avec un jeton volé.
     */
    ACCOUNT(Key.ACCOUNT, Set.of(), List.of("/**"), 300, Duration.ofMinutes(1)),

    /**
     * Actions de paiement (Stripe, Stripe Connect, pawaPay, solde) par compte. Chacune
     * appelle un prestataire facturé ou crée une intention de paiement : 20/min couvre
     * une hésitation entre deux moyens de paiement, pas un test de cartes volées.
     */
    PAYMENT_MINUTE(Key.ACCOUNT, Methods.WRITE, PaymentPaths.PATTERNS, 20, Duration.ofMinutes(1)),

    /** Même périmètre sur une heure : un script lent passe sous la limite à la minute. */
    PAYMENT_HOUR(Key.ACCOUNT, Methods.WRITE, PaymentPaths.PATTERNS, 100, Duration.ofHours(1)),

    /**
     * Création de session KYC : chaque appel ouvre une session chez Didit, facturée.
     * Un utilisateur légitime en ouvre une ou deux.
     */
    KYC_SESSION(Key.ACCOUNT, Methods.WRITE, List.of("/kyc/session"), 10, Duration.ofHours(1)),

    /**
     * Devis d'une demande : il valide aussi les codes promo, et sans limite il sert
     * d'oracle pour deviner des codes valides.
     */
    PROMO_QUOTE(Key.ACCOUNT, Set.of("POST"), List.of("/bids/quote"), 60, Duration.ofMinutes(1)),

    /** Saisie d'un code de parrainage : même risque d'oracle que les codes promo. */
    REFERRAL_REDEEM(Key.ACCOUNT, Set.of("POST"), List.of("/referral/redeem"), 10, Duration.ofHours(1)),

    /**
     * Envois de code OTP depuis une même IP, toutes adresses et tous numéros confondus.
     * La limite par adresse existe déjà dans les services OTP ; celle-ci freine
     * l'arrosage de milliers de destinataires (SMS pumping, saturation de la boîte
     * d'envoi). Large à cause du CGNAT : 30 connexions par 10 min et par IP.
     */
    OTP_SEND_IP(Key.IP, Set.of("POST"),
            List.of("/auth/email-otp/send", "/auth/sms-otp/send"), 30, Duration.ofMinutes(10)),

    /**
     * Vérifications de code depuis une même IP. Le budget par adresse (5 essais) borne
     * la force brute sur UN compte ; celle-ci borne l'essai d'un même code sur des
     * milliers d'adresses.
     */
    OTP_VERIFY_IP(Key.IP, Set.of("POST"),
            List.of("/auth/email-otp/verify", "/auth/sms-otp/verify",
                    "/auth/email-otp/attach", "/auth/sms-otp/attach"),
            60, Duration.ofMinutes(10));

    /** Ce qui identifie le demandeur pour une règle. */
    enum Key { ACCOUNT, IP }

    private final Key key;
    private final Set<String> methods;
    private final List<String> patterns;
    private final int defaultLimit;
    private final Duration defaultWindow;

    RateLimitRule(Key key, Set<String> methods, List<String> patterns,
                  int defaultLimit, Duration defaultWindow) {
        this.key = key;
        this.methods = methods;
        this.patterns = patterns;
        this.defaultLimit = defaultLimit;
        this.defaultWindow = defaultWindow;
    }

    Key key() { return key; }

    /** Méthodes HTTP concernées ; vide = toutes. */
    Set<String> methods() { return methods; }

    List<String> patterns() { return patterns; }

    int defaultLimit() { return defaultLimit; }

    Duration defaultWindow() { return defaultWindow; }

    /** Nom de propriété : {@code PAYMENT_MINUTE} → {@code payment-minute}. */
    String propertyName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static final class Methods {
        static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    }

    /**
     * Points d'entrée qui déclenchent un paiement ou touchent un compte de paiement.
     * Les webhooks et callbacks des prestataires n'y figurent pas : ils sont exclus de
     * l'intercepteur (leur signature les authentifie, un 429 y perdrait un événement).
     */
    private static final class PaymentPaths {
        static final List<String> PATTERNS = List.of(
                "/payments/connect/**",
                "/payments",
                "/payments/me/ephemeral-key",
                "/payments/intents/*/save-payment-method",
                "/payments/mobile-money/**",
                "/wallet/topup",
                "/wallet/topup/**",
                "/wallet/*/refund-request",
                "/auth/me/wallet-refund-request",
                "/bids/checkout",
                "/bids/*/confirm-payment",
                "/bids/*/negotiation/checkout",
                "/bids/*/mobile-money/**",
                "/bids/*/accept-with-commission",
                "/bids/*/confirm-acceptance",
                "/negotiations/*/checkout",
                "/negotiations/*/settle-commission",
                "/negotiations/*/confirm-commission",
                "/negotiations/*/decline-commission",
                "/negotiations/*/initiate-payment",
                "/negotiations/*/mobile-money/**",
                "/traveler/commission-method/**",
                "/billing/checkout-session",
                "/billing/portal-session");
    }
}
