package com.yadony.api.addressbook.pickup;

import com.yadony.api.addressbook.pickup.dto.CreatePickupAddressRequest;
import com.yadony.api.addressbook.pickup.dto.PickupAddressDto;
import com.yadony.api.addressbook.pickup.dto.UpdatePickupAddressRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/pickup-addresses")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class PickupAddressController {

    private final PickupAddressService service;
    private final UserRepository userRepository;

    public PickupAddressController(PickupAddressService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<PickupAddressDto>> list(@AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(service.findAll(userId(firebaseUid)));
    }

    @PostMapping
    public ResponseEntity<PickupAddressDto> create(
            @AuthenticationPrincipal String firebaseUid,
            @Valid @RequestBody CreatePickupAddressRequest request) {
        PickupAddressDto dto = service.create(userId(firebaseUid), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PickupAddressDto> update(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePickupAddressRequest request) {
        return ResponseEntity.ok(service.update(userId(firebaseUid), id, request));
    }

    @PatchMapping("/{id}/set-default")
    public ResponseEntity<PickupAddressDto> setDefault(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.setDefault(userId(firebaseUid), id));
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
