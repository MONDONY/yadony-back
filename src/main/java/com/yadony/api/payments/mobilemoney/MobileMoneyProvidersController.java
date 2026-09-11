package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersRequest;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogue des réseaux mobile money utilisables pour le versement sur un numéro. POST et non GET :
 * le numéro voyage dans le corps, jamais dans une URL (logs nginx, historique).
 */
@RestController
@RequestMapping("/payments/mobile-money/providers")
@PreAuthorize("hasAnyRole('TRAVELER', 'SENDER')")
public class MobileMoneyProvidersController {

    private final MobileMoneyAccountService service;
    private final UserRepository userRepository;

    public MobileMoneyProvidersController(MobileMoneyAccountService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /** Corps facultatif : numéro à examiner ; absent, le numéro de versement déjà enregistré, sinon Firebase. */
    @PostMapping
    public MobileMoneyProvidersResponse providers(@AuthenticationPrincipal String firebaseUid,
                                                  @RequestBody(required = false) MobileMoneyProvidersRequest body) {
        return service.providers(callerId(firebaseUid), body == null ? null : body.phoneNumber());
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
