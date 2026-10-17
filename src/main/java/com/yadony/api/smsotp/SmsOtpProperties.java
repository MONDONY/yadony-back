package com.yadony.api.smsotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConfigurationProperties(prefix = "yadony.sms")
public class SmsOtpProperties {

    /**
     * Nombre de codes envoyables à un même numéro par fenêtre glissante.
     *
     * <p>Même contrainte que pour l'email : l'écran de saisie du code est partagé
     * entre les deux canaux et rouvre « Renvoyer le code » toutes les 60 s. Un
     * budget inférieur à {@link #rateWindowMinutes} envois ferait refuser un
     * renvoi que le bouton venait de proposer.
     */
    private int maxSendsPerWindow = 5;

    /** Durée, en minutes, de la fenêtre glissante appliquée à {@link #maxSendsPerWindow}. */
    private int rateWindowMinutes = 5;

    /**
     * Codes erronés tolérés par numéro sur la durée de validité d'un code.
     *
     * <p>Compté par numéro et non par code : sinon chaque renvoi offrirait un
     * budget d'essais neuf et la limite ne protégerait plus de rien.
     */
    private int maxAttempts = 5;

    /** Durée de validité d'un code, en minutes. */
    private int otpValidMinutes = 10;

    /** Codes sur 24 h glissantes avant de passer à un SMS par heure (voir le canal email). */
    private int maxSendsPerDay = 10;

    /**
     * Connexions réussies tolérées par numéro sur {@link #loginWindowMinutes}, même avec
     * un code valide. Au-delà, ni envoi ni vérification : un numéro qui se reconnecte en
     * boucle signale un script ou un appareil compromis, pas un usage normal.
     */
    private int maxLoginsPerWindow = 3;

    /** Fenêtre glissante, en minutes, de {@link #maxLoginsPerWindow}. */
    private int loginWindowMinutes = 10;

    /**
     * Indicatifs pays (sans « + ») vers lesquels un code peut partir. Parade au SMS
     * pumping : des robots demandent des codes vers des numéros surtaxés d'autres pays,
     * et l'opérateur complice reverse une part de chaque SMS facturé. Vide = tous les
     * pays (dev, tests) ; la prod fixe la liste des marchés servis.
     */
    private List<String> allowedCountryCodes = new ArrayList<>();

    /**
     * Préfixes refusés même si leur indicatif est autorisé, vérifiés en premier.
     * L'indicatif 1 couvre les États-Unis et le Canada, mais aussi les Caraïbes
     * (+1 876 Jamaïque, +1 809 République dominicaine…), destinations classiques du
     * pumping : autoriser « 1 » sans ces exclusions rouvrait la porte.
     */
    private List<String> deniedPrefixes = new ArrayList<>();

    /** SMS de code envoyés par heure, tous numéros confondus. Au-delà : 503 et alerte. */
    private int maxSendsPerHourGlobal = 200;

    /** Même budget sur 24 h : un pompage lent passerait sous la limite horaire. */
    private int maxSendsPerDayGlobal = 1000;

    /** Empreinte SMS Retriever : 11 caractères de l'alphabet base64. */
    private static final Pattern APP_HASH = Pattern.compile("[A-Za-z0-9+/]{11}");

    /**
     * Empreintes de l'app Android (API SMS Retriever), une par certificat de signature :
     * debug, et clé de signature Play pour les builds distribués par le Store.
     *
     * <p>Google ne remet un SMS à l'app que s'il contient son empreinte : le code se remplit
     * alors sans que l'utilisateur ouvre Messages. Fixées ici et jamais reçues du client :
     * une empreinte fournie par l'app laisserait une app malveillante, installée sur le
     * téléphone d'une victime, lire le code d'un compte qui n'est pas le sien.
     */
    private List<String> androidAppHashes = new ArrayList<>();

    /** Entrée « +E164:code » d'un compte de revue ; le code fait 6 chiffres. */
    private static final Pattern REVIEW_ACCOUNT = Pattern.compile("(\\+\\d{6,15}):(\\d{6})");

    /**
     * Comptes de démonstration pour la review App Store / Play, au format « +E164:code ».
     *
     * <p>Le code passe par Twilio Verify, qui ignore les numéros de test de la console
     * Firebase : sans cette liste, aucun relecteur ne peut se connecter. Pour ces numéros,
     * aucun SMS ne part et le code fixe est accepté ; les autres limites (tentatives,
     * connexions, envois) restent appliquées. Vide par défaut, à vider après la review :
     * c'est une connexion sans possession du téléphone.
     */
    private List<String> reviewAccounts = new ArrayList<>();

