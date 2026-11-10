package com.yadony.api.travelerstatus;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.TravelerStatusRequest;
import com.yadony.api.travelerstatus.dto.TravelerStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/traveler-status")
public class TravelerStatusController {

    private final TravelerStatusService service;

    public TravelerStatusController(TravelerStatusService service) {
        this.service = service;
    }

    @PutMapping("/me")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public TravelerStatusResponse declare(@AuthenticationPrincipal String firebaseUid,
                                          @Valid @RequestBody TravelerStatusRequest request) {
        return service.declare(requireUid(firebaseUid), request);
    }

    private static String requireUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return firebaseUid;
    }
}
