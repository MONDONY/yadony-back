package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final WalletRefundRequestRepository walletRefundRequestRepository;
    private final AuditService auditService;

    public WalletService(WalletAccountRepository walletAccountRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         WalletRefundRequestRepository walletRefundRequestRepository,
                         AuditService auditService) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.walletRefundRequestRepository = walletRefundRequestRepository;
        this.auditService = auditService;
    }

    private static final List<WalletRefundRequestStatus> FREEZING_STATUSES =
            List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING);

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

    private void assertNotFrozen(UUID userId, String code) {
        if (walletRefundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(userId, code, FREEZING_STATUSES)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-pending",
                    "Unprocessable", "Solde gelé - une demande de remboursement est en cours sur cette devise");
        }
    }

    private void applyEligibilityOnCredit(WalletAccountEntity wallet, WalletTransactionType type, BigDecimal amount) {
        if (type == WalletTransactionType.TOP_UP) {
            wallet.setRefundEligibleAmount(wallet.getRefundEligibleAmount().add(amount));
        } else {
            taintEligibility(wallet);
        }
    }

    private void taintEligibility(WalletAccountEntity wallet) {
        wallet.setRefundEligibleAmount(BigDecimal.ZERO);
        wallet.setRefundEligibleSince(Instant.now());
    }

    public WalletAccountEntity getOrCreate(UUID userId, String currency) {
        String code = normalize(currency);
        return walletAccountRepository.findByUserIdAndCurrency(userId, code).orElseGet(() -> {
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setUserId(userId);
            wallet.setCurrency(code);
            wallet.setRefundEligibleSince(Instant.now());
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
        applyEligibilityOnCredit(wallet, type, amount);
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
        assertNotFrozen(userId, code);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        taintEligibility(wallet);
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

    /**
     * Variante de {@link #debit(UUID, String, BigDecimal, WalletTransactionType, UUID)} qui
     * snapshote une conversion de devise sur la transaction : {@code sourceCurrency} /
     * {@code sourceAmount} portent le montant avant conversion (devise de l'annonce),
     * {@code appliedRate} le taux réellement appliqué au moment du prélèvement. Ces trois
     * colonnes sont figées ici, une fois pour toutes : un changement ultérieur du taux
     * administré dans {@code exchange_rates} ne doit jamais rejaillir sur cette
     * transaction déjà persistée. À utiliser uniquement quand une conversion a
     * effectivement eu lieu ; sinon utiliser l'overload sans ces paramètres.
     */
    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId,
                      String sourceCurrency, BigDecimal sourceAmount, BigDecimal appliedRate) {
        String code = normalize(currency);
        assertNotFrozen(userId, code);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        taintEligibility(wallet);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setBidId(bidId);
        tx.setSourceCurrency(sourceCurrency);
        tx.setSourceAmount(sourceAmount);
        tx.setAppliedRate(appliedRate);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code, "bidId", String.valueOf(bidId),
                "sourceCurrency", String.valueOf(sourceCurrency), "sourceAmount", String.valueOf(sourceAmount),
                "appliedRate", String.valueOf(appliedRate)));
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
        assertNotFrozen(userId, code);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        taintEligibility(wallet);
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

    /**
     * Variante de {@link #debit(UUID, String, BigDecimal, WalletTransactionType, String, String)}
     * (paymentRef / idempotencyKey, utilisée par les fils de négociation) qui snapshote
     * une conversion de devise sur la transaction — même contrat que l'overload à
     * {@code bidId} : {@code sourceCurrency} / {@code sourceAmount} / {@code appliedRate}
     * sont figés au moment du prélèvement et ne doivent jamais être recalculés a
     * posteriori sur un changement ultérieur du taux administré.
     */
    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey,
                      String sourceCurrency, BigDecimal sourceAmount, BigDecimal appliedRate) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                    walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent debit ignored for key={}", idempotencyKey);
                return;
            }
        }

        String code = normalize(currency);
        assertNotFrozen(userId, code);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        taintEligibility(wallet);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setSourceCurrency(sourceCurrency);
        tx.setSourceAmount(sourceAmount);
        tx.setAppliedRate(appliedRate);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code,
                "paymentRef", String.valueOf(paymentRef),
                "sourceCurrency", String.valueOf(sourceCurrency), "sourceAmount", String.valueOf(sourceAmount),
                "appliedRate", String.valueOf(appliedRate)));
    }

    @Transactional
    public void debitConfirmedRefund(UUID userId, String currency, BigDecimal amount, WalletTransactionType type) {
        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> getOrCreate(userId, code));

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        wallet.setRefundEligibleAmount(wallet.getRefundEligibleAmount().subtract(amount).max(BigDecimal.ZERO));
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code));
    }
}
