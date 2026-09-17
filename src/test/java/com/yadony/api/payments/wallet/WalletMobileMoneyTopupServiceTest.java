package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupStatusResponse;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalletMobileMoneyTopupServiceTest {

    @Mock PawapayProviderResolver resolver;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayOperationRepository operations;
    @Mock WalletService walletService;
    @Mock AuditService auditService;

    private final UUID userId = UUID.randomUUID();
    private WalletMobileMoneyTopupService service;

    private static final String PHONE = "+2250734567890";
    /** Espace insécable : c'est le séparateur de milliers des montants affichés. */
    private static final String NBSP = " ";

    @BeforeEach
    void setUp() {
        service = service(true);
    }

    private WalletMobileMoneyTopupService service(boolean railEnabled) {
        return new WalletMobileMoneyTopupService(resolver, submission, operations, walletService, auditService,
                props(railEnabled));
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://api.test",
                "yadony://bids/%s/mobile-money/awaiting", "yadony://negotiations/%s/mobile-money/awaiting",
                "yadony://payments/wallet", new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static PawapayProviderResolver.Resolved orangeCi(BigDecimal min, BigDecimal max) {
        return resolved("ORANGE_CIV", "PIN", min, max);
    }

    private static PawapayProviderResolver.Resolved resolved(String provider, String authType,
                                                             BigDecimal min, BigDecimal max) {
        PawapayProviderConfig config = new PawapayProviderConfig(provider, "CIV", "XOF",
                new PawapayProviderConfig.Limits(min, max, authType, "OPERATIONAL"), null);
        return new PawapayProviderResolver.Resolved(provider, "CI", PHONE, config);
    }

    private static WalletTopupRequest request(String amount, String phone, String provider) {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setPaymentMethod("MOBILE_MONEY");
        r.setPhoneNumber(phone);
        r.setProvider(provider);
        return r;
    }

    private static PawapayOperationEntity deposit(UUID userId, String amount) {
        return new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, userId, null, null, new BigDecimal(amount), "XOF",
                "ORANGE_CIV", "CI", PHONE);
    }

    private void resolves(PawapayProviderResolver.Resolved value) {
        when(resolver.resolve(eq(PHONE), eq(PawapayOperationKind.DEPOSIT), isNull(), any(), anyString()))
                .thenReturn(value);
    }

    // ── initiate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dépôt soumis dans la devise de l'opérateur, pas dans celle du portefeuille")
    void initiate_submitsDepositInOperatorCurrency() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        when(operations.existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(eq(userId),
                eq(PawapayOperationKind.DEPOSIT), eq("XOF"), eq(PawapayOperationPurpose.WALLET_TOPUP),
                eq(PawapayOperationStatus.OPEN))).thenReturn(false);
        PawapayOperationEntity op = deposit(userId, "10000");
        when(submission.submitWalletDeposit(eq(userId), eq(PHONE), eq("ORANGE_CIV"), eq("CI"),
                eq(new BigDecimal("10000")), eq("XOF"), eq("wallet-topup-" + userId), isNull(), isNull()))
                .thenReturn(op);

        WalletTopupResponse response = service.initiate(userId, request("10000", PHONE, null));

        assertThat(response.getTopupId()).isEqualTo(op.getId());
        assertThat(response.getCurrency()).isEqualTo("XOF");
        assertThat(response.getProvider()).isEqualTo("ORANGE_CIV");
        assertThat(response.getProviderLabel()).isEqualTo("Orange Money");
        assertThat(response.getMsisdnMasked()).isEqualTo(op.getMsisdnMasked());
        assertThat(response.getClientSecret()).isNull();
        verify(auditService).log(eq("wallet_topup"), eq(op.getId()), eq("MOBILE_MONEY_INITIATED"), eq(userId), any());
    }

    /**
     * L'opérateur choisi par l'utilisateur est passé tel quel au résolveur (surcharge à cinq
     * arguments) : c'est lui, pas le prédit, qui doit recevoir la demande de code.
     */
    @Test
    void initiate_withChosenProvider_passesItToTheResolver() {
        when(resolver.resolve(eq(PHONE), eq(PawapayOperationKind.DEPOSIT), isNull(), eq("WAVE_CIV"), anyString()))
                .thenReturn(resolved("WAVE_CIV", PawapayProviders.REDIRECT_AUTH,
                        new BigDecimal("500"), new BigDecimal("1000000")));
        when(submission.submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(deposit(userId, "10000"));

        service.initiate(userId, request("10000", PHONE, "WAVE_CIV"));

        verify(submission).submitWalletDeposit(eq(userId), eq(PHONE), eq("WAVE_CIV"), eq("CI"),
                any(), eq("XOF"), anyString(), anyString(), anyString());
    }

    /**
     * Wave autorise par redirection : pawaPay a besoin des deux URLs de retour. Elles pointent
     * sur la page de rebond du portefeuille, keyée sur l'utilisateur (l'id de l'opération
     * n'existe pas encore au moment de la soumission).
     */
    @Test
    void initiate_redirectProvider_buildsWalletReturnUrls() {
        when(resolver.resolve(eq(PHONE), eq(PawapayOperationKind.DEPOSIT), isNull(), any(), anyString()))
                .thenReturn(resolved("WAVE_CIV", PawapayProviders.REDIRECT_AUTH,
                        new BigDecimal("500"), new BigDecimal("1000000")));
        when(submission.submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(deposit(userId, "10000"));
        ArgumentCaptor<String> successful = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> failed = ArgumentCaptor.forClass(String.class);

        service.initiate(userId, request("10000", PHONE, null));

        verify(submission).submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(),
                successful.capture(), failed.capture());
        assertThat(successful.getValue())
                .isEqualTo("https://api.test/api/v1/pawapay/return/wallet-topup?userId=" + userId + "&outcome=success");
        assertThat(failed.getValue())
                .isEqualTo("https://api.test/api/v1/pawapay/return/wallet-topup?userId=" + userId + "&outcome=failed");
    }

    @Test
    void initiate_withoutPhone_422() {
        Throwable t = catchThrowable(() -> service.initiate(userId, request("10000", null, null)));
        assertThat(t).isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "topup-phone-required")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(resolver, submission, operations);
    }

    @Test
    @DisplayName("rail coupé : aucune nouvelle demande de débit, même avec un numéro valide")
    void initiate_whenRailDisabled_422() {
        Throwable t = catchThrowable(() -> service(false).initiate(userId, request("10000", PHONE, null)));
        assertThat(t).hasFieldOrPropertyWithValue("errorCode", "mobile-money-disabled");
        verifyNoInteractions(resolver, submission);
    }

    @Test
    void initiate_amountBelowMin_422WithBounds() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));

        Throwable t = catchThrowable(() -> service.initiate(userId, request("100", PHONE, null)));

        assertThat(t).isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "topup-amount-out-of-range");
        assertThat(t.getMessage()).contains("500").contains("1" + NBSP + "000" + NBSP + "000")
                .contains("F CFA").contains("sans centimes");
        verifyNoInteractions(submission);
    }

    @Test
    void initiate_amountAboveMax_422() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        Throwable t = catchThrowable(() -> service.initiate(userId, request("1000001", PHONE, null)));
        assertThat(t).hasFieldOrPropertyWithValue("errorCode", "topup-amount-out-of-range");
        verifyNoInteractions(submission);
    }

    @Test
    void initiate_amountWithDecimalsOnXof_422() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        Throwable t = catchThrowable(() -> service.initiate(userId, request("1000.50", PHONE, null)));
        assertThat(t).hasFieldOrPropertyWithValue("errorCode", "topup-amount-out-of-range");
        verifyNoInteractions(submission);
    }

    /**
     * Un client JSON sérialise volontiers « 10000.00 » : le montant est entier, seule la
     * représentation porte des zéros. Le refuser rendrait la recharge impossible depuis ces
     * clients — d'où {@code stripTrailingZeros} plutôt que {@code scale()} nu.
     */
    @Test
    void initiate_amountWithTrailingZerosOnXof_isAccepted() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        when(submission.submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(deposit(userId, "10000"));

        service.initiate(userId, request("10000.00", PHONE, null));

        verify(submission).submitWalletDeposit(any(), any(), any(), any(), eq(new BigDecimal("10000.00")), any(),
                any(), any(), any());
    }

    @Test
    void initiate_whenAnotherTopupPending_422() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        when(operations.existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(any(), any(), any(), any(), any()))
                .thenReturn(true);

        Throwable t = catchThrowable(() -> service.initiate(userId, request("10000", PHONE, null)));

        assertThat(t).hasFieldOrPropertyWithValue("errorCode", "topup-already-pending");
        verifyNoInteractions(submission);
    }

    /**
     * {@code OPEN} (non terminaux) et non {@code LIVE_OR_DONE} : une recharge déjà COMPLETED
     * ne doit jamais bloquer la suivante, sinon un utilisateur ne recharge qu'une fois.
     */
    @Test
    void initiate_duplicateGuard_looksAtNonTerminalStatusesOnly() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        when(submission.submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(deposit(userId, "10000"));

        service.initiate(userId, request("10000", PHONE, null));

        verify(operations).existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(userId,
                PawapayOperationKind.DEPOSIT, "XOF", PawapayOperationPurpose.WALLET_TOPUP,
                PawapayOperationStatus.OPEN);
        assertThat(PawapayOperationStatus.OPEN).doesNotContain(PawapayOperationStatus.COMPLETED);
    }

    @Test
    void initiate_unsupportedNumber_422WithBusinessReason() {
        when(resolver.resolve(eq(PHONE), any(), isNull(), any(), anyString()))
                .thenThrow(unsupportedNumber(PawapayProviderResolver.Reason.NO_PROVIDER));

        Throwable t = catchThrowable(() -> service.initiate(userId, request("10000", PHONE, null)));

        assertThat(t).isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "topup-phone-unsupported");
        assertThat(t.getMessage()).isEqualTo("Aucun opérateur mobile money reconnu pour ce numéro.");
        verifyNoInteractions(submission);
    }

    // ── status ──────────────────────────────────────────────────────────────

    @Test
    void status_unknownOrForeignOperation_404() {
        UUID id = UUID.randomUUID();
        when(operations.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        Throwable t = catchThrowable(() -> service.status(userId, id));

        assertThat(t).isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "topup-not-found")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    /** Une opération de l'utilisateur mais d'un autre usage (paiement de colis) reste hors sujet. */
    @Test
    void status_operationOfAnotherPurpose_404() {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_REFUND, userId, null, null, new BigDecimal("10000"), "XOF",
                "ORANGE_CIV", "CI", PHONE);
        when(operations.findByIdAndUserId(op.getId(), userId)).thenReturn(Optional.of(op));

        assertThat(catchThrowable(() -> service.status(userId, op.getId())))
                .hasFieldOrPropertyWithValue("errorCode", "topup-not-found");
    }

    @Test
    void status_completed_returnsConfirmedAndBalance() {
        PawapayOperationEntity op = deposit(userId, "10000");
        op.setStatus(PawapayOperationStatus.COMPLETED);
        when(operations.findByIdAndUserId(op.getId(), userId)).thenReturn(Optional.of(op));
        when(walletService.getBalance(userId, "XOF")).thenReturn(new BigDecimal("10000"));

        WalletTopupStatusResponse s = service.status(userId, op.getId());

        assertThat(s.status()).isEqualTo("CONFIRMED");
        assertThat(s.walletBalance()).isEqualByComparingTo("10000");
        assertThat(s.amount()).isEqualByComparingTo("10000");
        assertThat(s.currency()).isEqualTo("XOF");
        assertThat(s.providerLabel()).isEqualTo("Orange Money");
        assertThat(s.failureReason()).isNull();
    }

    @Test
    void status_pending_hasNoBalance() {
        PawapayOperationEntity op = deposit(userId, "10000");
        op.setStatus(PawapayOperationStatus.PROCESSING);
        when(operations.findByIdAndUserId(op.getId(), userId)).thenReturn(Optional.of(op));

        WalletTopupStatusResponse s = service.status(userId, op.getId());

        assertThat(s.status()).isEqualTo("PENDING");
        assertThat(s.walletBalance()).isNull();
        verify(walletService, never()).getBalance(any(), any());
    }

    @Test
    void status_failed_reportsTheClampedReason_andNoBalance() {
        PawapayOperationEntity op = deposit(userId, "10000");
        op.setStatus(PawapayOperationStatus.FAILED);
        op.setFailureMessage("x".repeat(200));
        op.setFailureCode("PAYER_LIMIT_REACHED");
        ReflectionTestUtils.setField(op, "authorizationUrl", "https://wave.test/pay");
        when(operations.findByIdAndUserId(op.getId(), userId)).thenReturn(Optional.of(op));

        WalletTopupStatusResponse s = service.status(userId, op.getId());

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.failureReason()).hasSize(64);
        assertThat(s.authorizationUrl()).isEqualTo("https://wave.test/pay");
        assertThat(s.walletBalance()).isNull();
    }

    @Test
    void status_failedWithoutMessage_fallsBackToTheCode() {
        PawapayOperationEntity op = deposit(userId, "10000");
        op.setStatus(PawapayOperationStatus.SUBMIT_REJECTED);
        op.setFailureCode("REJECTED");
        when(operations.findByIdAndUserId(op.getId(), userId)).thenReturn(Optional.of(op));

        WalletTopupStatusResponse s = service.status(userId, op.getId());

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.failureReason()).isEqualTo("REJECTED");
    }

    // ── providers ───────────────────────────────────────────────────────────

    @Test
    void providers_mapsTheDepositCatalogueOfTheNumber() {
        PawapayProviderConfig orange = new PawapayProviderConfig("ORANGE_CIV", "CIV", "XOF",
                new PawapayProviderConfig.Limits(new BigDecimal("500"), new BigDecimal("1000000"), "PIN", "OPERATIONAL"), null);
        PawapayProviderConfig wave = new PawapayProviderConfig("WAVE_CIV", "CIV", "XOF",
                new PawapayProviderConfig.Limits(new BigDecimal("500"), new BigDecimal("1000000"),
                        PawapayProviders.REDIRECT_AUTH, "OPERATIONAL"), null);
        when(resolver.catalogue(eq(PHONE), eq(PawapayOperationKind.DEPOSIT), isNull(), anyString()))
                .thenReturn(new PawapayProviderResolver.Catalogue("CI", "XOF", PHONE, "ORANGE_CIV",
                        List.of(orange, wave)));

        MobileMoneyProvidersResponse response = service.providers(PHONE);

        assertThat(response.country()).isEqualTo("CI");
        assertThat(response.currency()).isEqualTo("XOF");
        assertThat(response.detected()).isEqualTo("ORANGE_CIV");
        assertThat(response.msisdnMasked()).doesNotContain("734567");
        assertThat(response.providers()).extracting(MobileMoneyProvidersResponse.ProviderOption::code)
                .containsExactly("ORANGE_CIV", "WAVE_CIV");
        assertThat(response.providers()).extracting(MobileMoneyProvidersResponse.ProviderOption::label)
                .containsExactly("Orange Money", "Wave");
        assertThat(response.providers().get(0).detected()).isTrue();
        assertThat(response.providers().get(1).detected()).isFalse();
    }

    @Test
    void providers_withoutPhone_422() {
        assertThat(catchThrowable(() -> service.providers(" ")))
                .hasFieldOrPropertyWithValue("errorCode", "topup-phone-required");
        verifyNoInteractions(resolver);
    }

    @Test
    void providers_whenRailDisabled_422() {
        assertThat(catchThrowable(() -> service(false).providers(PHONE)))
                .hasFieldOrPropertyWithValue("errorCode", "mobile-money-disabled");
        verifyNoInteractions(resolver);
    }

    @Test
    void providers_unsupportedNumber_422() {
        when(resolver.catalogue(eq(PHONE), any(), isNull(), anyString()))
                .thenThrow(unsupportedNumber(PawapayProviderResolver.Reason.OPERATION_CLOSED));

        Throwable t = catchThrowable(() -> service.providers(PHONE));

        assertThat(t).hasFieldOrPropertyWithValue("errorCode", "topup-phone-unsupported");
        assertThat(t.getMessage()).isEqualTo("Mobile money ne permet pas le paiement pour le moment.");
    }

    /**
     * Le constructeur de {@code UnsupportedNumberException} est package-private côté pawaPay
     * (seul le résolveur la lève) : ce test la fabrique par réflexion plutôt que d'ouvrir sa
     * visibilité pour la commodité d'un test.
     */
    private static PawapayProviderResolver.UnsupportedNumberException unsupportedNumber(
            PawapayProviderResolver.Reason reason) {
        try {
            Constructor<PawapayProviderResolver.UnsupportedNumberException> constructor =
                    PawapayProviderResolver.UnsupportedNumberException.class.getDeclaredConstructor(
                            PawapayProviderResolver.Reason.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(reason, null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void auditPayload_carriesTheOperationAndNeverTheRawNumber() {
        resolves(orangeCi(new BigDecimal("500"), new BigDecimal("1000000")));
        PawapayOperationEntity op = deposit(userId, "10000");
        when(submission.submitWalletDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);

        service.initiate(userId, request("10000", PHONE, null));

        verify(auditService).log(eq("wallet_topup"), eq(op.getId()), eq("MOBILE_MONEY_INITIATED"), eq(userId),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("currency", "XOF").containsEntry("amount", "10000")
                .containsEntry("provider", "ORANGE_CIV").containsEntry("status", "CREATED");
        assertThat(payload.getValue().values()).doesNotContain(PHONE);
    }
}
