package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletAccountRepository walletAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AuditService auditService;

    public WalletService(WalletAccountRepository walletAccountRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         AuditService auditService) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.auditService = auditService;
    }

    /**
     * Normalise un code devise avant toute lecture ou écriture du portefeuille.
     *
     * <p>Deux conventions de casse coexistent dans l'application : les colonnes
     * {@code currency} des tables métier n'acceptent que des codes en majuscules
     * (contraintes CHECK des migrations V198 à V200), tandis que
     * {@link com.yadony.api.payments.currency.SupportedCurrency#code()} expose la
     * forme minuscule qu'attend Stripe.
     *
     * <p>Sans cette normalisation, un appelant transmettant « eur » ouvrirait un
     * second portefeuille pour une devise déjà détenue : la contrainte
     * {@code UNIQUE(user_id, currency)} compare des chaînes et ne voit pas que
     * « eur » et « EUR » désignent la même devise. Le solde de l'utilisateur se
     * retrouverait éclaté sur deux comptes, et un débit pourrait échouer pour
     * solde insuffisant alors que l'argent est bien là.
     */
    private static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return ActiveCurrencyResolver.DEFAULT_CURRENCY;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    public WalletAccountEntity getOrCreate(UUID userId, String currency) {
        String code = normalize(currency);
        return walletAccountRepository.findByUserIdAndCurrency(userId, code).orElseGet(() -> {
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setUserId(userId);
            wallet.setCurrency(code);
            return walletAccountRepository.save(wallet);
        });
    }

    public BigDecimal getBalance(UUID userId, String currency) {
        return getOrCreate(userId, currency).getBalance();
    }

    public List<WalletAccountEntity> getAllBalances(UUID userId) {
        return walletAccountRepository.findAllByUserId(userId);
    }

    public List<WalletTransactionEntity> getTransactions(UUID userId, int page) {
        return walletTransactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 50))
                .getContent();
    }

    public void credit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                       String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                    walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent credit ignored for key={}", idempotencyKey);
                return;
            }
        }

        String code = normalize(currency);
        WalletAccountEntity wallet = getOrCreate(userId, code);
        BigDecimal newBalance = wallet.getBalance().add(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code,
                "paymentRef", String.valueOf(paymentRef)));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId) {
        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setBidId(bidId);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code, "bidId", String.valueOf(bidId)));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                    walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent debit ignored for key={}", idempotencyKey);
                return;
            }
        }

        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code,
                "paymentRef", String.valueOf(paymentRef)));
    }
}
