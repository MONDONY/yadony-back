package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.ReleaseIntentCancelRequest;
import com.yadony.api.admin.dto.ReleaseIntentCancelResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeReleaseExecutor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Sortie de secours d'un versement carte réservé resté sans issue (PROD_CHECKLIST 9.23) : paiement
 * RELEASED, réservation PENDING, aucune confirmation de Stripe. Réservé au super-admin
 * ({@code ADMIN_MANAGE}). La décision repose sur une recherche Stripe faite à l'instant :
 * Transfer trouvé → versement confirmé (réaligné) ; aucun → séquestre rendu (remboursable).
 *
 * <p>{@code POST /admin/payments/{id}/release-intent/cancel}, corps {@code {"reason": "…"}}
 * (10 à 500 caractères). 200 {@code CANCELLED} ou {@code REALIGNED} ; 409
 * {@code no-pending-release} / {@code release-in-progress} ; 422 {@code release-intent-unsupported}
 * (capture legacy) ; 502 {@code stripe-unavailable} (recherche impossible, rien n'a changé).
 */
@RestController
@RequestMapping("/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReleaseIntentController {

    private final StripeReleaseExecutor releaseExecutor;
    private final PaymentRepository paymentRepository;

    public AdminReleaseIntentController(StripeReleaseExecutor releaseExecutor, PaymentRepository paymentRepository) {
        this.releaseExecutor = releaseExecutor;
        this.paymentRepository = paymentRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
    @PostMapping("/{id}/release-intent/cancel")
    public ResponseEntity<ReleaseIntentCancelResponse> cancel(@PathVariable UUID id,
                                                              @Valid @RequestBody ReleaseIntentCancelRequest request,
                                                              Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        StripeReleaseExecutor.Cancellation outcome = releaseExecutor.cancelReservation(id, adminId, request.trimmedReason());
        return switch (outcome.result()) {
            case CANCELLED, REALIGNED -> ResponseEntity.ok(new ReleaseIntentCancelResponse(id,
                    outcome.result().name(), outcome.transferId(),
                    paymentRepository.findStatusById(id).map(PaymentStatus::name).orElse(null)));
            case NOT_PENDING -> throw new YadonyBusinessException(HttpStatus.CONFLICT, "no-pending-release",
                    "No Pending Release", "Aucun versement réservé en attente pour ce paiement");
            case IN_PROGRESS -> throw new YadonyBusinessException(HttpStatus.CONFLICT, "release-in-progress",
                    "Release In Progress", "Un appel Stripe est en cours pour ce versement : réessayez dans quelques minutes");
            case UNSUPPORTED -> throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "release-intent-unsupported", "Release Intent Unsupported",
                    "Capture legacy : vérifiez le PaymentIntent dans Stripe et traitez à la main");
            case LOOKUP_FAILED -> throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-unavailable",
                    "Stripe Unavailable",
                    "Recherche Stripe impossible : rien n'a été modifié, réessayez dans un instant");
        };
    }
}
