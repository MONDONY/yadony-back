package com.yadony.api.addressbook.recipient;

import com.yadony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.yadony.api.addressbook.recipient.dto.RecipientDto;
import com.yadony.api.addressbook.recipient.dto.UpdateRecipientRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController("addressbookRecipientController")
@RequestMapping("/addressbook/recipients")
@PreAuthorize("hasRole('SENDER')")
public class RecipientController {

    private final RecipientService service;
    private final UserRepository userRepository;

    public RecipientController(RecipientService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<RecipientDto>> list(@AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(service.findAll(userId(firebaseUid)));
    }

    @PostMapping
    public ResponseEntity<RecipientDto> create(
            @AuthenticationPrincipal String firebaseUid,
            @Valid @RequestBody CreateRecipientRequest request) {
        RecipientDto dto = service.create(userId(firebaseUid), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecipientDto> update(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRecipientRequest request) {
        return ResponseEntity.ok(service.update(userId(firebaseUid), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id) {
        service.delete(userId(firebaseUid), id);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyNotFoundException.localized("problem.kyc.user-not-found"))
                .getId();
    }
}
