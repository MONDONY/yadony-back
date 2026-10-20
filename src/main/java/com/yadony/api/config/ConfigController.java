package com.yadony.api.config;

import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.config.dto.CommissionGridResponse;
import com.yadony.api.config.dto.CommissionRateResponse;
import com.yadony.api.config.dto.ContentCategoryResponse;
import com.yadony.api.config.dto.FlightsEnabledResponse;
import com.yadony.api.config.dto.PaymentMethodsResponse;
import com.yadony.api.config.dto.ProEnabledResponse;
import com.yadony.api.config.dto.ReimbursementCapResponse;
import com.yadony.api.config.dto.SmsEnabledResponse;
import com.yadony.api.config.dto.UrgencyThresholdResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/config")
public class ConfigController {

    /**
     * Lot D — la source est desormais la table {@code platform_settings}, plus les
     * properties : une modification faite depuis le back-office est visible ici
     * immediatement, sans redeploiement.
     *
     * <p>⚠️ La FORME des reponses ne change pas et ne doit jamais changer : ces routes
     * sont consommees par l'application mobile deja installee chez les utilisateurs, qui
     * ne peut pas etre mise a jour a la demande. {@code ConfigControllerPlatformSettingsIT}
     * la verrouille.
     */
    private final PlatformSettingsService settings;
    private final CommissionGridService commissionGrids;

    public ConfigController(PlatformSettingsService settings, CommissionGridService commissionGrids) {
        this.settings = settings;
        this.commissionGrids = commissionGrids;
    }

    /**
     * Taux unique pour les versions de l'app d'avant les paliers : le taux de la première tranche de la
     * grille en vigueur, sinon {@code commission_rate}. La forme de la réponse ne change pas.
     */
    @GetMapping("/commission-rate")
    public ResponseEntity<CommissionRateResponse> getCommissionRate() {
        BigDecimal rate = commissionGrids.current()
                .map(CommissionGrid::tiers)
                .map(tiers -> tiers.get(0))
                .map(CommissionTier::rate)
                .orElseGet(settings::commissionRate);
        return ResponseEntity.ok(new CommissionRateResponse(rate));
    }

    /**
     * Grille de commission en vigueur et grille programmée (spec §5.4), plus {@code legacyRate} : le taux
     * des objets sans grille, {@code commission_rate} (jamais la 1re tranche). Public comme tout {@code /config/**}.
     */
    @GetMapping("/commission-grid")
    public ResponseEntity<CommissionGridResponse> getCommissionGrid() {
        return ResponseEntity.ok(CommissionGridResponse.of(commissionGrids.current(), commissionGrids.scheduled(),
                settings.commissionRate()));
    }

    /**
     * Une grille par identifiant, en vigueur, passée ou programmée : celle figée sur un bid, un fil ou
     * une demande ({@code commissionGridId} des devis), pour que le client recalcule exactement le prix
     * déjà annoncé même après l'entrée en vigueur d'une autre grille. Public comme tout {@code /config/**}.
     * 404 {@code commission-grid-not-found} si elle est inconnue ou annulée (jamais utilisable).
     */
    @GetMapping("/commission-grid/{id}")
    public ResponseEntity<CommissionGridResponse.GridView> getCommissionGridById(@PathVariable UUID id) {
        return ResponseEntity.ok(CommissionGridResponse.GridView.from(commissionGrids.require(id)));
    }

    @GetMapping("/urgency-threshold")
    public ResponseEntity<UrgencyThresholdResponse> getUrgencyThreshold() {
        return ResponseEntity.ok(new UrgencyThresholdResponse(settings.urgencyThresholdDays()));
    }

    @GetMapping("/reimbursement-cap")
    public ResponseEntity<ReimbursementCapResponse> getReimbursementCap() {
        return ResponseEntity.ok(new ReimbursementCapResponse(settings.reimbursementCapEur()));
    }

    @GetMapping("/content-categories")
    public ResponseEntity<List<ContentCategoryResponse>> getContentCategories() {
        return ResponseEntity.ok(ContentCatalog.CATEGORIES);
    }

    @GetMapping("/sms-enabled")
    public ResponseEntity<SmsEnabledResponse> getSmsEnabled() {
        return ResponseEntity.ok(new SmsEnabledResponse(settings.smsEnabled()));
    }

    /**
     * Feature flag de l'offre PRO, lu par l'application mobile au demarrage pour masquer
     * toute entree PRO tant que l'offre n'est pas ouverte. Public comme ses voisins : le
     * flag est lu avant toute authentification et ne revele rien de sensible.
     */
    @GetMapping("/pro-enabled")
    public ResponseEntity<ProEnabledResponse> getProEnabled() {
        return ResponseEntity.ok(new ProEnabledResponse(settings.proEnabled()));
    }

    /**
     * Feature flag des billets d'avion : faux, l'application garde sa barre de navigation
     * d'avant, sans l'onglet Billets. Public comme ses voisins.
     */
    @GetMapping("/flights-enabled")
    public ResponseEntity<FlightsEnabledResponse> getFlightsEnabled() {
        return ResponseEntity.ok(new FlightsEnabledResponse(settings.flightsEnabled()));
    }

    /**
     * Drapeaux des moyens de paiement de la feuille (YADONY-FLUTTER-PROD-11), lus par
     * l'application à CHAQUE ouverture de la feuille de paiement : une bascule faite dans le
     * back-office s'applique au paiement suivant, sans redémarrage de l'application. PayPal reste
     * en plus décidé par paiement ({@code paymentMethodTypes} du PaymentIntent). Public comme ses
     * voisins : rien de sensible.
     */
    @GetMapping("/payment-methods")
    public ResponseEntity<PaymentMethodsResponse> getPaymentMethods() {
        return ResponseEntity.ok(new PaymentMethodsResponse(
                settings.applePayEnabled(), settings.googlePayEnabled(), settings.paypalEnabled()));
    }
}
