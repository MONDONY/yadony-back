package com.yadony.api.payments.wallet;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.dto.WalletBalanceResponse;
import com.yadony.api.payments.wallet.dto.WalletCurrencyBalanceDto;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTransactionDto;
import com.yadony.api.settings.UserBusinessPrefsService;
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
    private final UserBusinessPrefsService businessPrefsService;

    public WalletController(WalletService walletService,
                            WalletTopupOrchestrator topupOrchestrator,
                            UserRepository userRepository,
                            UserBusinessPrefsService businessPrefsService) {
        this.walletService = walletService;
        this.topupOrchestrator = topupOrchestrator;
        this.userRepository = userRepository;
        this.businessPrefsService = businessPrefsService;
    }

    @GetMapping("/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @RequestParam(defaultValue = "0") int page) {
        UUID userId = currentUserId();
        String activeCurrency = businessPrefsService.getPrefs(currentFirebaseUid()).currencyCode();
        WalletAccountEntity wallet = walletService.getOrCreate(userId, activeCurrency);
        List<WalletTransactionDto> txs = walletService.getTransactions(userId, page)
            .stream()
            .map(WalletTransactionDto::from)
            .collect(Collectors.toList());
        List<WalletCurrencyBalanceDto> balances = walletService.getAllBalances(userId)
            .stream()
            .map(w -> new WalletCurrencyBalanceDto(
                w.getCurrency(), w.getBalance(), w.getCurrency().equals(activeCurrency)))
            .collect(Collectors.toList());
        return ResponseEntity.ok(
            new WalletBalanceResponse(wallet.getBalance(), activeCurrency, txs, balances));
    }

    @PostMapping("/topup")
    public ResponseEntity<WalletTopupResponse> topup(
            @Valid @RequestBody WalletTopupRequest request) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(topupOrchestrator.initiate(userId, request));
    }

    private UUID currentUserId() {
        return userRepository.findByFirebaseUid(currentFirebaseUid())
            .map(UserEntity::getId)
            .orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "Utilisateur introuvable", ""));
    }

    private String currentFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (String) auth.getPrincipal();
    }
}
