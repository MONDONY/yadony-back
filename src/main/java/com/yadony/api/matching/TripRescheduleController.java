package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripRescheduleRequest;
import com.yadony.api.matching.dto.TripRescheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Report d'un trajet publié par son voyageur (vol annulé, voyage repoussé). */
@RestController
@RequestMapping("/announcements")
public class TripRescheduleController {

    private final TripRescheduleService rescheduleService;

    public TripRescheduleController(TripRescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<TripRescheduleResponse> reschedule(
            @PathVariable UUID id,
            @Valid @RequestBody TripRescheduleRequest request
    ) {
        return ResponseEntity.ok(rescheduleService.reschedule(id, requireFirebaseUid(), request));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                    "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
