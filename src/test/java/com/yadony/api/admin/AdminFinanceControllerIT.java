package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPaymentEntity;
import com.yadony.api.payments.mobilemoney.MobileMoneyPaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.UUID;

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
@DisplayName("AdminFinanceControllerIT — /admin/wallets, /admin/mobile-money-payments, /admin/cash-commissions")
class AdminFinanceControllerIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean WalletAccountRepository walletRepository;
    @MockitoBean MobileMoneyPaymentRepository mobileMoneyRepository;
    @MockitoBean BidRepository bidRepository;

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

    private static MobileMoneyPaymentEntity mobileMoneyPayment() {
        MobileMoneyPaymentEntity entity = new MobileMoneyPaymentEntity();
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        entity.setBidId(BID_ID);
        entity.setProvider("WAVE");
        entity.setCountryCode("SN");
        entity.setPhoneNumber("221771234567");
        entity.setAmount(new BigDecimal("5000.00"));
        entity.setCurrency("XOF");
        entity.setStatus("COMPLETED");
        ReflectionTestUtils.setField(entity, "createdAt", WHEN);
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

    // ── Permission ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sans PAYMENT_VIEW, les trois routes sont fermees et aucune lecture n'a lieu")
    void withoutPaymentView_allThreeRoutesAreForbidden() throws Exception {
        mockMvc.perform(get("/admin/wallets").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/mobile-money-payments").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/cash-commissions").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());

        verify(walletRepository, never()).findAll(any(Pageable.class));
        verify(mobileMoneyRepository, never()).findAll(any(Pageable.class));
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

    // ── Mobile Money ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/mobile-money-payments — montant en centimes et numero masque des le serveur")
    void mobileMoney_masksPhoneNumberServerSide() throws Exception {
        when(mobileMoneyRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mobileMoneyPayment()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/mobile-money-payments").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.content[0].provider").value("WAVE"))
                .andExpect(jsonPath("$.content[0].countryCode").value("SN"))
                // Le numero ne doit JAMAIS quitter le serveur en clair : l'ecran ne l'affiche
                // pas, rien ne justifie de l'envoyer au navigateur.
                .andExpect(jsonPath("$.content[0].phoneNumber").value("••••••••4567"))
                .andExpect(jsonPath("$.content[0].amountCents").value(500000))
                .andExpect(jsonPath("$.content[0].currency").value("XOF"))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /admin/mobile-money-payments — le numero en clair n'apparait nulle part dans la reponse")
    void mobileMoney_clearNumberIsAbsentFromTheWholeBody() throws Exception {
        when(mobileMoneyRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mobileMoneyPayment()), PageRequest.of(0, 20), 1));

        String body = mockMvc.perform(get("/admin/mobile-money-payments")
                        .with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("221771234567");
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
    @DisplayName("GET /admin/cash-commissions — un bid sans montant negocie ne fait pas tomber la page")
    void cashCommissions_toleratesMissingAmounts() throws Exception {
        BidEntity bare = new BidEntity();
        ReflectionTestUtils.setField(bare, "id", BID_ID);
        bare.setCommissionStatus(CommissionStatus.PENDING);
        ReflectionTestUtils.setField(bare, "currency", "EUR");
        ReflectionTestUtils.setField(bare, "createdAt", WHEN);
        when(bidRepository.findCashCommissions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bare), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/cash-commissions").with(authentication(supportAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amountCents").value(0))
                .andExpect(jsonPath("$.content[0].commissionCents").value(0))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].chargedVia").doesNotExist());
    }

    // ── Pagination ───────────────────────────────────────────────────────────

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
