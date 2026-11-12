package com.yadony.api.triptemplate;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.triptemplate.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/trip-templates")
@PreAuthorize("hasRole('TRAVELER')")
public class TripTemplateController {

    private final TripTemplateService service;
    private final UserRepository userRepository;

    public TripTemplateController(TripTemplateService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<TripTemplateDto>> list(@AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(service.findAll(resolveUserId(firebaseUid)));
    }

    @PostMapping
    public ResponseEntity<TripTemplateDto> create(
            @AuthenticationPrincipal String firebaseUid,
            @Valid @RequestBody CreateTripTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(resolveUserId(firebaseUid), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TripTemplateDto> update(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTripTemplateRequest request) {
        return ResponseEntity.ok(service.update(resolveUserId(firebaseUid), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id) {
        service.delete(resolveUserId(firebaseUid), id);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUserId(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        return user.getId();
    }
}
