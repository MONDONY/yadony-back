package com.yadony.api.requests.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record PackageRequestCreateRequest(
    @NotBlank @Size(max = 100) String departureCity,
    @NotBlank @Size(max = 100) String arrivalCity,
    @NotNull @FutureOrPresent LocalDate desiredDate,
    @Min(0) @Max(7) int dateToleranceDays,
    @NotNull @DecimalMin("0.5") @DecimalMax("32.0") BigDecimal weightKg,
    // Multi-sélection jointe par virgule côté front — alignée sur BidCheckoutRequest
    // (500, cf. V171__unify_content_categories.sql pour le pourquoi).
    @NotBlank @Size(max = 500) String contentCategory,
    @Size(max = 500) String description,
    // Budget TOTAL (gross) obligatoire ; converti en net au service.
    @DecimalMin("0.0") @DecimalMax("560.0") BigDecimal totalBudgetEur,
    @Size(max = 500) String photoUrl,
    @Size(max = 100) String pickupNeighborhood,
    @Size(max = 100) String deliveryNeighborhood,
    boolean negotiable,
    @NotEmpty Set<PaymentMethod> acceptedPaymentMethods,
    // Clés S3 des photos colis (max 4) — sous package_requests/{senderId}/. Remplace photoUrl.
    @Size(max = 4) List<String> photoKeys,
    // null/false → publication directe (comportement historique) ; true → brouillon.
    // Nullable et non primitif pour que l'absence du champ reste distincte de false.
    Boolean saveAsDraft,
    // Code promo brut (nullable), saisi à la publication — validation paresseuse
    // au paiement réel, même contrat que BidRequest.promoCode.
    @Size(max = 50) String promoCode,
    // Devise choisie pour cette demande (ex "USD"). Optionnel : absent ou vide, le
    // service retombe sur ActiveCurrencyResolver.resolve(senderId) (portefeuille,
    // sinon pays). Validée contre SupportedCurrency dans PackageRequestService,
    // jamais ici (422 currency-unsupported, pas une 400 Bean Validation).
    String currency
) {
    /**
     * Constructeur de compatibilité (sans promoCode ni currency) — évite de
     * retoucher tous les sites d'appel de test existants pour l'ajout de ces
     * champs optionnels.
     */
    public PackageRequestCreateRequest(
            String departureCity, String arrivalCity, LocalDate desiredDate, int dateToleranceDays,
            BigDecimal weightKg, String contentCategory, String description, BigDecimal totalBudgetEur,
            String photoUrl, String pickupNeighborhood, String deliveryNeighborhood, boolean negotiable,
            Set<PaymentMethod> acceptedPaymentMethods, List<String> photoKeys, Boolean saveAsDraft) {
        this(departureCity, arrivalCity, desiredDate, dateToleranceDays, weightKg, contentCategory,
            description, totalBudgetEur, photoUrl, pickupNeighborhood, deliveryNeighborhood, negotiable,
            acceptedPaymentMethods, photoKeys, saveAsDraft, null, null);
    }

    /**
     * Constructeur de compatibilité (sans currency) — évite de retoucher tous
     * les sites d'appel de test existants pour l'ajout de ce champ optionnel.
     */
    public PackageRequestCreateRequest(
            String departureCity, String arrivalCity, LocalDate desiredDate, int dateToleranceDays,
            BigDecimal weightKg, String contentCategory, String description, BigDecimal totalBudgetEur,
            String photoUrl, String pickupNeighborhood, String deliveryNeighborhood, boolean negotiable,
            Set<PaymentMethod> acceptedPaymentMethods, List<String> photoKeys, Boolean saveAsDraft,
            String promoCode) {
        this(departureCity, arrivalCity, desiredDate, dateToleranceDays, weightKg, contentCategory,
            description, totalBudgetEur, photoUrl, pickupNeighborhood, deliveryNeighborhood, negotiable,
            acceptedPaymentMethods, photoKeys, saveAsDraft, promoCode, null);
    }
}
