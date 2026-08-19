package com.yadony.api.config;

import com.yadony.api.config.dto.CommissionRateResponse;
import com.yadony.api.config.dto.ContentCategoryResponse;
import com.yadony.api.config.dto.ReimbursementCapResponse;
import com.yadony.api.config.dto.SmsEnabledResponse;
import com.yadony.api.config.dto.UrgencyThresholdResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public ConfigController(PlatformSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/commission-rate")
    public ResponseEntity<CommissionRateResponse> getCommissionRate() {
        return ResponseEntity.ok(new CommissionRateResponse(settings.commissionRate()));
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
}
