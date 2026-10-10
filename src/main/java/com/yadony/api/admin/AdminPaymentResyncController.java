package com.yadony.api.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminPaymentResyncResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.PaymentStripeResyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /admin/payments/{id}/resync-stripe} : réaligne un paiement carte sur Stripe
 * ({@link PaymentStripeResyncService}), puis clôt les alertes du paiement que cette
 * resynchronisation a résolues ({@code RECON_STRIPE_<id>} pour les écarts
 * {@code AUTORISE_NON_ENREGISTRE} / {@code SEQUESTRE_NON_CAPTURE}, {@code ESCROW_CAPTURE_FAILED_<id>}).
 * Une alerte qui porte un autre écart reste ouverte et est renvoyée dans {@code openAlertIds}.
 *
 * <p>Réservé aux super-admins ({@code ADMIN_MANAGE}, comme les autres gestes sensibles hors
 * permission métier) : la resynchronisation peut capturer un séquestre ou changer un statut.
 */
@RestController
@RequestMapping("/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentResyncController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentResyncController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String RECON_PREFIX = "RECON_STRIPE_";
    static final String CAPTURE_FAILED_PREFIX = "ESCROW_CAPTURE_FAILED_";

    private final PaymentStripeResyncService resyncService;
    private final AdminAlertRepository alertRepository;
    private final AuditService auditService;

    public AdminPaymentResyncController(PaymentStripeResyncService resyncService,
                                        AdminAlertRepository alertRepository, AuditService auditService) {
        this.resyncService = resyncService;
        this.alertRepository = alertRepository;
        this.auditService = auditService;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
    @PostMapping("/{id}/resync-stripe")
    public ResponseEntity<AdminPaymentResyncResponse> resync(@PathVariable UUID id, Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        PaymentStripeResyncService.Result result = resyncService.resync(id, adminId);

        List<UUID> resolved = new ArrayList<>();
        List<UUID> open = new ArrayList<>();
        for (AdminAlertEntity alert : openAlertsOf(id)) {
            if (isResolvedBy(alert, result.after())) {
                alert.setResolved(true);
                alert.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
                alertRepository.save(alert);
                auditService.log("ADMIN_ALERT", alert.getId(), "ADMIN_ALERT_RESOLVED", adminId,
                        Map.of("alertType", alert.getType(), "severity", String.valueOf(alert.getSeverity()),
                                "note", "résolue par la resynchronisation Stripe (" + result.action() + ")"));
                resolved.add(alert.getId());
            } else {
                open.add(alert.getId());
            }
        }
        if (!resolved.isEmpty()) {
            log.info("Resynchronisation du paiement {} : {} alerte(s) résolue(s)", id, resolved.size());
        }
        return ResponseEntity.ok(AdminPaymentResyncResponse.of(result, resolved, open));
    }

    private List<AdminAlertEntity> openAlertsOf(UUID paymentId) {
        List<AdminAlertEntity> alerts = new ArrayList<>(alertRepository.findByTypeAndResolved(RECON_PREFIX + paymentId, false));
        alerts.addAll(alertRepository.findByTypeAndResolved(CAPTURE_FAILED_PREFIX + paymentId, false));
        return alerts;
    }

    /** Vrai si l'état aligné ne présente plus aucun des écarts portés par l'alerte. */
    static boolean isResolvedBy(AdminAlertEntity alert, PaymentStripeResyncService.Snapshot after) {
        boolean captured = "succeeded".equals(after.stripeStatus());
        if (alert.getType().startsWith(CAPTURE_FAILED_PREFIX)) {
            return captured;
        }
        Set<String> codes = codesOf(alert.getPayload());
        if (codes.isEmpty()) {
            return false;
        }
        for (String code : codes) {
            boolean gone = switch (code) {
                case "AUTORISE_NON_ENREGISTRE" -> !"PENDING".equals(after.status());
                case "SEQUESTRE_NON_CAPTURE" -> captured || !"ESCROW".equals(after.status());
                default -> false;
            };
            if (!gone) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> codesOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return Set.of();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
            Object ecart = map.get("ecart");
            if (ecart == null || ecart.toString().isBlank()) {
                return Set.of();
            }
            return Set.copyOf(Arrays.stream(ecart.toString().split(",")).map(String::trim).toList());
        } catch (Exception unreadable) {
            return Set.of();
        }
    }
}
