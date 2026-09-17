package com.yadony.api.payments.wallet;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersRequest;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.wallet.dto.WalletBalanceResponse;
import com.yadony.api.payments.wallet.dto.WalletCurrencyBalanceDto;
import com.yadony.api.payments.wallet.dto.WalletEligibleTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletRefundRequestSummaryResponse;
import com.yadony.api.payments.wallet.dto.WalletRefundSelectionRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupCheckoutRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupCheckoutResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupStatusResponse;
import com.yadony.api.payments.wallet.dto.WalletTransactionDto;
import com.yadony.api.settings.UserBusinessPrefsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/wallet")
@PreAuthorize("isAuthenticated()")
public class WalletController {

    private final WalletService walletService;
    private final WalletSelfRefundService walletSelfRefundService;
    private final WalletTopupOrchestrator topupOrchestrator;
    private final WalletMobileMoneyTopupService mobileMoneyTopupService;
    private final UserRepository userRepository;
    private final UserBusinessPrefsService businessPrefsService;

    public WalletController(WalletService walletService,
                            WalletSelfRefundService walletSelfRefundService,
                            WalletTopupOrchestrator topupOrchestrator,
                            WalletMobileMoneyTopupService mobileMoneyTopupService,
                            UserRepository userRepository,
                            UserBusinessPrefsService businessPrefsService) {
        this.walletService = walletService;
        this.walletSelfRefundService = walletSelfRefundService;
        this.topupOrchestrator = topupOrchestrator;
        this.mobileMoneyTopupService = mobileMoneyTopupService;
        this.userRepository = userRepository;
        this.businessPrefsService = businessPrefsService;
    }

    @GetMapping("/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @RequestParam(defaultValue = "0") int page) {
        UUID userId = currentUserId();
        String activeCurrency = businessPrefsService.getPrefs(currentFirebaseUid()).currencyCode();
        WalletAccountEntity wallet = walletService.getOrCreate(userId, activeCurrency);
        List<WalletTransactionEntity> transactions = walletService.getTransactions(userId, page);
        Map<UUID, String> refundStatusByTxId = walletSelfRefundService.refundStatusByTransactionId(
                transactions.stream().map(WalletTransactionEntity::getId).toList());
        Map<UUID, WalletSelfRefundService.RefundFeeBreakdown> feesByTxId =
                walletSelfRefundService.refundFeesByTransactionId(userId, transactions);
        List<WalletTransactionDto> txs = transactions
            .stream()
            .map(tx -> {
                WalletSelfRefundService.RefundFeeBreakdown fees = feesByTxId.get(tx.getId());
                return WalletTransactionDto.from(tx, refundStatusByTxId.get(tx.getId()),
                        fees == null ? null : fees.feeAmount(), fees == null ? null : fees.netAmount());
            })
            .collect(Collectors.toList());
        List<WalletCurrencyBalanceDto> balances = walletService.getAllBalances(userId)
            .stream()
            .map(w -> {
                WalletRefundAllocation a = safeAllocation(userId, w.getCurrency());
                // isEligible reçoit l'allocation déjà calculée : la recalculer ici rejouerait
                // tout le ledger une seconde fois, pour chaque devise du portefeuille.
                // Net nul (tout le remboursable part en frais) : rien ne repartirait, bouton inactif.
                boolean eligible = walletSelfRefundService.isEligible(userId, w.getCurrency(), a)
                        && a.net().signum() > 0;
                // equalsIgnoreCase : les portefeuilles antérieurs à V202 peuvent
                // encore porter une casse mixte, et un simple equals aurait
                // affiché « aucun portefeuille actif » à leur propriétaire.
                return new WalletCurrencyBalanceDto(
                        w.getCurrency(), w.getBalance(), w.getCurrency().equalsIgnoreCase(activeCurrency),
                        eligible, a.refundableTotal(), a.nonRefundable(), a.fees(), a.net());
            })
            .collect(Collectors.toList());
        boolean activeEligible = balances.stream()
                .filter(WalletCurrencyBalanceDto::active)
                .anyMatch(WalletCurrencyBalanceDto::refundEligible);
        return ResponseEntity.ok(
            new WalletBalanceResponse(wallet.getBalance(), activeCurrency, txs, balances, activeEligible));
    }

