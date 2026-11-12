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
    // Facultative depuis V328 : null = « Date flexible » (toutes dates compatibles,
    // jamais urgente, expiration 60 jours après la création).
    @FutureOrPresent LocalDate desiredDate,
    // Ignorée (stockée null) sans date ; avec une date, null retombe sur 2 jours.
    @Min(0) @Max(7) Integer dateToleranceDays,
    // Maximum technique (NUMERIC(8,2), V380). Le plafond par mode (32 kg en avion et « peu importe »)
    // est appliqué au service (WeightLimits, 422 weight-too-heavy) : il dépend du mode.
    @NotNull @DecimalMin("0.5") @DecimalMax("999999.99") BigDecimal weightKg,
    // Multi-sélection jointe par virgule côté front — alignée sur BidCheckoutRequest
    // (500, cf. V171__unify_content_categories.sql pour le pourquoi).
    @NotBlank @Size(max = 500) String contentCategory,
    @Size(max = 500) String description,
    // Budget TOTAL (gross) obligatoire ; converti en net au service.
    // Garde-fou large seulement : le plafond réel dépend de la devise de la demande
    // (560 € mis à l'échelle par CurrencyBounds.maxPackageBudget, appliqué au service).
    // Une annotation ne peut pas connaître la devise ; un plafond de 560 ici valait
    // 0,85 € en franc CFA et rejetait toute demande XOF avant même le service.
    @DecimalMin("0.0") @DecimalMax("1000000.0") BigDecimal totalBudgetEur,
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
    String currency,
    // Mode de transport voulu. Absent/null : PLANE (anciennes apps, qui ne l'envoient pas) ;
    // « ANY » : peu importe (stocké null en base) ; sinon l'un des modes, soumis aux
    // interrupteurs route/bateau dans PackageRequestService. Valeur inconnue : 422 validation.
    @Pattern(regexp = "ANY|PLANE|CAR|TRAIN|BUS|BOAT|OTHER") String transportMode,
    // Taille du colis (S, M, L, XL), facultative, jamais exigée. Chaîne et non enum : une valeur
    // inconnue donne un 422 avec « violations », pas une erreur de désérialisation.
    // Création : absente = pas de taille. Modification : ABSENTE (null) = taille conservée (ancienne
    // app) ; « NONE » = effacer la taille. Gardée en voiture, bus et « peu importe » ; toute autre
    // demande (avion, bateau, train, autre, mode absent) ramène la taille à null (ParcelFitRules).
    @Pattern(regexp = "NONE|S|M|L|XL", message = "{validation.request.item-size.pattern}") String itemSize
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
            acceptedPaymentMethods, photoKeys, saveAsDraft, null, null, null);
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
            acceptedPaymentMethods, photoKeys, saveAsDraft, promoCode, null, null);
    }

    /**
     * Constructeur de compatibilité (sans transportMode) — l'ancienne forme canonique,
     * pour ne pas retoucher tous les sites d'appel existants.
     */
    public PackageRequestCreateRequest(
            String departureCity, String arrivalCity, LocalDate desiredDate, Integer dateToleranceDays,
            BigDecimal weightKg, String contentCategory, String description, BigDecimal totalBudgetEur,
            String photoUrl, String pickupNeighborhood, String deliveryNeighborhood, boolean negotiable,
            Set<PaymentMethod> acceptedPaymentMethods, List<String> photoKeys, Boolean saveAsDraft,
            String promoCode, String currency) {
        this(departureCity, arrivalCity, desiredDate, dateToleranceDays, weightKg, contentCategory,
            description, totalBudgetEur, photoUrl, pickupNeighborhood, deliveryNeighborhood, negotiable,
            acceptedPaymentMethods, photoKeys, saveAsDraft, promoCode, currency, null);
    }

    /**
     * Constructeur de compatibilité (sans itemSize) — l'ancienne forme canonique : la taille y est
     * absente (null), comme chez une ancienne application.
     */
    public PackageRequestCreateRequest(
            String departureCity, String arrivalCity, LocalDate desiredDate, Integer dateToleranceDays,
            BigDecimal weightKg, String contentCategory, String description, BigDecimal totalBudgetEur,
            String photoUrl, String pickupNeighborhood, String deliveryNeighborhood, boolean negotiable,
            Set<PaymentMethod> acceptedPaymentMethods, List<String> photoKeys, Boolean saveAsDraft,
            String promoCode, String currency, String transportMode) {
        this(departureCity, arrivalCity, desiredDate, dateToleranceDays, weightKg, contentCategory,
            description, totalBudgetEur, photoUrl, pickupNeighborhood, deliveryNeighborhood, negotiable,
            acceptedPaymentMethods, photoKeys, saveAsDraft, promoCode, currency, transportMode, null);
    }
}
