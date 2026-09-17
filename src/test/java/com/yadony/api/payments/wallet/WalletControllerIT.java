package com.yadony.api.payments.wallet;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WalletService walletService;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired WalletRefundRequestItemRepository walletRefundRequestItemRepository;
    @Autowired WalletRefundRequestRepository walletRefundRequestRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired com.yadony.api.payments.pawapay.PawapayOperationService pawapayOperationService;
    @MockBean UserRepository userRepository;
    // Frais Stripe réels lus via PaymentIntent.retrieve (appel réseau) : neutralisés ici pour
    // ne jamais dépendre de Stripe en IT (cf. tâche 3, lot 2 « recharge wallet mobile money »).
    @MockBean com.yadony.api.payments.wallet.fees.StripeFeeSource stripeFeeSource;

    private static final UUID USER_UUID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-test-wallet";

    @BeforeEach
    void setUp() {
        walletRefundRequestItemRepository.deleteAll();
        walletRefundRequestRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletAccountRepository.deleteAll();

        UserEntity testUser = new UserEntity();
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, USER_UUID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(userRepository.findByFirebaseUid(anyString())).thenReturn(Optional.of(testUser));
        // UserBusinessPrefsService.getPrefs charge désormais l'utilisateur par id
        // (pour lire son pays et calculer le verrou) — WalletController.getBalance
        // en dépend indirectement, donc findById doit être doublé lui aussi.
        when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(testUser));
        when(stripeFeeSource.fee(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /**
     * {@code WalletService.debit} verrouille via {@code findByUserIdAndCurrencyForUpdate}
     * ({@code FOR NO KEY UPDATE}), une clause Postgres que H2 (profil "test", MODE=PostgreSQL)
     * ne supporte pas. On reproduit ici les deux écritures (solde + transaction) sans le
     * verrou pessimiste, sans risque dans un test mono-thread.
     */
    private void debitDirect(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId) {
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrency(userId, currency).orElseThrow();
        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(currency);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setBidId(bidId);
        walletTransactionRepository.save(tx);
    }

    @Test
    void getBalance_returnsZeroForNewUser() throws Exception {
        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(0))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.transactions").isArray());
    }

    @Test
    void getBalance_withPageParam_returnsOk() throws Exception {
        mockMvc.perform(get("/wallet/balance")
                .param("page", "0")
                .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").exists())
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void topup_invalidAmount_returns422() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 0.5, "paymentMethod", "STRIPE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void topup_nullAmount_returns422() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("paymentMethod", "STRIPE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void topupCheckoutSession_invalidAmount_returns422() throws Exception {
        mockMvc.perform(post("/wallet/topup/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 0.5)))
                .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void topupCheckoutSession_withoutAuthentication_isRejected() throws Exception {
        mockMvc.perform(post("/wallet/topup/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 25))))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void topup_wave_returns422MobileMoneyTopupRetired() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 10.00, "paymentMethod", "WAVE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("mobile-money-topup-retired"));
    }

    @Test
    void topup_orangeMoney_returns422MobileMoneyTopupRetired() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 20.00, "paymentMethod", "ORANGE_MONEY")))
                .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("mobile-money-topup-retired"));
    }

    @Test
    void getBalance_returnsBalancesListWithActiveCurrencyFlagged() throws Exception {
        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances").isArray())
            .andExpect(jsonPath("$.balances[?(@.currency == 'EUR')].active").value(true));
    }

    @Test
    void getBalance_exposesRefundEligibleTrueWhenPureTopUp() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("40.00"),
            WalletTransactionType.TOP_UP, "pi_refund_eligible", "idem-refund-eligible");

        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refundEligible").value(true));
    }

    @Test
    void requestRefund_notEligible_returns422() throws Exception {
        mockMvc.perform(post("/wallet/USD/refund-request")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("wallet-not-refund-eligible"));
    }

    @Test
    void listRefundRequests_emptyWhenNone() throws Exception {
        mockMvc.perform(get("/wallet/refund-requests")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getBalance_includesLockedNonActiveCurrencyBalances() throws Exception {
        walletService.credit(USER_UUID, "CAD", new BigDecimal("15.00"),
            WalletTransactionType.TOP_UP, "ref-cad", null);

        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances[?(@.currency == 'CAD')].balance").value(15.0))
            .andExpect(jsonPath("$.balances[?(@.currency == 'CAD')].active").value(false));
    }

    @Test
    void getBalance_marksTopupAsRefundProcessingWhenItemInProgress() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("10.00"),
            WalletTransactionType.TOP_UP, "pi_refund_processing", "idem-refund-processing");
        WalletTransactionEntity topup = walletTransactionRepository
            .findByIdempotencyKey("idem-refund-processing").orElseThrow();

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_UUID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        request.setAmount(new BigDecimal("10.00"));
        request.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        request.setRequestedAt(LocalDateTime.now());
        WalletRefundRequestEntity savedRequest = walletRefundRequestRepository.save(request);

        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(savedRequest.getId());
        item.setWalletTransactionId(topup.getId());
        item.setPaymentIntentId("pi_refund_processing");
        item.setAmount(new BigDecimal("10.00"));
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        walletRefundRequestItemRepository.save(item);

        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactions[?(@.paymentRef == 'pi_refund_processing')].refundStatus")
                .value("PROCESSING"))
            // La liste mêle les portefeuilles d'un même utilisateur : chaque ligne porte sa devise.
            .andExpect(jsonPath("$.transactions[?(@.paymentRef == 'pi_refund_processing')].currency")
                .value("EUR"));
    }

    @Test
    void balance_exposeRemboursableEtNonRemboursableParDevise() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("40.00"),
            WalletTransactionType.TOP_UP, "pi_it_1", "k-it-1");
        walletService.credit(USER_UUID, "EUR", new BigDecimal("5.00"),
            WalletTransactionType.REFERRAL_REWARD, null, "k-it-2");
        debitDirect(USER_UUID, "EUR", new BigDecimal("10.00"),
            WalletTransactionType.BID_PAYMENT, UUID.randomUUID());

        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refundEligible").value(true))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundableAmount").value(35.00))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].nonRefundableAmount").value(0.0))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundEligible").value(true))
            // Contrat additif : frais (nuls ici, Stripe neutralisé) et net exposés par devise.
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundFeeAmount").value(0))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundNetAmount").value(35.00));
    }

    @Test
    void balance_netNulApresFrais_nonEligible() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("0.50"),
            WalletTransactionType.TOP_UP, "pi_it_fee", "k-it-fee");
        // Frais Stripe au moins égal au montant : rien ne repartirait vers l'utilisateur.
        when(stripeFeeSource.fee(any(), any())).thenReturn(new BigDecimal("0.50"));

        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refundEligible").value(false))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundableAmount").value(0.50))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundNetAmount").value(0.0))
            .andExpect(jsonPath("$.balances[?(@.currency=='EUR')].refundEligible").value(false));
    }

    @Test
    void refundEligibleTopups_renvoieLeRestantEtLeMontantDOrigine() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("40.00"),
            WalletTransactionType.TOP_UP, "pi_it_2", "k-it-3");
        debitDirect(USER_UUID, "EUR", new BigDecimal("5.00"),
            WalletTransactionType.BID_PAYMENT, UUID.randomUUID());

        mockMvc.perform(get("/wallet/EUR/refund-eligible-topups")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].amount").value(35.00))
            .andExpect(jsonPath("$[0].originalAmount").value(40.00))
            .andExpect(jsonPath("$[0].paymentRef").value("pi_it_2"))
            .andExpect(jsonPath("$[0].feeAmount").value(0));
    }

    @Test
    void listRefundRequests_exposeFraisNetRailEtDestinationMasquee() throws Exception {
        com.yadony.api.payments.pawapay.PawapayOperationEntity deposit = pawapayOperationService.create(
            com.yadony.api.payments.pawapay.PawapayOperationKind.DEPOSIT,
            com.yadony.api.payments.pawapay.PawapayOperationPurpose.WALLET_TOPUP, USER_UUID, null, null,
            new BigDecimal("10000"), "XOF", "ORANGE_CIV", "CI", "2250734567890");

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_UUID);
        request.setCurrency("XOF");
        request.setStatus(WalletRefundRequestStatus.REFUNDED);
        request.setAmount(new BigDecimal("10000.00"));
        request.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        request.setRequestedAt(LocalDateTime.now());
        request.setResolvedAt(LocalDateTime.now());
        WalletRefundRequestEntity saved = walletRefundRequestRepository.save(request);

        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(saved.getId());
        item.setWalletTransactionId(UUID.randomUUID());
        item.setPaymentIntentId("pawapay:" + deposit.getId());
        item.setAmount(new BigDecimal("10000.00"));
        item.setFeeAmount(new BigDecimal("200.00"));
        item.setStatus(WalletRefundItemStatus.REFUNDED);
        walletRefundRequestItemRepository.save(item);

        String body = mockMvc.perform(get("/wallet/refund-requests")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].channel").value("AUTOMATIC_PAWAPAY"))
            .andExpect(jsonPath("$[0].rail").value("PAWAPAY"))
            .andExpect(jsonPath("$[0].amount").value(10000.00))
            .andExpect(jsonPath("$[0].feeAmount").value(200.00))
            .andExpect(jsonPath("$[0].netAmount").value(9800.00))
            .andExpect(jsonPath("$[0].destinationMasked").value(deposit.getMsisdnMasked()))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // Règle 14 : jamais le numéro complet dans la réponse.
        assertThat(body).doesNotContain("0734567890");
    }

    @Test
    void refundRequest_sansCorps_accepte() throws Exception {
        walletService.credit(USER_UUID, "EUR", new BigDecimal("40.00"),
            WalletTransactionType.TOP_UP, "pi_it_3", "k-it-4");

        // Refund.create part au commit de la demande (WalletRefundIssueListener) : mockStatic
        // évite tout appel réseau réel (une clé Stripe factice en test convient tant que
        // STRIPE_SECRET_KEY n'est pas injectée par un workflow de déploiement). L'émission
        // verrouille la demande en FOR NO KEY UPDATE, qu'H2 refuse : l'écouteur journalise
        // l'erreur sans la propager, et l'émission elle-même est couverte sur PostgreSQL
        // réel par WalletRefundIT. Ici, seul le contrat HTTP compte.
        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("resource_missing", "payment_intent", "req_it",
                            "resource_missing", 400, null));

            mockMvc.perform(post("/wallet/EUR/refund-request")
                    .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(jsonPath("$.channel").value("AUTOMATIC_STRIPE"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.rail").value("STRIPE"))
                .andExpect(jsonPath("$.feeAmount").value(0.0))
                .andExpect(jsonPath("$.netAmount").value(40.00))
                // Rail Stripe : aucune destination mobile money. Le champ est absent du corps,
                // pas présent à null : la sérialisation est en NON_NULL (application.yml).
                .andExpect(jsonPath("$.destinationMasked").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "destinationMasked"))));
        }

        assertThat(walletRefundRequestItemRepository.findAll())
            .anyMatch(item -> "pi_it_3".equals(item.getPaymentIntentId()));
    }

    /**
     * Sans numéro payeur, la recharge mobile money est refusée AVANT tout appel réseau à
     * pawaPay : c'est une saisie manquante, pas une panne d'opérateur. Aucun mock pawaPay
     * n'est donc nécessaire ici — s'il en fallait un, c'est que la garde serait tombée trop
     * tard (un dépôt serait parti sans payeur).
     */
    @Test
    void topup_mobileMoneySansNumero_returns422PhoneRequired() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 10000, "paymentMethod", "MOBILE_MONEY")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("topup-phone-required"));
    }

    /**
     * Catalogue demandé avec un numéro vide : 422 avant tout appel réseau. Le test traverse la
     * route, la désérialisation du corps ET la garde du service — sans aucun mock pawaPay,
     * puisque le refus tombe avant le résolveur.
     */
    @Test
    void topupProviders_numeroVide_returns422PhoneRequired() throws Exception {
        mockMvc.perform(post("/wallet/topup/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"\"}")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("topup-phone-required"));
    }

    /**
     * Corps absent : même 422 qu'un numéro vide, jamais un 400. Un corps manquant n'est pas une
     * requête malformée mais un numéro manquant — l'app n'a ainsi qu'un seul message à afficher.
     */
    @Test
    void topupProviders_sansCorps_returns422PhoneRequired() throws Exception {
        mockMvc.perform(post("/wallet/topup/providers")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("topup-phone-required"));
    }

    @Test
    void topupProviders_sansAuthentification_estRefuse() throws Exception {
        mockMvc.perform(post("/wallet/topup/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+225 07 34 56 78 90\"}"))
            .andExpect(status().is4xxClientError());
    }

    /** Recharge inconnue : 404 « introuvable », jamais un 403 qui confirmerait l'identifiant. */
    @Test
    void topupStatus_rechargeInconnue_returns404() throws Exception {
        mockMvc.perform(get("/wallet/topup/" + UUID.randomUUID() + "/status")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("topup-not-found"));
    }

    /** Un identifiant qui n'est pas un UUID est une requête malformée (400), jamais un 500. */
    @Test
    void topupStatus_identifiantNonUuid_returns400() throws Exception {
        mockMvc.perform(get("/wallet/topup/pas-un-uuid/status")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getBalance_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/wallet/balance"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void topup_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 10.00, "paymentMethod", "WAVE"))))
            .andExpect(status().isUnauthorized());
    }
}
