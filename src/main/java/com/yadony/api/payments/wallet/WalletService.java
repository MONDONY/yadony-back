package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
    private final EntityManager entityManager;

    public WalletService(WalletAccountRepository walletAccountRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         WalletRefundRequestRepository walletRefundRequestRepository,
                         AuditService auditService,
                         EntityManager entityManager) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.walletRefundRequestRepository = walletRefundRequestRepository;
        this.auditService = auditService;
        this.entityManager = entityManager;
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
        if (isFrozen(userId, code)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-pending",
                    "Unprocessable", "Solde gelé - une demande de remboursement est en cours sur cette devise");
        }
    }

    /**
     * Vrai quand une demande de remboursement (PENDING ou PROCESSING) gèle cette devise :
     * exactement la règle qui fait échouer {@code debit} en 422 {@code wallet-refund-pending}.
     * Exposée pour qu'un appelant qui RÉPARTIT un prélèvement entre plusieurs portefeuilles
     * (cf. {@code WalletCommissionCollector}) puisse écarter d'avance un portefeuille gelé au
     * lieu de faire échouer tout le règlement dessus.
     */
    @Transactional(readOnly = true)
    public boolean isFrozen(UUID userId, String currency) {
        return walletRefundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(
                userId, normalize(currency), FREEZING_STATUSES);
    }

    /**
     * Solde d'un portefeuille lu SOUS VERROU ({@code PESSIMISTIC_WRITE}, tenu jusqu'à la fin
     * de la transaction appelante, d'où {@code MANDATORY}). Sert à décider une répartition
     * « tout ou rien » entre plusieurs portefeuilles avant de les débiter : le solde ne peut
     * plus bouger entre la lecture et les {@code debit} qui suivent dans la même transaction,
     * ce qui ferme la fenêtre TOCTOU où un second débit échouait après qu'un premier avait
     * été écrit (et conservé par {@code noRollbackFor}).
     *
     * <p>Un portefeuille absent est rendu comme vide (0) sans être créé : on ne verrouille
     * que ce qui existe, et créer une ligne ici réintroduirait, à l'intérieur de la
     * transaction appelante, la course sur {@code UNIQUE(user_id, currency)} que
     * {@link #getOrCreate} évite en {@code NOT_SUPPORTED}. Un débit ultérieur crée le
     * portefeuille s'il le faut (cas d'un débit à zéro). Appelants qui verrouillent plusieurs
     * portefeuilles : toujours dans le même ordre (codes devise triés) pour ne pas
     * s'interbloquer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal getBalanceForUpdate(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, normalize(currency))
                .map(WalletAccountEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    // NOT_SUPPORTED : appelé par le proxy (GET /wallet/balance), la création porte sa
    // propre transaction courte. Les appels internes (credit/debit) restent dans la
    // transaction de l'appelant (self-invocation sans proxy) : createOrReadExisting
    // est atomique dans les deux cas.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WalletAccountEntity getOrCreate(UUID userId, String currency) {
        String code = normalize(currency);
        return walletAccountRepository.findByUserIdAndCurrency(userId, code)
                .orElseGet(() -> createOrReadExisting(userId, code));
    }

    // INSERT ... ON CONFLICT DO NOTHING puis relecture. Un save() suivi d'un catch de
    // DataIntegrityViolationException ne tenait que hors transaction : dans celle d'un
    // appelant (credit, debit), l'INSERT partait au commit, hors du try, et la relecture
    // du catch tombait sur une transaction Postgres avortée.
    private WalletAccountEntity createOrReadExisting(UUID userId, String code) {
        walletAccountRepository.insertIfAbsent(UUID.randomUUID(), userId, code);
        return walletAccountRepository.findByUserIdAndCurrency(userId, code)
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet introuvable après création : user=" + userId + " currency=" + code));
    }

    /**
     * Solde du wallet dans la devise, zéro s'il n'existe pas encore. Lecture pure : ne crée
     * jamais le wallet (le créer ici exposait les appelants transactionnels à la course sur
     * l'index unique, et l'INSERT était perdu dans une transaction en lecture seule).
     */
    public BigDecimal getBalance(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, normalize(currency))
                .map(WalletAccountEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    public List<WalletAccountEntity> getAllBalances(UUID userId) {
        return walletAccountRepository.findAllByUserId(userId);
    }

    public void credit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                       String paymentRef, String idempotencyKey) {
        credit(userId, currency, amount, type, paymentRef, idempotencyKey, null);
    }

    /**
     * Crédit rattaché à un colis : {@code bidId} est écrit sur la ligne du grand livre
     * (FLUTTER-QP, lien « voir le colis » de l'historique du solde). {@code null} = pas de colis.
     */
    public void credit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                       String paymentRef, String idempotencyKey, UUID bidId) {
        String code = normalize(currency);
        // Verrou AVANT le test de clé : deux appels de même clé visent le même portefeuille, le
        // verrou les sérialise et le second voit la ligne du premier. Testée avant le verrou, la
        // clé laissait passer les deux, et le second butait sur UNIQUE(idempotency_key).
        WalletAccountEntity wallet = lockWallet(userId, code);
        if (isReplay(idempotencyKey, "credit")) {
            return;
        }

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
        tx.setBidId(bidId);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code,
                "paymentRef", String.valueOf(paymentRef)));
    }

    /**
     * Verrouille la ligne du portefeuille (la crée au besoin) et en rend l'état À JOUR.
     *
     * <p>Deux pièges, tous deux sources d'un solde faux :
     * <ul>
     *   <li>un portefeuille absent est créé par {@code INSERT ... ON CONFLICT DO NOTHING} : si un
     *       concurrent vient de le créer, l'INSERT n'en pose pas le verrou, il faut donc le
     *       reprendre par la requête {@code FOR UPDATE} ;</li>
     *   <li>une requête verrouillée rend l'entité DÉJÀ présente dans le cache de premier niveau
     *       sans recharger son état. Quand l'appelant a lu le portefeuille plus tôt dans sa
     *       transaction (solde périmé), l'UPDATE écrasait un mouvement commité entre-temps : le
     *       grand livre gardait les deux lignes, le solde n'en comptait qu'une (règle INV-01 du
     *       moniteur d'intégrité). {@code refresh} relit la ligne une fois le verrou acquis.</li>
     * </ul>
     * Réservé aux écritures : une transaction est obligatoire, le verrou vit jusqu'à son terme.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletAccountEntity lockWallet(UUID userId, String currency) {
        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseGet(() -> {
                    walletAccountRepository.insertIfAbsent(UUID.randomUUID(), userId, code);
                    return walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Wallet introuvable après création : user=" + userId + " currency=" + code));
                });
        entityManager.refresh(wallet);
        return wallet;
    }

    /** Vrai (et tracé) quand la clé d'idempotence a déjà produit une ligne : l'appel n'a alors aucun effet. */
    private boolean isReplay(String idempotencyKey, String operation) {
        if (idempotencyKey != null && walletTransactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.info("Idempotent {} ignored for key={}", operation, idempotencyKey);
            return true;
        }
        return false;
    }

    /**
     * Cœur unique de tous les débits. Ordre voulu : verrou, rejeu par clé, gel, solde. Le gel est
     * testé APRÈS le verrou : une demande de remboursement qui prend le verrou avant ce débit en
     * fait le gel visible, et un débit déjà en file derrière elle ne doit pas partir sur un
     * contrôle périmé. Les surcharges publiques ne portent que leur propre audit.
     *
     * @param auditExtras champs d'audit propres à la surcharge, ajoutés à {@code amount} et {@code currency}
     */
    private void debitInternal(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                               UUID bidId, String paymentRef, String idempotencyKey,
                               String sourceCurrency, BigDecimal sourceAmount, BigDecimal appliedRate,
                               Map<String, Object> auditExtras) {
        String code = normalize(currency);
        WalletAccountEntity wallet = lockWallet(userId, code);
        if (isReplay(idempotencyKey, "debit")) {
            return;
        }
        assertNotFrozen(userId, code);

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
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setSourceCurrency(sourceCurrency);
        tx.setSourceAmount(sourceAmount);
        tx.setAppliedRate(appliedRate);
        walletTransactionRepository.save(tx);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("amount", amount.toString());
        audit.put("currency", code);
        audit.putAll(auditExtras);
        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(), userId, audit);
    }

    private static Map<String, Object> extras(Object... keyValues) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            extras.put((String) keyValues[i], String.valueOf(keyValues[i + 1]));
        }
        return extras;
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId) {
        debitInternal(userId, currency, amount, type, bidId, null, null, null, null, null,
                extras("bidId", bidId));
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
        debitInternal(userId, currency, amount, type, bidId, null, null,
                sourceCurrency, sourceAmount, appliedRate,
                extras("bidId", bidId, "sourceCurrency", sourceCurrency, "sourceAmount", sourceAmount,
                        "appliedRate", appliedRate));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey) {
        debitInternal(userId, currency, amount, type, null, paymentRef, idempotencyKey, null, null, null,
                extras("paymentRef", paymentRef));
    }

    /**
     * Variante de {@link #debit(UUID, String, BigDecimal, WalletTransactionType, String, String)}
     * (paymentRef / idempotencyKey, utilisée par les fils de négociation) qui snapshote
     * une conversion de devise sur la transaction : même contrat que l'overload à
     * {@code bidId} : {@code sourceCurrency} / {@code sourceAmount} / {@code appliedRate}
     * sont figés au moment du prélèvement et ne doivent jamais être recalculés a
     * posteriori sur un changement ultérieur du taux administré.
     */
    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey,
                      String sourceCurrency, BigDecimal sourceAmount, BigDecimal appliedRate) {
        debitInternal(userId, currency, amount, type, null, paymentRef, idempotencyKey,
                sourceCurrency, sourceAmount, appliedRate,
                extras("paymentRef", paymentRef, "sourceCurrency", sourceCurrency,
                        "sourceAmount", sourceAmount, "appliedRate", appliedRate));
    }

    /**
     * Débit rattaché à un colis ET idempotent par clé : {@code bidId} et {@code paymentRef} sont
     * écrits sur la ligne, une ligne portant déjà {@code idempotencyKey} rend l'appel sans effet.
     * Conversion snapshotée comme les autres variantes ({@code sourceCurrency},
     * {@code sourceAmount}, {@code appliedRate} nuls quand rien n'est converti). Sert à la
     * commission du supplément de prix (une clé par portefeuille débité).
     */
    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId,
                      String paymentRef, String idempotencyKey,
                      String sourceCurrency, BigDecimal sourceAmount, BigDecimal appliedRate) {
        Map<String, Object> extras = extras("bidId", bidId, "paymentRef", paymentRef);
        if (sourceCurrency != null) {
            extras.putAll(extras("sourceCurrency", sourceCurrency, "sourceAmount", sourceAmount,
                    "appliedRate", appliedRate));
        }
        debitInternal(userId, currency, amount, type, bidId, paymentRef, idempotencyKey,
                sourceCurrency, sourceAmount, appliedRate, extras);
    }

    /**
     * Débit d'un remboursement déjà confirmé (Stripe ou admin) : ignore volontairement
     * {@code assertNotFrozen}, puisque c'est la demande en cours qui gèle la devise.
     *
     * <p>Le solde est tout de même vérifié : entre la demande et la confirmation du webhook,
     * il a pu baisser (résolution d'un ticket concurrent, débit admin). Sans cette garde le
     * wallet passait en négatif silencieusement, et le rejeu du ledger
     * ({@link WalletRefundAllocator}) ne retombait plus jamais sur le solde. Le remboursement
     * Stripe étant irréversible, l'écart doit remonter plutôt que s'écrire.
     *
     * @throws InsufficientWalletBalanceException 422 si le solde ne couvre pas {@code amount}
     */
    @Transactional
    public void debitConfirmedRefund(UUID userId, String currency, BigDecimal amount, WalletTransactionType type) {
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
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
                userId, Map.of("amount", amount.toString(), "currency", code));
    }
}
