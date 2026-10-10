package com.yadony.api.cancellation;

import com.yadony.api.cancellation.dto.PrePaymentCancellationResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Annulation par l'expéditeur d'une demande qui attend son paiement. Pas de garde de rôle :
 * la propriété du bid, vérifiée par le service, fait autorité (comme {@code PUT /bids/{id}/cancel}).
 */
@RestController
public class PrePaymentCancellationController {

    private final PrePaymentCancellationService service;

    public PrePaymentCancellationController(PrePaymentCancellationService service) {
        this.service = service;
    }

    @PostMapping("/bids/{bidId}/cancel-before-payment")
    public ResponseEntity<PrePaymentCancellationResponse> cancelBeforePayment(@PathVariable UUID bidId) {
        return ResponseEntity.ok(service.cancel(requireFirebaseUid(), bidId));
    }

    private static String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
