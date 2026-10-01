package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.CreateRecipientInvitationRequest;
import com.yadony.api.addressbook.invitation.dto.IncomingInvitationDto;
import com.yadony.api.addressbook.invitation.dto.InvitationSentResponse;
import com.yadony.api.addressbook.invitation.dto.SentInvitationDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Invitations au carnet de destinataires : côté inviteur et côté invité. */
@RestController
@RequestMapping("/recipient-invitations")
@PreAuthorize("isAuthenticated()")
public class RecipientInvitationController {

    private final RecipientInvitationService service;

    public RecipientInvitationController(RecipientInvitationService service) {
        this.service = service;
    }

    /** Toujours {@code 202 { "status": "SENT" }} pour une cible valide, compte existant ou non. */
    @PostMapping
    public ResponseEntity<InvitationSentResponse> send(@AuthenticationPrincipal String firebaseUid,
                                                       @RequestBody CreateRecipientInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.send(firebaseUid, request));
    }

    @GetMapping("/sent")
    public List<SentInvitationDto> sent(@AuthenticationPrincipal String firebaseUid) {
        return service.sent(firebaseUid);
    }

    @GetMapping("/incoming")
    public List<IncomingInvitationDto> incoming(@AuthenticationPrincipal String firebaseUid) {
        return service.incoming(firebaseUid);
    }

    @PostMapping("/{id}/accept")
    public IncomingInvitationDto accept(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        return service.accept(id, firebaseUid);
    }

    @PostMapping("/{id}/decline")
    public IncomingInvitationDto decline(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        return service.decline(id, firebaseUid);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        service.revoke(id, firebaseUid);
        return ResponseEntity.noContent().build();
    }
}
