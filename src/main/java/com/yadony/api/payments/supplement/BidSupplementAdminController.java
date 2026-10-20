package com.yadony.api.payments.supplement;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.payments.supplement.dto.AdminSupplementDecisionRequest;
import com.yadony.api.payments.supplement.dto.AdminSupplementDecisionResponse;
import com.yadony.api.payments.supplement.dto.AdminSupplementRow;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Suppléments de prix côté administration (plan §10) : liste d'un colis, et décision manuelle sur
 * un supplément bloqué (litige tranché par partage, principal remboursé, alerte
 * {@code SUPP_UNSETTLED_}) ou relance d'un versement / remboursement resté en attente. Chaque
 * méthode re-déclare {@code hasRole('ADMIN')} : une {@code @PreAuthorize} de méthode remplace celle
 * de la classe.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class BidSupplementAdminController {

    private final BidSupplementAdminService service;

    public BidSupplementAdminController(BidSupplementAdminService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/bids/{bidId}/supplements")
    public List<AdminSupplementRow> list(@PathVariable UUID bidId) {
        return service.listForBid(bidId);
    }

    /** Verse le supplément au voyageur (ou relance un versement en attente). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_RELEASE')")
    @PostMapping("/supplements/{id}/payout")
    public AdminSupplementDecisionResponse payout(@PathVariable UUID id,
                                                  @RequestBody @Valid AdminSupplementDecisionRequest request,
                                                  Authentication authentication) {
        return service.payout(id, AdminPrincipal.requireAdminId(authentication), request.reason());
    }

    /** Rembourse le supplément à l'expéditeur (ou relance un remboursement en attente). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_REFUND')")
    @PostMapping("/supplements/{id}/refund")
    public AdminSupplementDecisionResponse refund(@PathVariable UUID id,
                                                  @RequestBody @Valid AdminSupplementDecisionRequest request,
                                                  Authentication authentication) {
        return service.refund(id, AdminPrincipal.requireAdminId(authentication), request.reason());
    }
}
