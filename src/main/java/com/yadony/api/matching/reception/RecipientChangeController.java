package com.yadony.api.matching.reception;

import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.reception.dto.ChangeRecipientRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** L'expéditeur change le destinataire de son colis, jusqu'à la remise (lot 3A). */
@RestController
@PreAuthorize("isAuthenticated()")
public class RecipientChangeController {

    private final RecipientChangeService service;

    public RecipientChangeController(RecipientChangeService service) {
        this.service = service;
    }

    @PutMapping("/bids/{bidId}/recipient")
    public ResponseEntity<BidResponse> changeRecipient(@AuthenticationPrincipal String firebaseUid,
                                                       @PathVariable UUID bidId,
                                                       @Valid @RequestBody ChangeRecipientRequest request) {
        return ResponseEntity.ok(service.changeRecipient(bidId, firebaseUid, request));
    }
}
