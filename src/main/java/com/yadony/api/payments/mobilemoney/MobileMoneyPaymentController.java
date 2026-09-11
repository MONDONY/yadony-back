package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyInitiateRequest;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayerProvidersResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bids")
public class MobileMoneyPaymentController {

    private final MobileMoneyBidPaymentService service;
    private final UserRepository userRepository;

    public MobileMoneyPaymentController(MobileMoneyBidPaymentService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /** Acceptation par le voyageur : réserve la capacité et crée le paiement en attente (30 min). */
    @PostMapping("/{bidId}/mobile-money/accept")
    @PreAuthorize("hasRole('TRAVELER')")
    public MobileMoneyPaymentStatusResponse accept(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId) {
        return service.acceptBid(bidId, callerId(firebaseUid));
    }

    /** L'expéditeur déclenche le push PIN (ou la redirection Wave). Corps optionnel : autre numéro payeur et opérateur choisi. */
    @PostMapping("/{bidId}/mobile-money/initiate")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<MobileMoneyPaymentStatusResponse> initiate(@AuthenticationPrincipal String firebaseUid,
                                                                     @PathVariable UUID bidId,
                                                                     @RequestBody(required = false) MobileMoneyInitiateRequest body) {
        String phone = body == null ? null : body.phoneNumber();
        String provider = body == null ? null : body.provider();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.initiateDeposit(bidId, callerId(firebaseUid), phone, provider));
    }

    /** Réseaux avec lesquels l'expéditeur peut payer ce colis. Corps optionnel : autre numéro payeur. */
    @PostMapping("/{bidId}/mobile-money/providers")
    @PreAuthorize("hasRole('SENDER')")
    public MobileMoneyPayerProvidersResponse providers(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId,
                                                       @RequestBody(required = false) MobileMoneyProvidersRequest body) {
        return service.providersForPayer(bidId, callerId(firebaseUid), body == null ? null : body.phoneNumber());
    }

    @GetMapping("/{bidId}/mobile-money/status")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public MobileMoneyPaymentStatusResponse status(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId) {
        return service.status(bidId, callerId(firebaseUid));
    }

    private UUID callerId(String firebaseUid) {
        if (firebaseUid == null) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise"))
                .getId();
    }
}
