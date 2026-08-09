package com.yadony.api.payments.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public class CreatePaymentRequest {

    @NotNull(message = "bidId est obligatoire")
    private UUID bidId;

    // Indicatif uniquement : le montant net est TOUJOURS recalculé côté serveur
    // (grid items snapshotés + poids × prix/kg). S'il est fourni ici, il est
    // recoupé et rejeté (422 amount-mismatch) s'il diffère du montant serveur.
    // Ne jamais s'en servir comme source de vérité du montant.
    private BigDecimal totalNetEur;

    // null/true → setup_future_usage=off_session (carte réutilisable) ; false → non enregistrée
    private Boolean savePaymentMethod;

    /** Optional ISO currency requested by the client; the backend remains authoritative. */
    private String currencyCode;


    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public BigDecimal getTotalNetEur() { return totalNetEur; }
    public void setTotalNetEur(BigDecimal totalNetEur) { this.totalNetEur = totalNetEur; }
    public Boolean getSavePaymentMethod() { return savePaymentMethod; }
    public void setSavePaymentMethod(Boolean savePaymentMethod) { this.savePaymentMethod = savePaymentMethod; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
}