    public int getMaxSendsPerWindow() { return maxSendsPerWindow; }
    public void setMaxSendsPerWindow(int maxSendsPerWindow) { this.maxSendsPerWindow = maxSendsPerWindow; }
    public int getRateWindowMinutes() { return rateWindowMinutes; }
    public void setRateWindowMinutes(int rateWindowMinutes) { this.rateWindowMinutes = rateWindowMinutes; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getOtpValidMinutes() { return otpValidMinutes; }
    public void setOtpValidMinutes(int otpValidMinutes) { this.otpValidMinutes = otpValidMinutes; }
    public int getMaxSendsPerDay() { return maxSendsPerDay; }
    public void setMaxSendsPerDay(int maxSendsPerDay) { this.maxSendsPerDay = maxSendsPerDay; }
    public int getMaxLoginsPerWindow() { return maxLoginsPerWindow; }
    public void setMaxLoginsPerWindow(int maxLoginsPerWindow) { this.maxLoginsPerWindow = maxLoginsPerWindow; }
    public int getLoginWindowMinutes() { return loginWindowMinutes; }
    public void setLoginWindowMinutes(int loginWindowMinutes) { this.loginWindowMinutes = loginWindowMinutes; }
    public int getMaxSendsPerHourGlobal() { return maxSendsPerHourGlobal; }
    public void setMaxSendsPerHourGlobal(int maxSendsPerHourGlobal) { this.maxSendsPerHourGlobal = maxSendsPerHourGlobal; }
    public int getMaxSendsPerDayGlobal() { return maxSendsPerDayGlobal; }
    public void setMaxSendsPerDayGlobal(int maxSendsPerDayGlobal) { this.maxSendsPerDayGlobal = maxSendsPerDayGlobal; }

    /** Indicatifs nettoyés (espaces, « + » retirés), sans doublon. */
    public List<String> getAllowedCountryCodes() {
        return clean(allowedCountryCodes);
    }

    public void setAllowedCountryCodes(List<String> allowedCountryCodes) {
        this.allowedCountryCodes = allowedCountryCodes == null ? new ArrayList<>() : allowedCountryCodes;
    }

    public List<String> getDeniedPrefixes() {
        return clean(deniedPrefixes);
    }

    public void setDeniedPrefixes(List<String> deniedPrefixes) {
        this.deniedPrefixes = deniedPrefixes == null ? new ArrayList<>() : deniedPrefixes;
    }

    /**
     * Vrai si le numéro E.164 n'a aucun préfixe refusé et commence par un indicatif
     * autorisé (ou si la liste d'autorisés est vide).
     */
    public boolean isCountryAllowed(String e164) {
        if (getDeniedPrefixes().stream().anyMatch(prefix -> e164.startsWith("+" + prefix))) {
            return false;
        }
        List<String> allowed = getAllowedCountryCodes();
        return allowed.isEmpty() || allowed.stream().anyMatch(code -> e164.startsWith("+" + code));
    }

    private static List<String> clean(List<String> codes) {
        return codes.stream()
                .map(code -> code.trim().replace("+", ""))
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    /** Empreintes au bon format seulement ; une valeur mal copiée est écartée. */
    public List<String> getAndroidAppHashes() {
        return androidAppHashes.stream()
                .map(String::trim)
                .filter(hash -> APP_HASH.matcher(hash).matches())
                .distinct()
                .toList();
    }

    /** Valeurs configurées mais écartées, pour signaler une empreinte mal copiée. */
    public List<String> getRejectedAndroidAppHashes() {
        return androidAppHashes.stream()
                .map(String::trim)
                .filter(hash -> !hash.isEmpty() && !APP_HASH.matcher(hash).matches())
                .toList();
    }

    public void setAndroidAppHashes(List<String> androidAppHashes) {
        this.androidAppHashes = androidAppHashes == null ? new ArrayList<>() : androidAppHashes;
    }

    /** Code fixe du compte de revue pour ce numéro ; vide si ce n'en est pas un. */
    public Optional<String> reviewCodeFor(String e164) {
        return reviewAccounts.stream()
                .map(entry -> REVIEW_ACCOUNT.matcher(entry.trim()))
                .filter(Matcher::matches)
                .filter(matcher -> matcher.group(1).equals(e164))
                .map(matcher -> matcher.group(2))
                .findFirst();
    }

    public void setReviewAccounts(List<String> reviewAccounts) {
        this.reviewAccounts = reviewAccounts == null ? new ArrayList<>() : reviewAccounts;
    }
}
