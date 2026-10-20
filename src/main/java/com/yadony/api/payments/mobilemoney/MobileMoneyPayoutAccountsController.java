package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountRequest;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comptes de versement mobile money du voyageur, un par code opérateur pawaPay (nouveau contrat ;
 * l'ancien compte unique reste servi par {@link MobileMoneyAccountController}). L'identifiant
 * utilisateur vient uniquement du contexte de sécurité, et le service ne trouve que les comptes de
 * l'appelant (404 pour celui d'un autre) : un appelant n'agit que sur ses propres comptes.
 */
@RestController
@RequestMapping("/payments/mobile-money/accounts")
@PreAuthorize("hasAnyRole('TRAVELER', 'SENDER')")
public class MobileMoneyPayoutAccountsController {

    private final MobileMoneyPayoutAccountService service;
    private final UserRepository userRepository;

    public MobileMoneyPayoutAccountsController(MobileMoneyPayoutAccountService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public MobileMoneyPayoutAccountsResponse list(@AuthenticationPrincipal String firebaseUid) {
        return service.list(callerId(firebaseUid));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView add(@AuthenticationPrincipal String firebaseUid, @Valid @RequestBody MobileMoneyPayoutAccountRequest body) {
        return service.add(callerId(firebaseUid), body.phoneNumber(), body.provider());
    }

    /** Seul {@code phoneNumber} est lu : l'opérateur d'un compte ne change pas. */
    @PutMapping("/{id}/number")
    public AccountView changeNumber(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id,
                                    @Valid @RequestBody MobileMoneyPayoutAccountRequest body) {
        return service.changeNumber(callerId(firebaseUid), id, body.phoneNumber());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        service.remove(callerId(firebaseUid), id);
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
