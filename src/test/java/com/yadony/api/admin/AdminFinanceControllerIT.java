package com.yadony.api.admin;

import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.MobileMoneyCommissionMonthRow;
import com.yadony.api.payments.dto.MobileMoneyCommissionRow;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot D — les trois vues financieres etendues, en LECTURE SEULE.
 *
 * <p>Elles sont gardees par {@code PAYMENT_VIEW}, que SUPPORT porte deja : ces onglets vivent
 * dans la meme page Transactions, elle-meme ouverte a SUPPORT. Le test de refus vise donc un
 * compte a qui la permission a ete retiree par override, seul cas ou elle manque reellement.
 *
 * <p>Deux conversions sont verrouillees ici parce qu'elles sont invisibles a la lecture du
 * code appelant : les montants sont stockes en UNITES ({@code BigDecimal} scale 2) et exposes
 * en CENTIMES, et le numero de telephone Mobile Money est masque cote serveur.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminFinanceControllerIT — /admin/wallets, /admin/cash-commissions")
class AdminFinanceControllerIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean WalletAccountRepository walletRepository;
    @MockitoBean BidRepository bidRepository;
    @MockitoBean PawapayOperationRepository pawapayOperationRepository;
    @MockitoBean PaymentRepository paymentRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 8, 19, 10, 0);

    /** SUPPORT porte PAYMENT_VIEW dans AdminRole — c'est le cas nominal, pas une faveur. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-finance");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    /** Compte admin dont PAYMENT_VIEW a ete retiree par override. */
    private static UsernamePasswordAuthenticationToken withoutPaymentView() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "bride@yadony.test", AdminRole.ADMIN, false, "uid-bride-finance");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static WalletAccountEntity wallet() {
        WalletAccountEntity entity = new WalletAccountEntity();
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entity, "userId", USER_ID);
        ReflectionTestUtils.setField(entity, "balance", new BigDecimal("12.34"));
        ReflectionTestUtils.setField(entity, "currency", "EUR");
        ReflectionTestUtils.setField(entity, "updatedAt", WHEN);
        return entity;
    }

    private static BidEntity cashBid() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setNegotiatedGrossEur(new BigDecimal("100.00"));
        bid.setNegotiatedNetEur(new BigDecimal("88.00"));
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.WALLET);
        bid.setCommissionRetryCount(2);
        ReflectionTestUtils.setField(bid, "currency", "EUR");
        ReflectionTestUtils.setField(bid, "createdAt", WHEN);
        return bid;
    }

    /** Tâche 18 — une opération pawaPay COMPLETED, telle que rendue par {@code AdminMobileMoneyResponse}. */
    private static PawapayOperationEntity operation() {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, UUID.randomUUID(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        op.setStatus(PawapayOperationStatus.COMPLETED);
        return op;
    }

    // ── Permission ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sans PAYMENT_VIEW, les deux routes sont fermees et aucune lecture n'a lieu")
    void withoutPaymentView_bothRoutesAreForbidden() throws Exception {
        mockMvc.perform(get("/admin/wallets").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/cash-commissions").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());

        verify(walletRepository, never()).findAll(any(Pageable.class));
        verify(bidRepository, never()).findCashCommissions(any(Pageable.class));
    }

    // ── Wallets ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/wallets — le solde passe des unites aux centimes")
    void wallets_convertUnitsToCents() throws Exception {
        when(walletRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wallet()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/wallets").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(USER_ID.toString()))
                // 12.34 EUR en base -> 1234 centimes sur le fil, jamais 12.34.
                .andExpect(jsonPath("$.content[0].balanceCents").value(1234))
                .andExpect(jsonPath("$.content[0].currency").value("EUR"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── Commissions cash ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/cash-commissions — la commission est deduite de brut - net")
    void cashCommissions_deriveCommissionFromGrossMinusNet() throws Exception {
        when(bidRepository.findCashCommissions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cashBid()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/cash-commissions").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.content[0].amountCents").value(10000))
                // Aucune colonne ne porte le montant de commission : il se deduit de
                // l'invariant net + commission = brut, garanti au centime par la negociation.
                .andExpect(jsonPath("$.content[0].commissionCents").value(1200))
                .andExpect(jsonPath("$.content[0].status").value("CHARGED"))
                .andExpect(jsonPath("$.content[0].chargedVia").value("WALLET"))
                .andExpect(jsonPath("$.content[0].retryCount").value(2));
    }

    @Test
    @DisplayName("GET /admin/cash-commissions — un bid hors negociation n'invente pas un montant de zero")
    void cashCommissions_reportsUnknownAmountsAsAbsent() throws Exception {
        // Cas MAJORITAIRE, pas un cas limite : une demande cash ordinaire calcule sa
        // commission depuis kgNet + gridNet sans jamais renseigner brut ni net. Exposer 0 se
        // lirait « aucune commission prelevee » — faux : elle l'a bien ete, c'est son montant
        // que cette vue ne sait pas reconstituer. Absent est la seule reponse honnete.
        BidEntity bare = new BidEntity();
        ReflectionTestUtils.setField(bare, "id", BID_ID);
        bare.setCommissionStatus(CommissionStatus.PENDING);
        ReflectionTestUtils.setField(bare, "currency", "EUR");
        ReflectionTestUtils.setField(bare, "createdAt", WHEN);
        when(bidRepository.findCashCommissions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bare), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/cash-commissions").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amountCents").doesNotExist())
                .andExpect(jsonPath("$.content[0].commissionCents").doesNotExist())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].chargedVia").doesNotExist());
    }

    // ── Mobile money (tâche 18) ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/mobile-money-payments — operations pawaPay, montant en centimes, numero masque")
    void mobileMoney_listsOperations_masked() throws Exception {
        when(pawapayOperationRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(operation()), PageRequest.of(0, 20), 1));

        String body = mockMvc.perform(get("/admin/mobile-money-payments").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[0].provider").value("ORANGE_SEN"))
                .andExpect(jsonPath("$.content[0].countryCode").value("SN"))
                .andExpect(jsonPath("$.content[0].phoneNumber").value("+221 •••• 67"))
                .andExpect(jsonPath("$.content[0].amountCents").value(1680000))
                .andExpect(jsonPath("$.content[0].currency").value("XOF"))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("221771234567");
    }

    @Test
    @DisplayName("GET /admin/mobile-money-payments — sans PAYMENT_VIEW → 403")
    void mobileMoney_requiresPaymentView() throws Exception {
        mockMvc.perform(get("/admin/mobile-money-payments").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
        verify(pawapayOperationRepository, never()).findAllByOrderByCreatedAtDesc(any(Pageable.class));
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    // ── Commissions mobile money ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/mobile-money-commissions — replie les statuts en un total par devise")
    void mobileMoneyCommissions_foldsStatusesPerCurrency() throws Exception {
        when(paymentRepository.sumMobileMoneyCommissionsByCurrencyAndStatus(any(), any())).thenReturn(List.of(
                new MobileMoneyCommissionRow("XOF", PaymentStatus.RELEASED, 2,
                        new BigDecimal("19800.00"), new BigDecimal("1800.00")),
                new MobileMoneyCommissionRow("XOF", PaymentStatus.ESCROW, 1,
                        new BigDecimal("9900.00"), new BigDecimal("900.00")),
                new MobileMoneyCommissionRow("XAF", PaymentStatus.RELEASED, 1,
                        new BigDecimal("5000.00"), new BigDecimal("500.00"))));
        when(paymentRepository.sumMobileMoneyCommissionsByMonth(any(), any())).thenReturn(List.of(
                new MobileMoneyCommissionMonthRow(2026, 9, "XOF", 2,
                        new BigDecimal("19800.00"), new BigDecimal("1800.00"))));

        mockMvc.perform(get("/admin/mobile-money-commissions").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                // Une entrée par devise, jamais un total toutes devises confondues.
                .andExpect(jsonPath("$.byCurrency.length()").value(2))
                .andExpect(jsonPath("$.byCurrency[0].currency").value("XOF"))
                .andExpect(jsonPath("$.byCurrency[0].earnedCount").value(2))
                // Centièmes de l'unité principale : 1 800 XOF -> 180000, jamais l'unité mineure.
                .andExpect(jsonPath("$.byCurrency[0].earnedCommissionCents").value(180000))
                .andExpect(jsonPath("$.byCurrency[0].earnedGrossCents").value(1980000))
                // Net versé au voyageur = brut - commission.
                .andExpect(jsonPath("$.byCurrency[0].earnedNetCents").value(1800000))
                .andExpect(jsonPath("$.byCurrency[0].escrowedCommissionCents").value(90000))
                .andExpect(jsonPath("$.byCurrency[0].refundedCount").value(0))
                .andExpect(jsonPath("$.byCurrency[1].currency").value("XAF"))
                .andExpect(jsonPath("$.byCurrency[1].earnedCommissionCents").value(50000))
                .andExpect(jsonPath("$.monthly[0].month").value("2026-09"))
                .andExpect(jsonPath("$.monthly[0].netCents").value(1800000));
    }

    @Test
    @DisplayName("GET /admin/mobile-money-commissions — période par défaut : les 12 derniers mois")
    void mobileMoneyCommissions_defaultsToLastTwelveMonths() throws Exception {
        when(paymentRepository.sumMobileMoneyCommissionsByCurrencyAndStatus(any(), any())).thenReturn(List.of());
        when(paymentRepository.sumMobileMoneyCommissionsByMonth(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/mobile-money-commissions").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byCurrency.length()").value(0));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(paymentRepository).sumMobileMoneyCommissionsByCurrencyAndStatus(from.capture(), to.capture());
        assertThat(from.getValue()).isCloseTo(to.getValue().minusMonths(12), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("GET /admin/mobile-money-commissions — les bornes passées sont transmises telles quelles")
    void mobileMoneyCommissions_forwardsExplicitRange() throws Exception {
        when(paymentRepository.sumMobileMoneyCommissionsByCurrencyAndStatus(any(), any())).thenReturn(List.of());
        when(paymentRepository.sumMobileMoneyCommissionsByMonth(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/mobile-money-commissions")
                        .param("from", "2026-08-01T00:00:00")
                        .param("to", "2026-08-31T23:59:59")
                        .with(authentication(supportAuth())))
                .andExpect(status().isOk());

        verify(paymentRepository).sumMobileMoneyCommissionsByCurrencyAndStatus(
                LocalDateTime.of(2026, 8, 1, 0, 0, 0), LocalDateTime.of(2026, 8, 31, 23, 59, 59));
    }

    @Test
    @DisplayName("GET /admin/mobile-money-commissions — sans PAYMENT_VIEW → 403, sans aucune lecture")
    void mobileMoneyCommissions_requiresPaymentView() throws Exception {
        mockMvc.perform(get("/admin/mobile-money-commissions").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());

        verify(paymentRepository, never()).sumMobileMoneyCommissionsByCurrencyAndStatus(any(), any());
        verify(paymentRepository, never()).sumMobileMoneyCommissionsByMonth(any(), any());
    }

    @Test
    @DisplayName("La pagination demandee est celle transmise au depot")
    void paginationIsForwarded() throws Exception {
        when(walletRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get("/admin/wallets")
                        .param("page", "2").param("size", "5")
                        .with(authentication(supportAuth())))
                .andExpect(status().isOk());

        verify(walletRepository).findAll(PageRequest.of(2, 5));
    }
}
