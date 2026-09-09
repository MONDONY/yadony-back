package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyActivateRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compte de versement mobile money du voyageur. Le numéro du compte Firebase de l'appelant
 * (vérifié par OTP) reste TOUJOURS prioritaire : {@link MobileMoneyAccountService#activate}
 * l'utilise dès qu'il existe et ignore alors silencieusement tout numéro fourni dans le corps
 * de {@link #activate}. Ce corps est facultatif et n'est lu que si le compte Firebase n'a pas
 * de téléphone (voir la javadoc du service pour le motif). L'identifiant utilisateur vient
 * uniquement du contexte de sécurité (jamais d'un paramètre d'URL ou de corps), ce qui garantit
 * qu'un appelant n'agit que sur son propre compte.
 */
@RestController
@RequestMapping("/payments/mobile-money/account")
@PreAuthorize("hasAnyRole('TRAVELER', 'SENDER')")
public class MobileMoneyAccountController {

    private final MobileMoneyAccountService service;
    private final UserRepository userRepository;

    public MobileMoneyAccountController(MobileMoneyAccountService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public MobileMoneyAccountResponse get(@AuthenticationPrincipal String firebaseUid) {
        return service.get(callerId(firebaseUid));
    }

    /** Corps facultatif : numéro de versement, utilisé seulement si le compte Firebase n'en a pas. */
    @PostMapping
    public MobileMoneyAccountResponse activate(@AuthenticationPrincipal String firebaseUid,
                                                @RequestBody(required = false) MobileMoneyActivateRequest body) {
        return service.activate(callerId(firebaseUid), body == null ? null : body.phoneNumber());
    }

    @DeleteMapping
    public MobileMoneyAccountResponse disable(@AuthenticationPrincipal String firebaseUid) {
        return service.disable(callerId(firebaseUid));
    }

    // Le principal est l'UID Firebase (String), posé par FirebaseTokenFilter.
    private UUID callerId(String firebaseUid) {
        if (firebaseUid == null) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise"))
                .getId();
    }
}
