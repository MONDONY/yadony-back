package com.yadony.api.referral;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.yadony.api.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints for the referral system.
 *
 * <ul>
 *   <li>{@code GET  /me/referral}        — get code + stats</li>
 *   <li>{@code POST /me/referral/regenerate} — regenerate code (cooldown applies)</li>
 *   <li>{@code POST /referral/redeem}    — redeem a code on sign-up</li>
 *   <li>{@code GET  /me/referral/referees} — referees and their reward state (paginated)</li>
 *   <li>{@code POST /me/referral/rewards/{invitationId}/choose} — pick a referee's reward</li>
 * </ul>
 */
@RestController
public class ReferralController {

    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    /**
     * Returns the authenticated user's referral code and invitation statistics.
     * Lazily creates a code if not yet generated.
     */
    @GetMapping("/me/referral")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MyReferralResponse> getMyReferral(Authentication auth) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.getMyReferral(firebaseUid));
    }

    /**
     * Regenerates the authenticated user's referral code.
     * Subject to a cooldown configured in {@code yadony.referral.code-regeneration-cooldown-days}.
     */
    @PostMapping("/me/referral/regenerate")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MyReferralResponse> regenerateCode(Authentication auth) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.regenerateCode(firebaseUid));
    }

    /**
     * Lists the authenticated user's referees, newest first (no phone number, first name +
     * initial only). {@code size} is capped at 50.
     */
    @GetMapping("/me/referral/referees")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<PageResponse<ReferralRefereeResponse>> getReferees(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.getReferees(firebaseUid, page, size));
    }

    /**
     * Picks the reward of a referee's first delivery: wallet credit or personal discount code.
     * 404 if the invitation is not the caller's, 409 if not available or already chosen.
     */
    @PostMapping("/me/referral/rewards/{invitationId}/choose")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ReferralRefereeResponse> chooseReward(Authentication auth,
                                                                @PathVariable UUID invitationId,
                                                                @Valid @RequestBody ChooseRewardRequest request) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.chooseReward(firebaseUid, invitationId, request.choice()));
    }

    /**
     * Redeems a referral code on behalf of the authenticated user (referee).
     * Returns 204 No Content on success.
     */
    @PostMapping("/referral/redeem")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<Void> redeemCode(Authentication auth,
                                           @Valid @RequestBody RedeemCodeRequest request) {
        String firebaseUid = (String) auth.getPrincipal();
        referralService.redeemCode(firebaseUid, request.code());
        return ResponseEntity.noContent().build();
    }
}
