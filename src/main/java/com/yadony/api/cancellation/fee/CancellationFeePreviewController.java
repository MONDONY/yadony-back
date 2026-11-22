package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.dto.CancellationFeePreviewResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Aperçu des frais d'annulation, à montrer à l'expéditeur AVANT qu'il confirme l'annulation de son colis. Pas de garde
 * de rôle : la propriété du colis, vérifiée par le service (expéditeur seul, 403 sinon), fait autorité, comme
 * {@code POST /bids/{id}/cancel-before-payment}.
 */
@RestController
@RequestMapping("/cancellations")
public class CancellationFeePreviewController {

    private final CancellationFeePreviewService service;

    public CancellationFeePreviewController(CancellationFeePreviewService service) {
        this.service = service;
    }

    @GetMapping("/bids/{bidId}/fee-preview")
    public ResponseEntity<CancellationFeePreviewResponse> preview(@PathVariable UUID bidId) {
        return ResponseEntity.ok(service.preview(requireFirebaseUid(), bidId));
    }

    private static String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "problem.auth.token-required");
        }
        return (String) auth.getPrincipal();
    }
}
