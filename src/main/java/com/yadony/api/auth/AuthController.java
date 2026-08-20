package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.auth.dto.DeleteImmediatelyRequest;
import com.yadony.api.auth.dto.DeletionEligibilityResponse;
import com.yadony.api.auth.dto.FcmTokenRequest;
import com.yadony.api.auth.dto.RegisterRequest;
import com.yadony.api.auth.dto.UpdateProfileRequest;
import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.auth.dto.WalletRefundRequestResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        String firebaseUid = requireFirebaseUid();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        FirebaseToken decodedToken = auth.getCredentials() instanceof FirebaseToken t ? t : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(firebaseUid, decodedToken, request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.getProfile(firebaseUid));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.updateProfile(firebaseUid, request));
    }

    @PutMapping("/me/fcm-token")
    public ResponseEntity<Void> updateFcmToken(@Valid @RequestBody FcmTokenRequest request) {
        String firebaseUid = requireFirebaseUid();
        authService.updateFcmToken(firebaseUid, request.fcmToken(),
                request.deviceId(), request.deviceName(), request.platform());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/privacy-settings")
    public ResponseEntity<com.yadony.api.auth.dto.PrivacySettingsResponse> getPrivacySettings() {
        return ResponseEntity.ok(authService.getPrivacySettings(requireFirebaseUid()));
    }

    @PutMapping("/me/privacy-settings")
    public ResponseEntity<Void> updatePrivacySettings(
            @Valid @RequestBody com.yadony.api.auth.dto.PrivacySettingsRequest request) {
        authService.updatePrivacySettings(requireFirebaseUid(), request.contactKycOnly(),
                request.hidePhoneNumber());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/analytics-consent")
    public ResponseEntity<com.yadony.api.auth.dto.AnalyticsConsentResponse> getAnalyticsConsent() {
        return ResponseEntity.ok(authService.getAnalyticsConsent(requireFirebaseUid()));
    }

    @PutMapping("/me/analytics-consent")
    public ResponseEntity<Void> updateAnalyticsConsent(
            @Valid @RequestBody com.yadony.api.auth.dto.AnalyticsConsentRequest request) {
        authService.updateAnalyticsConsent(requireFirebaseUid(),
                request.granted(), request.policyVersion(), request.source());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/deletion-eligibility")
    public ResponseEntity<DeletionEligibilityResponse> checkDeletionEligibility() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.checkDeletionEligibility(firebaseUid));
    }

    /** Solde wallet bloquant la suppression → ouvre un ticket de remboursement manuel
     *  (cf. {@code WalletRefundRequestService}). Ne débloque pas la suppression elle-même :
     *  un admin doit résoudre le ticket après remboursement Stripe hors-app. */
    @PostMapping("/me/wallet-refund-request")
    public ResponseEntity<java.util.List<WalletRefundRequestResponse>> requestWalletRefund() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.requestWalletRefund(firebaseUid));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount() {
        String firebaseUid = requireFirebaseUid();
        authService.deleteAccount(firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/reactivate")
    public ResponseEntity<UserResponse> reactivateAccount() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.reactivateAccount(firebaseUid));
    }

    @PostMapping("/me/upgrade-to-pro")
    public ResponseEntity<UserResponse> upgradeToPro(@Valid @RequestBody UpgradeToProRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.upgradeToPro(firebaseUid, request));
    }

    @DeleteMapping("/me/upgrade-to-pro")
    public ResponseEntity<UserResponse> downgradePro() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.downgradePro(firebaseUid));
    }

    @PostMapping("/me/delete-immediately")
    public ResponseEntity<Void> deleteImmediately(@Valid @RequestBody DeleteImmediatelyRequest request) {
        String firebaseUid = requireFirebaseUid();
        authService.deleteImmediately(firebaseUid, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.updateAvatar(firebaseUid, file));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "unauthorized",
                    "Unauthorized",
                    "Un token Firebase valide est requis"
            );
        }
        if (auth.getPrincipal() instanceof AdminPrincipal ap) {
            return ap.firebaseUid();
        }
        return (String) auth.getPrincipal();
    }
}
