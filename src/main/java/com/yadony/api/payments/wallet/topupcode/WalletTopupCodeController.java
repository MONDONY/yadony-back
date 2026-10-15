package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.topupcode.dto.RedeemTopupCodeRequest;
import com.yadony.api.payments.wallet.topupcode.dto.TopupCodeRedeemResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallet/topup-codes")
@PreAuthorize("isAuthenticated()")
public class WalletTopupCodeController {

    private final WalletTopupCodeRedemptionService redemptionService;
    private final UserRepository userRepository;

    public WalletTopupCodeController(WalletTopupCodeRedemptionService redemptionService,
                                     UserRepository userRepository) {
        this.redemptionService = redemptionService;
        this.userRepository = userRepository;
    }

    @PostMapping("/redeem")
    public TopupCodeRedeemResponse redeem(@Valid @RequestBody RedeemTopupCodeRequest request) {
        return redemptionService.redeem(currentUserId(), request.code());
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByFirebaseUid((String) auth.getPrincipal())
                .map(UserEntity::getId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Utilisateur introuvable", ""));
    }
}
