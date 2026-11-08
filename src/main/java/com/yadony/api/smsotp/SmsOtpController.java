package com.yadony.api.smsotp;

import com.yadony.api.auth.AuthService;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.smsotp.dto.SmsOtpAttachRequest;
import com.yadony.api.smsotp.dto.SmsOtpSendRequest;
import com.yadony.api.smsotp.dto.SmsOtpSendResponse;
import com.yadony.api.smsotp.dto.SmsOtpVerifyRequest;
import com.yadony.api.smsotp.dto.SmsOtpVerifyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/sms-otp")
public class SmsOtpController {

    /** Jeton personnalisé de reconnexion après rattachement (voir FirebaseContactService#issueSessionToken). */
    static final String SESSION_TOKEN_HEADER = "X-Session-Token";

    private final SmsOtpService smsOtpService;
    private final AuthService authService;

    public SmsOtpController(SmsOtpService smsOtpService, AuthService authService) {
        this.smsOtpService = smsOtpService;
        this.authService = authService;
    }

    @PostMapping("/send")
    public ResponseEntity<SmsOtpSendResponse> send(@Valid @RequestBody SmsOtpSendRequest request) {
        var expiresAt = smsOtpService.sendOtp(request.phoneNumber(), signedInUid());
        return ResponseEntity.ok(new SmsOtpSendResponse(expiresAt));
    }

    @PostMapping("/verify")
    public ResponseEntity<SmsOtpVerifyResponse> verify(@Valid @RequestBody SmsOtpVerifyRequest request) {
        String customToken = smsOtpService.verifyOtp(request.phoneNumber(), request.code());
        return ResponseEntity.ok(new SmsOtpVerifyResponse(customToken));
    }

    /**
     * Rattache un numéro au compte connecté. Numéro et code voyagent ensemble :
     * le serveur exige la preuve de possession au moment même où il écrit, ce qu'un
     * appel de vérification séparé ne garantissait pas.
     */
    @PostMapping("/attach")
    public ResponseEntity<UserResponse> attach(@Valid @RequestBody SmsOtpAttachRequest request) {
        String firebaseUid = requireFirebaseUid();
        String sessionToken = smsOtpService.attachPhoneToAccount(firebaseUid, request.phoneNumber(), request.code());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (sessionToken != null) {
            // En-tête plutôt que champ du profil : UserResponse sert partout, et un
            // client antérieur ignore simplement l'en-tête (FirebaseContactService#issueSessionToken).
            response.header(SESSION_TOKEN_HEADER, sessionToken);
        }
        return response.body(authService.getProfile(firebaseUid));
    }

    /**
     * UID du compte connecté qui demande un code, {@code null} sans jeton, en invité
     * (session anonyme Firebase, {@code ROLE_GUEST}) ou pour un administrateur. Seul un
     * vrai compte peut demander un code pour un numéro encore inconnu, pour l'ajouter à
     * son profil : un invité se crée sans rien prouver, il rouvrirait le pompage.
     */
    static String signedInUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof String uid) || uid.isBlank()
                || "anonymousUser".equals(uid)) {
            return null;
        }
        boolean guest = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_GUEST".equals(a.getAuthority()));
        return guest ? null : uid;
    }

    /**
     * L'authentification est exigée par SecurityConfig, qui déclare
     * {@code /auth/sms-otp/attach} en {@code .authenticated()} avant le permitAll du
     * préfixe. Cette garde reste en second rideau et sert surtout à extraire l'UID.
     */
    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())
                || !(auth.getPrincipal() instanceof String uid)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis");
        }
        return uid;
    }
}
