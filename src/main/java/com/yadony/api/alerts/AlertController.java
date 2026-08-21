package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.CorridorAlertRequest;
import com.yadony.api.alerts.dto.CorridorAlertResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/me/corridor-alerts")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    public List<CorridorAlertResponse> list(@AuthenticationPrincipal String firebaseUid,
                                            @RequestParam(required = false) AlertDirection direction) {
        return alertService.list(firebaseUid, direction);
    }

    @PostMapping("/me/corridor-alerts")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CorridorAlertResponse create(@AuthenticationPrincipal String firebaseUid,
                                        @Valid @RequestBody CorridorAlertRequest request) {
        return alertService.create(firebaseUid, request);
    }

    @PutMapping("/me/corridor-alerts/{id}")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    public CorridorAlertResponse update(@AuthenticationPrincipal String firebaseUid,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody CorridorAlertRequest request) {
        return alertService.update(firebaseUid, id, request, request.active());
    }

    @DeleteMapping("/me/corridor-alerts/{id}")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal String firebaseUid,
                       @PathVariable UUID id) {
        alertService.delete(firebaseUid, id);
    }

    @GetMapping("/me/corridor-alerts/{id}/matches")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    public List<?> matches(@AuthenticationPrincipal String firebaseUid,
                           @PathVariable UUID id) {
        return alertService.getMatchesForDirection(firebaseUid, id);
    }
}
