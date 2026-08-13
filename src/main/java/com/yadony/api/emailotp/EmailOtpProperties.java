package com.yadony.api.emailotp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yadony.email")
public class EmailOtpProperties {

    private String resendApiKey = "";
    private String fromAddress = "noreply@yadony.com";
    private String otpTemplate = "Ton code Yadony est : %s. Valable 10 minutes.";

    /**
     * Nombre de codes envoyables à une même adresse par fenêtre glissante.
     *
     * <p>Doit rester cohérent avec le compte à rebours du bouton « Renvoyer le
     * code » de l'application : celui-ci se rouvre toutes les 60 s, donc un
     * budget inférieur à {@code rateWindowMinutes} envois fait refuser un renvoi
     * que l'écran présentait pourtant comme disponible.
     */
    private int maxSendsPerWindow = 5;

    /** Durée, en minutes, de la fenêtre glissante appliquée à {@link #maxSendsPerWindow}. */
    private int rateWindowMinutes = 5;

    /**
     * Codes erronés tolérés par adresse sur la durée de validité d'un code.
     *
     * <p>Volontairement compté par adresse et non par code : sinon chaque renvoi
     * offrirait un budget d'essais neuf, et la limite ne protégerait plus de rien.
     */
    private int maxAttempts = 5;

    /** Durée de validité d'un code, en minutes. */
    private int otpValidMinutes = 10;

    public String getResendApiKey() { return resendApiKey; }
    public void setResendApiKey(String resendApiKey) { this.resendApiKey = resendApiKey; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
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