    /** Un ledger incohérent ne doit pas casser l'écran portefeuille : on affiche 0 remboursable. */
    private WalletRefundAllocation safeAllocation(UUID userId, String currency) {
        try {
            return walletSelfRefundService.allocation(userId, currency);
        } catch (WalletAllocationInvariantException e) {
            return WalletRefundAllocation.empty();
        }
    }

    @PostMapping("/topup")
    public ResponseEntity<WalletTopupResponse> topup(
            @Valid @RequestBody WalletTopupRequest request) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(topupOrchestrator.initiate(userId, request));
    }

    /**
     * Réseaux mobile money utilisables pour payer une recharge depuis ce numéro. POST et non
     * GET pour la même raison que {@code /payments/mobile-money/providers} : le numéro voyage
     * dans le corps, jamais dans une URL (journaux nginx, historique du client).
     *
     * <p>Corps facultatif, comme sur le jumeau du versement : un corps absent n'est pas une
     * requête malformée (400) mais un numéro manquant, et le service le refuse en 422
     * {@code topup-phone-required} — le même code que pour un numéro vide, donc un seul
     * message à afficher côté app. Pas de {@code @Valid} ici : le record ne porte aucune
     * contrainte, l'annotation ne ferait que laisser croire à une garde qui n'existe pas.
     */
    @PostMapping("/topup/providers")
    public ResponseEntity<MobileMoneyProvidersResponse> topupProviders(
            @RequestBody(required = false) MobileMoneyProvidersRequest body) {
        return ResponseEntity.ok(mobileMoneyTopupService.providers(body == null ? null : body.phoneNumber()));
    }

    /**
     * Statut d'une recharge mobile money, relu en boucle par l'app pendant l'attente du PIN.
     * L'identifiant de l'appelant est passé au service, qui filtre par propriétaire : une
     * recharge d'un autre utilisateur est introuvable (404), jamais « interdite ».
     */
    @GetMapping("/topup/{topupId}/status")
    public ResponseEntity<WalletTopupStatusResponse> topupStatus(@PathVariable UUID topupId) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(mobileMoneyTopupService.status(userId, topupId));
    }

    /** Recharge par carte depuis le portail PRO : session Stripe Checkout hébergée. */
    @PostMapping("/topup/checkout-session")
    public ResponseEntity<WalletTopupCheckoutResponse> topupCheckoutSession(
            @Valid @RequestBody WalletTopupCheckoutRequest request) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(topupOrchestrator.createCheckoutSession(userId, request.amount()));
    }

    @GetMapping("/{currency}/refund-eligible-topups")
    public ResponseEntity<List<WalletEligibleTopupResponse>> refundEligibleTopups(@PathVariable String currency) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(walletSelfRefundService.listEligibleTopups(userId, currency).stream()
                .map(WalletEligibleTopupResponse::from)
                .collect(Collectors.toList()));
    }

    @PostMapping("/{currency}/refund-request")
    public ResponseEntity<WalletRefundRequestSummaryResponse> requestRefund(
            @PathVariable String currency,
            @RequestBody(required = false) WalletRefundSelectionRequest request) {
        UUID userId = currentUserId();
        List<UUID> transactionIds = request == null ? List.of() : request.transactionIds();
        WalletRefundRequestEntity created = walletSelfRefundService.request(userId, currency, transactionIds);
        return ResponseEntity.ok(summaries(List.of(created)).get(0));
    }

    @GetMapping("/refund-requests")
    public ResponseEntity<List<WalletRefundRequestSummaryResponse>> listRefundRequests() {
        UUID userId = currentUserId();
        return ResponseEntity.ok(summaries(walletSelfRefundService.listForUser(userId)));
    }

    private List<WalletRefundRequestSummaryResponse> summaries(List<WalletRefundRequestEntity> requests) {
        return walletSelfRefundService.details(requests).stream()
                .map(d -> WalletRefundRequestSummaryResponse.from(d.request(), d.items(), d.destinationMasked()))
                .collect(Collectors.toList());
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
