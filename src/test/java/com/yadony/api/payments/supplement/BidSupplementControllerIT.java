package com.yadony.api.payments.supplement;

import com.yadony.api.payments.pawapay.ValidationHint;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayerProvidersResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.supplement.dto.SupplementOptionsResponse;
import com.yadony.api.payments.supplement.dto.SupplementQuoteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BidSupplementControllerIT {

    @Autowired MockMvc mockMvc;
    @MockBean BidSupplementService service;
    @MockBean BidSupplementPaymentFlow paymentFlow;
    @MockBean BidSupplementMobileMoneyRail mobileMoney;

    private final UUID bidId = UUID.randomUUID();
    private final UUID supplementId = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken as(String uid, String... roles) {
        return new UsernamePasswordAuthenticationToken(uid, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private BidSupplementResponse response(String status, boolean forSender) {
        return new BidSupplementResponse(supplementId, status, "STRIPE", "EUR",
                forSender ? null : new BigDecimal("20.00"), forSender ? null : new BigDecimal("1.10"),
                new BigDecimal("21.10"), "GRID_ITEM", "Téléphone", "Téléphone", "https://signed",
                OffsetDateTime.parse("2026-10-19T12:00:00Z"), null, null, "PROPOSED".equals(status));
    }

    private static final String CREATE_BODY = """
            {"netAmount": 20, "reasonCode": "GRID_ITEM", "reasonText": "Téléphone déclaré au kilo",
             "gridItemLabel": "Téléphone", "photoKey": "tracking/%s/1_supplement.jpg"}
            """;

    @Test
    void create_voyageur_201() throws Exception {
        when(paymentFlow.create(eq(bidId), eq("uid-t"), any(CreateSupplementRequest.class)))
                .thenReturn(response("PROPOSED", false));

        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "SENDER", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(bidId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(supplementId.toString()))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.netAmount").value(20.00))
                .andExpect(jsonPath("$.grossAmount").value(21.10))
                .andExpect(jsonPath("$.blocksHandover").value(true));
    }

    @Test
    void create_sansPhoto_422Validation() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"netAmount\": 20, \"reasonCode\": \"OTHER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void create_erreurMetier_problemJsonAvecCodeEtProprietes() throws Exception {
        when(paymentFlow.create(eq(bidId), eq("uid-t"), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-amount-out-of-range", "Supplement Amount Out Of Range",
                "hors bornes", Map.of("min", new BigDecimal("1.00"), "max", new BigDecimal("300.00"), "currency", "EUR")));

        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(bidId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("supplement-amount-out-of-range"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.max").value(300.00));
    }

    @Test
    void create_sansJeton_401_sansRoleVoyageur_403() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/supplements")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(bidId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(bidId)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void options_et_devis_voyageur() throws Exception {
        when(service.options(bidId, "uid-t")).thenReturn(new SupplementOptionsResponse("EUR", new BigDecimal("1.00"),
                new BigDecimal("300.00"), List.of(new SupplementOptionsResponse.Suggestion("KG", null,
                new BigDecimal("8.00"))), "STRIPE"));
        when(service.quote(bidId, "uid-t", new BigDecimal("20"))).thenReturn(new SupplementQuoteResponse(
                new BigDecimal("20.00"), new BigDecimal("1.10"), new BigDecimal("21.10"), "EUR"));

        mockMvc.perform(get("/bids/" + bidId + "/supplements/options").with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxNet").value(300.00))
                .andExpect(jsonPath("$.suggestions[0].kind").value("KG"))
                .andExpect(jsonPath("$.rail").value("STRIPE"));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/quote").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"netAmount\": 20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionAmount").value(1.10))
                .andExpect(jsonPath("$.grossAmount").value(21.10));
    }

    @Test
    void retrait_voyageur() throws Exception {
        when(service.withdraw(bidId, supplementId, "uid-t")).thenReturn(response("WITHDRAWN", false));

        mockMvc.perform(delete("/bids/" + bidId + "/supplements/" + supplementId)
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.blocksHandover").value(false));
    }

    @Test
    void refus_expediteur_sansNetNiCommission() throws Exception {
        when(service.decline(bidId, supplementId, "uid-s")).thenReturn(response("DECLINED", true));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/decline")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.netAmount").doesNotExist())
                .andExpect(jsonPath("$.commissionAmount").doesNotExist())
                .andExpect(jsonPath("$.grossAmount").value(21.10));
    }

    @Test
    void acceptation_railNonLivre_422() throws Exception {
        when(paymentFlow.accept(bidId, supplementId, "uid-s")).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported", "Supplement Rail Unsupported", "non"));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/accept")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("supplement-rail-unsupported"));
    }

    @Test
    void actionsExpediteur_refuseesAuSeulRoleVoyageur() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/accept")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/decline")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/confirm-payment")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
        verify(service, never()).accept(any(), any(), any());
        verify(paymentFlow, never()).accept(any(), any(), any());
        verify(paymentFlow, never()).confirmPayment(any(), any(), any());
        verify(service, never()).decline(any(), any(), any());
    }

    @Test
    void acceptation_carte_contratDeLaPaymentSheet() throws Exception {
        when(paymentFlow.accept(bidId, supplementId, "uid-s")).thenReturn(new SupplementAcceptResponse(
                response("PAYMENT_PENDING", true), "STRIPE", "pi_1_secret_x", List.of("card", "paypal"),
                new BigDecimal("21.10"), "EUR", "pk_test_1"));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/accept")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rail").value("STRIPE"))
                .andExpect(jsonPath("$.clientSecret").value("pi_1_secret_x"))
                .andExpect(jsonPath("$.publishableKey").value("pk_test_1"))
                .andExpect(jsonPath("$.paymentMethodTypes[1]").value("paypal"))
                .andExpect(jsonPath("$.amount").value(21.10))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.supplement.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.supplement.netAmount").doesNotExist());
    }

    @Test
    void confirmationDuPaiement_expediteur_vueSansNet() throws Exception {
        when(paymentFlow.confirmPayment(bidId, supplementId, "uid-s")).thenReturn(response("PAID", true));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/confirm-payment")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.netAmount").doesNotExist())
                .andExpect(jsonPath("$.grossAmount").value(21.10));
    }

    @Test
    void confirmationDuPaiement_stripeInjoignable_502ProblemJson() throws Exception {
        when(paymentFlow.confirmPayment(bidId, supplementId, "uid-s"))
                .thenThrow(com.yadony.api.common.stripe.StripeUnavailable.exception());

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/confirm-payment")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("stripe-unavailable"));
    }

    // ── Rail mobile money (B3) ───────────────────────────────────────────────

    private MobileMoneyPaymentStatusResponse mmStatus(String paymentStatus) {
        return new MobileMoneyPaymentStatusResponse(bidId, "ACCEPTED", paymentStatus,
                java.time.LocalDateTime.parse("2026-10-19T12:00:00"), new BigDecimal("13700"), "XOF",
                new MobileMoneyPaymentStatusResponse.OperationView(UUID.randomUUID(), "ACCEPTED", "WAVE_SEN", "Wave",
                        "+221 •••• 01", "https://pay.wave.com/x", null, null, null, null, ValidationHint.GENERIC));
    }

    @Test
    void mobileMoneyInitiate_expediteur_201_avecNumeroEtOperateur() throws Exception {
        when(mobileMoney.initiate(bidId, supplementId, "uid-s", "+221770000001", "WAVE_SEN"))
                .thenReturn(mmStatus("PENDING"));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/initiate")
                        .with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"+221770000001\", \"provider\": \"WAVE_SEN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bidId").value(bidId.toString()))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(13700))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.deposit.authorizationUrl").value("https://pay.wave.com/x"));
    }

    @Test
    void mobileMoneyInitiate_sansCorps_201_voyageurSeul_403() throws Exception {
        when(mobileMoney.initiate(bidId, supplementId, "uid-s", null, null)).thenReturn(mmStatus("PENDING"));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/initiate")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/initiate")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void mobileMoneyInitiate_erreurMetier_problemJson() throws Exception {
        when(mobileMoney.initiate(any(), any(), any(), any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled", "Mobile Money Disabled", "coupé"));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/initiate")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("mobile-money-disabled"));
    }

    @Test
    void mobileMoneyStatus_expediteurEtVoyageur() throws Exception {
        when(mobileMoney.status(bidId, supplementId, "uid-t")).thenReturn(mmStatus("ESCROW"));
        mockMvc.perform(get("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/status")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("ESCROW"));
        mockMvc.perform(get("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mobileMoneyProviders_catalogueDuPayeur() throws Exception {
        when(mobileMoney.providersForPayer(bidId, supplementId, "uid-s", "+221770000001"))
                .thenReturn(new MobileMoneyPayerProvidersResponse("SN", "XOF", "+221 •••• 01", "ORANGE_SEN",
                        List.of(new MobileMoneyProvidersResponse.ProviderOption("ORANGE_SEN", "Orange Money", true)),
                        List.of("Orange Money"), "Awa"));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/providers")
                        .with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\": \"+221770000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detected").value("ORANGE_SEN"))
                .andExpect(jsonPath("$.providers[0].code").value("ORANGE_SEN"));
        when(mobileMoney.providersForPayer(bidId, supplementId, "uid-s", null))
                .thenReturn(new MobileMoneyPayerProvidersResponse("SN", "XOF", "+221 •••• 01", null, List.of(),
                        List.of(), "Awa"));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/mobile-money/providers")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk());
    }
    // ── Rail espèces (B4) ────────────────────────────────────────────────────

    private BidSupplementResponse cashPending(String clientSecret) {
        return new BidSupplementResponse(supplementId, "COMMISSION_PENDING", "CASH", "EUR", new BigDecimal("20.00"),
                new BigDecimal("1.10"), new BigDecimal("21.10"), "GRID_ITEM", null, null, "https://signed",
                OffsetDateTime.parse("2026-10-19T12:00:00Z"), null, "REQUIRES_3DS", true, clientSecret);
    }

    @Test
    void create_especesCarte3ds_clientSecretDeCommission() throws Exception {
        when(paymentFlow.create(eq(bidId), eq("uid-t"), any(CreateSupplementRequest.class)))
                .thenReturn(cashPending("pi_c_secret"));

        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"netAmount": 20, "reasonCode": "GRID_ITEM", "photoKey": "tracking/%s/1.jpg",
                                 "commissionSource": "CARD", "fundingCurrency": "XOF"}
                                """.formatted(bidId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMMISSION_PENDING"))
                .andExpect(jsonPath("$.commissionStatus").value("REQUIRES_3DS"))
                .andExpect(jsonPath("$.commissionClientSecret").value("pi_c_secret"));

        org.mockito.ArgumentCaptor<CreateSupplementRequest> request =
                org.mockito.ArgumentCaptor.forClass(CreateSupplementRequest.class);
        verify(paymentFlow).create(eq(bidId), eq("uid-t"), request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().commissionSource())
                .isEqualTo(com.yadony.api.payments.cash.CommissionSource.CARD);
        org.assertj.core.api.Assertions.assertThat(request.getValue().fundingCurrency()).isEqualTo("XOF");
    }

    @Test
    void create_sourceDeCommissionInconnue_rejeteeSansAppel() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"netAmount": 20, "reasonCode": "GRID_ITEM", "photoKey": "tracking/%s/1.jpg",
                                 "commissionSource": "BITCOIN"}
                                """.formatted(bidId)))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(paymentFlow);
    }

    @Test
    void create_especesSoldeCourt_422AvecAlternatives() throws Exception {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("availableBalance", new BigDecimal("0.40"));
        props.put("requiredCommission", new BigDecimal("1.10"));
        props.put("shortfall", new BigDecimal("0.70"));
        props.put("hasCard", false);
        props.put("currency", "EUR");
        props.put("bidCurrency", "EUR");
        props.put("alternatives", List.of(new com.yadony.api.payments.cash.dto.FundingAlternativeDto("XOF",
                new BigDecimal("5000"), new BigDecimal("722"))));
        when(paymentFlow.create(eq(bidId), eq("uid-t"), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-commission-unfunded", "Supplement Commission Unfunded",
                "solde court", props));

        mockMvc.perform(post("/bids/" + bidId + "/supplements").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(bidId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("supplement-commission-unfunded"))
                .andExpect(jsonPath("$.shortfall").value(0.70))
                .andExpect(jsonPath("$.hasCard").value(false))
                .andExpect(jsonPath("$.alternatives[0].currency").value("XOF"))
                .andExpect(jsonPath("$.alternatives[0].required").value(722));
    }

    @Test
    void confirmerCommission_voyageur200_expediteurSeul403() throws Exception {
        when(paymentFlow.confirmCommission(bidId, supplementId, "uid-t")).thenReturn(response("PROPOSED", false));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/commission/confirm")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.netAmount").value(20.00));
        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/commission/confirm")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isForbidden());
        verify(paymentFlow).confirmCommission(bidId, supplementId, "uid-t");
    }

    @Test
    void accepter_especes_payeSansClientSecret() throws Exception {
        BidSupplementResponse paid = new BidSupplementResponse(supplementId, "PAID", "CASH", "EUR", null, null,
                new BigDecimal("21.10"), "GRID_ITEM", null, null, "https://signed",
                OffsetDateTime.parse("2026-10-19T12:00:00Z"), OffsetDateTime.parse("2026-10-19T10:05:00Z"), null, false);
        when(paymentFlow.accept(bidId, supplementId, "uid-s")).thenReturn(new SupplementAcceptResponse(paid, "CASH",
                null, null, new BigDecimal("21.10"), "EUR"));

        mockMvc.perform(post("/bids/" + bidId + "/supplements/" + supplementId + "/accept")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rail").value("CASH"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.amount").value(21.10))
                .andExpect(jsonPath("$.supplement.status").value("PAID"))
                .andExpect(jsonPath("$.supplement.netAmount").doesNotExist());
    }
}
