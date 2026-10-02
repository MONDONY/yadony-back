package com.yadony.api.calls;

import com.yadony.api.calls.dto.AdminCallResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Historique des appels d'une commande, pour l'arbitrage des litiges. Aucun contenu, métadonnées seules. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminCallsController {

    private final CallRepository calls;

    public AdminCallsController(CallRepository calls) {
        this.calls = calls;
    }

    @GetMapping("/admin/bids/{bidId}/calls")
    public List<AdminCallResponse> list(@PathVariable UUID bidId) {
        return calls.findByBidIdOrderByCreatedAtDesc(bidId).stream()
                .map(c -> new AdminCallResponse(c.getId(), c.getConversationId(), c.getCallerId(), c.getCalleeId(),
                        c.getStatus().name(), c.getStartedAt(), c.getEndedAt(), c.getDurationSeconds(), c.getCreatedAt()))
                .toList();
    }
}
