package com.yadony.api.payments.wallet;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.dto.WalletBalanceResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTransactionDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/wallet")
@PreAuthorize("isAuthenticated()")
public class WalletController {

    private final WalletService walletService;
    private final WalletTopupOrchestrator topupOrchestrator;
    private final UserRepository userRepository;

    public WalletController(WalletService walletService,
                            WalletTopupOrchestrator topupOrchestrator,
                            UserRepository userRepository) {
        this.walletService = walletService;
        this.topupOrchestrator = topupOrchestrator;
        this.userRepository = userRepository;
    }

    @GetMapping("/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @RequestParam(defaultValue = "0") int page) {
        UUID userId = currentUserId();
        WalletAccountEntity wallet = walletService.getOrCreate(userId, "EUR");
        List<WalletTransactionDto> txs = walletService.getTransactions(userId, page)
            .stream()
            .map(WalletTransactionDto::from)
            .collect(Collectors.toList());
        return ResponseEntity.ok(
            new WalletBalanceResponse(wallet.getBalance(), wallet.getCurrency(), txs));
    }

    @PostMapping("/topup")
    public ResponseEntity<WalletTopupResponse> topup(
            @Valid @RequestBody WalletTopupRequest request) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(topupOrchestrator.initiate(userId, request));
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String firebaseUid = (String) auth.getPrincipal();
        return userRepository.findByFirebaseUid(firebaseUid)
            .map(UserEntity::getId)
            .orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "Utilisateur introuvable", ""));
    }
}
