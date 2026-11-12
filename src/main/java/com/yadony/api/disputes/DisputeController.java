package com.yadony.api.disputes;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.dto.DisputeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/disputes")
public class DisputeController {

    private final DisputeService disputeService;
    private final UserRepository userRepository;

    public DisputeController(DisputeService disputeService, UserRepository userRepository) {
        this.disputeService = disputeService;
        this.userRepository = userRepository;
    }

    // GET /disputes/me — litiges en lecture seule où l'utilisateur courant est partie
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public ResponseEntity<List<DisputeResponse>> getMyDisputes() {
        UUID userId = resolveUserId();
        return ResponseEntity.ok(disputeService.getDisputesForUser(userId));
    }

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        return user.getId();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        return (String) auth.getPrincipal();
    }
}
