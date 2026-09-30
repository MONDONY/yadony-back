package com.yadony.api.emailotp;

import com.yadony.api.auth.AuthService;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.emailotp.dto.EmailOtpAttachRequest;
import com.yadony.api.emailotp.dto.EmailOtpSendRequest;
import com.yadony.api.emailotp.dto.EmailOtpSendResponse;
import com.yadony.api.emailotp.dto.EmailOtpVerifyRequest;
import com.yadony.api.emailotp.dto.EmailOtpVerifyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/email-otp")
public class EmailOtpController {

    /** Jeton personnalisé de reconnexion après rattachement (voir FirebaseContactService#issueSessionToken). */
    static final String SESSION_TOKEN_HEADER = "X-Session-Token";

    private final EmailOtpService emailOtpService;
    private final AuthService authService;

    public EmailOtpController(EmailOtpService emailOtpService, AuthService authService) {
        this.emailOtpService = emailOtpService;
        this.authService = authService;
    }

    @PostMapping("/send")
    public ResponseEntity<EmailOtpSendResponse> send(@Valid @RequestBody EmailOtpSendRequest request) {
        var expiresAt = emailOtpService.sendOtp(request.email());
        return ResponseEntity.ok(new EmailOtpSendResponse(expiresAt));
    }

    @PostMapping("/verify")
    public ResponseEntity<EmailOtpVerifyResponse> verify(@Valid @RequestBody EmailOtpVerifyRequest request) {
        String customToken = emailOtpService.verifyOtp(request.email(), request.code());
        return ResponseEntity.ok(new EmailOtpVerifyResponse(customToken));
    }

    /**
     * Rattache une adresse au compte connecté. Adresse et code voyagent ensemble :
     * le serveur exige la preuve de possession au moment même où il écrit, ce qu'un
     * appel de vérification séparé ne garantissait pas.
     */
    @PostMapping("/attach")
    public ResponseEntity<UserResponse> attach(@Valid @RequestBody EmailOtpAttachRequest request) {
        String firebaseUid = requireFirebaseUid();
        String sessionToken = emailOtpService.attachEmailToAccount(firebaseUid, request.email(), request.code());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (sessionToken != null) {
            // En-tête plutôt que champ du profil : UserResponse sert partout, et un
            // client antérieur ignore simplement l'en-tête (FirebaseContactService#issueSessionToken).
            response.header(SESSION_TOKEN_HEADER, sessionToken);
        }
        return response.body(authService.getProfile(firebaseUid));
    }

    /**
     * L'authentification est exigée par SecurityConfig, qui déclare
     * {@code /auth/email-otp/attach} en {@code .authenticated()} avant le permitAll du
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
