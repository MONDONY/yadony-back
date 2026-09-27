package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletAdminAdjustmentServiceTest {

    @Mock WalletAccountRepository accountRepository;
    @Mock WalletTransactionRepository transactionRepository;
    @Mock WalletService walletService;
    @Mock WalletSelfRefundService selfRefundService;
    @Mock ExchangeRateService exchangeRateService;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    WalletAdminAdjustmentService service;

    static final UUID USER_ID = UUID.randomUUID();
    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String REASON = "Correction d'une recharge en double";

    @BeforeEach
    void setUp() {
        service = new WalletAdminAdjustmentService(accountRepository, transactionRepository, walletService,
                selfRefundService, exchangeRateService, auditService, eventPublisher);
        lenient().when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private static <T> T withId(T entity) {
        try {
            Class<?> type = entity.getClass();
            Field f = null;
            while (f == null && type != null) {
                try {
                    f = type.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            f.setAccessible(true);
            if (f.get(entity) == null) f.set(entity, UUID.randomUUID());
            return entity;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = withId(new WalletAccountEntity());
        w.setUserId(USER_ID);
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private static AdjustmentCommand cmd(String currency, String direction, String amount, String reason, String key) {
        return new AdjustmentCommand(USER_ID, ADMIN_ID, currency, direction,
                amount == null ? null : new BigDecimal(amount), reason, key);
    }

    private static AdjustmentCommand credit(String currency, String amount) {
        return cmd(currency, "CREDIT", amount, REASON, "key-1");
    }

    private static AdjustmentCommand debit(String currency, String amount) {
        return cmd(currency, "DEBIT", amount, REASON, "key-1");
    }

    private void locked(WalletAccountEntity w) {
        when(accountRepository.findByUserIdAndCurrencyForUpdate(USER_ID, w.getCurrency())).thenReturn(Optional.of(w));
    }

    private static YadonyBusinessException business(Throwable t) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        return (YadonyBusinessException) t;
    }

    private static WalletTransactionEntity existingAdjustment(String currency, WalletTransactionType type,
                                                             String signedAmount, String reason) {
        WalletTransactionEntity tx = withId(new WalletTransactionEntity());
        tx.setUserId(USER_ID);
        tx.setCurrency(currency);
        tx.setType(type);
        tx.setAmount(new BigDecimal(signedAmount));
        tx.setBalanceAfter(new BigDecimal("100.00"));
        tx.setIdempotencyKey("admin-adjust:key-1");
        tx.setAdminReason(reason);
        tx.setAdminActorId(ADMIN_ID);
        return tx;
    }

    // ── Crédit ───────────────────────────────────────────────────────────────

    @Test
    void credit_augmenteLeSoldeJournaliseEtPublie() {
        WalletAccountEntity w = wallet("EUR", "10.00");
        locked(w);

        AdjustmentResult result = service.adjust(credit("eur", "25.50"));

        assertThat(result.replayed()).isFalse();
        assertThat(w.getBalance()).isEqualByComparingTo("35.50");
        WalletTransactionEntity tx = result.transaction();
        assertThat(tx.getType()).isEqualTo(WalletTransactionType.ADMIN_CREDIT);
        assertThat(tx.getAmount()).isEqualByComparingTo("25.50");
        assertThat(tx.getBalanceAfter()).isEqualByComparingTo("35.50");
        assertThat(tx.getCurrency()).isEqualTo("EUR");
        assertThat(tx.getUserId()).isEqualTo(USER_ID);
        assertThat(tx.getAdminReason()).isEqualTo(REASON);
        assertThat(tx.getAdminActorId()).isEqualTo(ADMIN_ID);
        assertThat(tx.getIdempotencyKey()).isEqualTo("admin-adjust:key-1");
        assertThat(tx.getPaymentRef()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("wallet"), eq(w.getId()), eq("WALLET_ADMIN_CREDIT"), eq(ADMIN_ID), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("userId", USER_ID.toString())
                .containsEntry("currency", "EUR")
                .containsEntry("amount", "25.50")
                .containsEntry("balanceBefore", "10.00")
                .containsEntry("balanceAfter", "35.50")
                .containsEntry("reason", REASON)
                .containsEntry("idempotencyKey", "key-1")
                .containsEntry("transactionId", tx.getId().toString());

        ArgumentCaptor<WalletAdjustedByAdminEvent> event = ArgumentCaptor.forClass(WalletAdjustedByAdminEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        assertThat(event.getValue().currency()).isEqualTo("EUR");
        assertThat(event.getValue().direction()).isEqualTo(WalletAdjustmentDirection.CREDIT);
        assertThat(event.getValue().amount()).isEqualByComparingTo("25.50");
        assertThat(event.getValue().transactionId()).isEqualTo(tx.getId());
    }

    @Test
    void credit_surCompteExistant_nAppellePasGetOrCreate() {
        WalletAccountEntity w = wallet("EUR", "10.00");
        locked(w);

        service.adjust(credit("EUR", "5"));

        verify(walletService, never()).getOrCreate(any(), anyString());
    }

    @Test
    void credit_neTouchePasAuMontantRemboursableStocke() {
        WalletAccountEntity w = wallet("EUR", "10.00");
        w.setRefundEligibleAmount(new BigDecimal("4.00"));
        locked(w);

        service.adjust(credit("EUR", "20"));

        assertThat(w.getRefundEligibleAmount()).isEqualByComparingTo("4.00");
    }

    @Test
    void credit_surDeviseSansCompte_creeLeCompte() {
        WalletAccountEntity created = wallet("XOF", "0");
        when(accountRepository.findByUserIdAndCurrencyForUpdate(USER_ID, "XOF"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        when(exchangeRateService.toEurPivot(new BigDecimal("1500"), "XOF")).thenReturn(new BigDecimal("2.2867"));

        AdjustmentResult result = service.adjust(credit("XOF", "1500"));

        verify(walletService).getOrCreate(USER_ID, "XOF");
        assertThat(created.getBalance()).isEqualByComparingTo("1500");
        assertThat(result.transaction().getType()).isEqualTo(WalletTransactionType.ADMIN_CREDIT);
    }

    @Test
    void credit_surPortefeuilleGele_refuse422() {
        WalletAccountEntity w = wallet("EUR", "10.00");
        locked(w);
        when(walletService.isFrozen(USER_ID, "EUR")).thenReturn(true);

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("EUR", "5"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("wallet-refund-pending");
        assertThat(w.getBalance()).isEqualByComparingTo("10.00");
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    // ── Débit ────────────────────────────────────────────────────────────────

    @Test
    void debit_diminueLeSoldeAvecMontantNegatif() {
        WalletAccountEntity w = wallet("EUR", "30.00");
        locked(w);

        AdjustmentResult result = service.adjust(debit("EUR", "12.25"));

        assertThat(w.getBalance()).isEqualByComparingTo("17.75");
        assertThat(result.transaction().getType()).isEqualTo(WalletTransactionType.ADMIN_DEBIT);
        assertThat(result.transaction().getAmount()).isEqualByComparingTo("-12.25");
        assertThat(result.transaction().getBalanceAfter()).isEqualByComparingTo("17.75");
        verify(auditService).log(eq("wallet"), eq(w.getId()), eq("WALLET_ADMIN_DEBIT"), eq(ADMIN_ID), any());
        verify(walletService, never()).getOrCreate(any(), anyString());
    }

    @Test
    void debit_ramenerLeMontantRemboursableStockeSousLeNouveauSolde() {
        WalletAccountEntity w = wallet("EUR", "30.00");
        w.setRefundEligibleAmount(new BigDecimal("25.00"));
        locked(w);

        service.adjust(debit("EUR", "20"));

        assertThat(w.getRefundEligibleAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void debit_laisseLeMontantRemboursableStockeQuandIlResteSousLeSolde() {
        WalletAccountEntity w = wallet("EUR", "30.00");
        w.setRefundEligibleAmount(new BigDecimal("5.00"));
        locked(w);

        service.adjust(debit("EUR", "20"));

        assertThat(w.getRefundEligibleAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void debit_auDelaDuSolde_refuse422SoldeInsuffisant() {
        WalletAccountEntity w = wallet("XOF", "1000");
        locked(w);
        when(exchangeRateService.toEurPivot(any(), eq("XOF"))).thenReturn(new BigDecimal("2.2867"));

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(debit("XOF", "1500"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("insufficient-wallet-balance");
        assertThat(e.getMessage()).doesNotContain("€").doesNotContain("—");
        assertThat(e.getProperties()).containsEntry("availableBalance", "1000").containsEntry("currency", "XOF");
        assertThat(w.getBalance()).isEqualByComparingTo("1000");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void debit_sansCompte_refuse422() {
        when(accountRepository.findByUserIdAndCurrencyForUpdate(USER_ID, "CAD")).thenReturn(Optional.empty());
        when(exchangeRateService.toEurPivot(any(), eq("CAD"))).thenReturn(new BigDecimal("3.40"));

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(debit("CAD", "5"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("wallet-account-not-found");
        verify(walletService, never()).getOrCreate(any(), anyString());
    }

    @Test
    void debit_surPortefeuilleGele_refuse422() {
        WalletAccountEntity w = wallet("EUR", "30.00");
        locked(w);
        when(walletService.isFrozen(USER_ID, "EUR")).thenReturn(true);

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(debit("EUR", "5"))));

        assertThat(e.getErrorCode()).isEqualTo("wallet-refund-pending");
        assertThat(w.getBalance()).isEqualByComparingTo("30.00");
    }

    // ── Plafond ──────────────────────────────────────────────────────────────

    @Test
    void plafond_eurAuDelaDe500_refuse422() {
        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("EUR", "500.01"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-cap-exceeded");
        verifyNoInteractions(exchangeRateService);
        verify(accountRepository, never()).findByUserIdAndCurrencyForUpdate(any(), anyString());
    }

    @Test
    void plafond_eurExactement500_accepte() {
        WalletAccountEntity w = wallet("EUR", "0");
        locked(w);

        service.adjust(credit("EUR", "500"));

        assertThat(w.getBalance()).isEqualByComparingTo("500");
    }

    @Test
    void plafond_xofConvertiAuTauxDeLaTable() {
        // 327 958 F CFA = 499,97 € au taux fixe 655,957 : accepté ; 330 000 F CFA = 503,08 € : refusé.
        when(exchangeRateService.toEurPivot(new BigDecimal("330000"), "XOF")).thenReturn(new BigDecimal("503.0818"));

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("XOF", "330000"))));

        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-cap-exceeded");
        assertThat(e.getProperties()).containsEntry("capEur", "500").containsEntry("eurEquivalent", "503.0818");
    }

    @Test
    void plafond_sansTauxPourLaDevise_propageLe422ExchangeRateMissing() {
        when(exchangeRateService.toEurPivot(any(), eq("CHF"))).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, ExchangeRateService.RATE_MISSING_CODE, "Exchange Rate Missing",
                "Aucun taux de change n'est configure pour la devise CHF"));

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("CHF", "10"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("exchange-rate-missing");
        verify(accountRepository, never()).findByUserIdAndCurrencyForUpdate(any(), anyString());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void raison_tropCourteApresTrim_refuse400() {
        YadonyBusinessException e = business(catchThrowable(() ->
                service.adjust(cmd("EUR", "CREDIT", "5", "   court    ", "key-1"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-reason-invalid");
    }

    @Test
    void raison_absenteOuTropLongue_refuse400() {
        assertThat(business(catchThrowable(() -> service.adjust(cmd("EUR", "CREDIT", "5", null, "k"))))
                .getErrorCode()).isEqualTo("wallet-adjustment-reason-invalid");
        assertThat(business(catchThrowable(() -> service.adjust(cmd("EUR", "CREDIT", "5", "x".repeat(501), "k"))))
                .getErrorCode()).isEqualTo("wallet-adjustment-reason-invalid");
    }

    @Test
    void raison_estStockeeTrimmee() {
        WalletAccountEntity w = wallet("EUR", "0");
        locked(w);

        AdjustmentResult r = service.adjust(cmd("EUR", "CREDIT", "5", "   " + REASON + "  ", "key-1"));

        assertThat(r.transaction().getAdminReason()).isEqualTo(REASON);
    }

    @Test
    void montant_nulNegatifOuAbsent_refuse400() {
        for (String amount : new String[]{null, "0", "-3"}) {
            YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("EUR", amount))));
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-amount-invalid");
        }
    }

    @Test
    void montant_decimalesAuDelaDeLaDevise_refuse400() {
        assertThat(business(catchThrowable(() -> service.adjust(credit("XOF", "1500.5")))).getErrorCode())
                .isEqualTo("wallet-adjustment-amount-invalid");
        assertThat(business(catchThrowable(() -> service.adjust(credit("EUR", "1.005")))).getErrorCode())
                .isEqualTo("wallet-adjustment-amount-invalid");
    }

    @Test
    void montant_zerosDecimauxInutilesAcceptesEnXof() {
        WalletAccountEntity w = wallet("XOF", "0");
        locked(w);
        when(exchangeRateService.toEurPivot(any(), eq("XOF"))).thenReturn(new BigDecimal("2.2867"));

        service.adjust(credit("XOF", "1500.00"));

        assertThat(w.getBalance()).isEqualByComparingTo("1500");
    }

    @Test
    void direction_inconnue_refuse400() {
        YadonyBusinessException e = business(catchThrowable(() ->
                service.adjust(cmd("EUR", "REFUND", "5", REASON, "key-1"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-direction-invalid");
    }

    @Test
    void devise_nonSupportee_refuse400() {
        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("JPY", "5"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-currency-unsupported");
    }

    @Test
    void cleIdempotence_absenteOuTropLongue_refuse400() {
        assertThat(business(catchThrowable(() -> service.adjust(cmd("EUR", "CREDIT", "5", REASON, " "))))
                .getErrorCode()).isEqualTo("idempotency-key-required");
        assertThat(business(catchThrowable(() -> service.adjust(cmd("EUR", "CREDIT", "5", REASON, "k".repeat(201)))))
                .getErrorCode()).isEqualTo("idempotency-key-invalid");
    }

    // ── Idempotence ──────────────────────────────────────────────────────────

    @Test
    void rejeu_memeCleMemeContenu_rendLeMouvementSansReecrire() {
        WalletTransactionEntity existing = existingAdjustment("EUR", WalletTransactionType.ADMIN_CREDIT, "25.50", REASON);
        when(transactionRepository.findByIdempotencyKey("admin-adjust:key-1")).thenReturn(Optional.of(existing));

        AdjustmentResult result = service.adjust(cmd("eur", "CREDIT", "25.5", "  " + REASON, "key-1"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(existing);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).findByUserIdAndCurrencyForUpdate(any(), anyString());
        verifyNoInteractions(auditService, eventPublisher, walletService);
    }

    @Test
    void rejeu_memeCleContenuDifferent_refuse409() {
        WalletTransactionEntity existing = existingAdjustment("EUR", WalletTransactionType.ADMIN_CREDIT, "25.50", REASON);
        when(transactionRepository.findByIdempotencyKey("admin-adjust:key-1")).thenReturn(Optional.of(existing));

        for (AdjustmentCommand other : List.of(
                cmd("EUR", "CREDIT", "25.51", REASON, "key-1"),
                cmd("EUR", "DEBIT", "25.50", REASON, "key-1"),
                cmd("CAD", "CREDIT", "25.50", REASON, "key-1"),
                cmd("EUR", "CREDIT", "25.50", REASON + " bis", "key-1"),
                new AdjustmentCommand(UUID.randomUUID(), ADMIN_ID, "EUR", "CREDIT", new BigDecimal("25.50"),
                        REASON, "key-1"))) {
            YadonyBusinessException e = business(catchThrowable(() -> service.adjust(other)));
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-idempotency-conflict");
        }
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejeu_cleDejaPriseParUnMouvementNonAdmin_refuse409() {
        // Le préfixe « admin-adjust: » isole les clés admin des clés système (« pawapay-topup-… ») :
        // un mouvement non-admin ne peut porter cette clé, mais on le traite quand même en conflit.
        WalletTransactionEntity existing = existingAdjustment("EUR", WalletTransactionType.TOP_UP, "25.50", null);
        when(transactionRepository.findByIdempotencyKey("admin-adjust:key-1")).thenReturn(Optional.of(existing));

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("EUR", "25.50"))));

        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-idempotency-conflict");
    }

    @Test
    void rejeu_concurrentDetecteApresLeVerrou() {
        // Deux requêtes de même clé : la seconde rate la lecture initiale, attend le verrou du
        // compte, puis doit voir le mouvement écrit par la première au lieu d'en écrire un second.
        WalletAccountEntity w = wallet("EUR", "35.50");
        locked(w);
        WalletTransactionEntity existing = existingAdjustment("EUR", WalletTransactionType.ADMIN_CREDIT, "25.50", REASON);
        when(transactionRepository.findByIdempotencyKey("admin-adjust:key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        AdjustmentResult result = service.adjust(credit("EUR", "25.50"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(existing);
        assertThat(w.getBalance()).isEqualByComparingTo("35.50");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejeu_violationUniqueAuFlush_rendUn409() {
        WalletAccountEntity w = wallet("EUR", "10.00");
        locked(w);
        doThrow(new DataIntegrityViolationException("uq")).when(transactionRepository).saveAndFlush(any());

        YadonyBusinessException e = business(catchThrowable(() -> service.adjust(credit("EUR", "5"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("wallet-adjustment-idempotency-conflict");
        verifyNoInteractions(auditService, eventPublisher);
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    void comptes_exposentLeRemboursableDuRejeuEtLeGel() {
        WalletAccountEntity eur = wallet("EUR", "12.50");
        WalletAccountEntity xof = wallet("XOF", "3000");
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(xof, eur));
        when(selfRefundService.allocation(USER_ID, "EUR")).thenReturn(new WalletRefundAllocation(List.of(),
                new BigDecimal("10.00"), new BigDecimal("2.50"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00")));
        when(selfRefundService.allocation(USER_ID, "XOF")).thenThrow(
                new WalletAllocationInvariantException(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO));
        when(walletService.isFrozen(USER_ID, "EUR")).thenReturn(false);
        when(walletService.isFrozen(USER_ID, "XOF")).thenReturn(true);

        List<WalletAccountView> views = service.accounts(USER_ID);

        assertThat(views).extracting(WalletAccountView::currency).containsExactly("EUR", "XOF");
        assertThat(views.get(0).balance()).isEqualByComparingTo("12.50");
        assertThat(views.get(0).refundEligibleAmount()).isEqualByComparingTo("10.00");
        assertThat(views.get(0).frozen()).isFalse();
        // Ledger incohérent : on n'invente pas de montant remboursable.
        assertThat(views.get(1).refundEligibleAmount()).isNull();
        assertThat(views.get(1).frozen()).isTrue();
    }

    @Test
    void compte_absent_estNul() {
        when(accountRepository.findByUserIdAndCurrency(USER_ID, "CAD")).thenReturn(Optional.empty());

        assertThat(service.account(USER_ID, "cad")).isNull();
    }

    @Test
    void mouvements_filtrentParDeviseEtTypeTriesParDateDecroissante() {
        Page<WalletTransactionEntity> page = new PageImpl<>(List.of());
        when(transactionRepository.findByUserIdAndCurrencyAndType(eq(USER_ID), eq("XOF"),
                eq(WalletTransactionType.ADMIN_CREDIT), any(Pageable.class))).thenReturn(page);

        assertThat(service.transactions(USER_ID, "xof", "admin_credit", 2, 20)).isSameAs(page);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByUserIdAndCurrencyAndType(eq(USER_ID), eq("XOF"),
                eq(WalletTransactionType.ADMIN_CREDIT), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void mouvements_sansFiltre_etTailleBornee() {
        when(transactionRepository.findByUserId(eq(USER_ID), any(Pageable.class))).thenReturn(Page.empty());
        service.transactions(USER_ID, null, " ", -1, 1000);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByUserId(eq(USER_ID), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void mouvements_filtreDeviseSeuleOuTypeSeul() {
        when(transactionRepository.findByUserIdAndCurrency(eq(USER_ID), eq("EUR"), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(transactionRepository.findByUserIdAndType(eq(USER_ID), eq(WalletTransactionType.TOP_UP), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.transactions(USER_ID, "EUR", null, 0, 0);
        service.transactions(USER_ID, null, "TOP_UP", 0, 20);

        verify(transactionRepository).findByUserIdAndCurrency(eq(USER_ID), eq("EUR"), any(Pageable.class));
        verify(transactionRepository).findByUserIdAndType(eq(USER_ID), eq(WalletTransactionType.TOP_UP), any(Pageable.class));
    }

    @Test
    void mouvements_typeInconnu_refuse400() {
        YadonyBusinessException e = business(catchThrowable(() -> service.transactions(USER_ID, null, "BOGUS", 0, 20)));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getErrorCode()).isEqualTo("wallet-transaction-type-invalid");
    }
}
