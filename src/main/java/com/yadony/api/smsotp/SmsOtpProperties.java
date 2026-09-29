package com.yadony.api.smsotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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

    public int getMaxSendsPerWindow() { return maxSendsPerWindow; }
    public void setMaxSendsPerWindow(int maxSendsPerWindow) { this.maxSendsPerWindow = maxSendsPerWindow; }
    public int getRateWindowMinutes() { return rateWindowMinutes; }
    public void setRateWindowMinutes(int rateWindowMinutes) { this.rateWindowMinutes = rateWindowMinutes; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getOtpValidMinutes() { return otpValidMinutes; }
    public void setOtpValidMinutes(int otpValidMinutes) { this.otpValidMinutes = otpValidMinutes; }

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
}
