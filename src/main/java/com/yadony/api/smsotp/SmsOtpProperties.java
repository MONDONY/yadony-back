package com.yadony.api.smsotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yadony.sms")
public class SmsOtpProperties {

    private String otpTemplate = "Ton code Yadony est : %s. Valable 10 minutes.";

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

    public String getOtpTemplate() { return otpTemplate; }
    public void setOtpTemplate(String otpTemplate) { this.otpTemplate = otpTemplate; }
    public int getMaxSendsPerWindow() { return maxSendsPerWindow; }
    public void setMaxSendsPerWindow(int maxSendsPerWindow) { this.maxSendsPerWindow = maxSendsPerWindow; }
    public int getRateWindowMinutes() { return rateWindowMinutes; }
    public void setRateWindowMinutes(int rateWindowMinutes) { this.rateWindowMinutes = rateWindowMinutes; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getOtpValidMinutes() { return otpValidMinutes; }
    public void setOtpValidMinutes(int otpValidMinutes) { this.otpValidMinutes = otpValidMinutes; }
}
