package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.AmendmentOptionsResponse;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteRequest;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

/** Routes {@code /bids/{bidId}/amendments} : rôles, contrats JSON et erreurs RFC 7807. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BidAmendmentControllerIT {

    @Autowired MockMvc mockMvc;
    @MockBean BidAmendmentService service;
    @MockBean BidAmendmentPaymentFlow paymentFlow;

    private final UUID bidId = UUID.randomUUID();
    private final UUID amendmentId = UUID.randomUUID();

    private static final String CREATE_BODY = """
            {"addedKg": 3, "items": [{"gridItemId": "%s", "quantity": 1}], "paymentMethod": "CASH"}
            """;

    private static UsernamePasswordAuthenticationToken as(String uid, String... roles) {
        return new UsernamePasswordAuthenticationToken(uid, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private BidAmendmentResponse response(String status, boolean forSender) {
        return new BidAmendmentResponse(amendmentId, status, "CASH", "EUR", new BigDecimal("3.00"),
                List.of(new BidAmendmentResponse.Item("Téléphone", 1, new BigDecimal("16.80"),
                        forSender ? null : new BigDecimal("15.00"))),
                new BigDecimal("50.40"), forSender ? null : new BigDecimal("45.00"),
                forSender ? null : new BigDecimal("5.40"), OffsetDateTime.parse("2026-10-25T10:00:00Z"), null, null,
                "PROPOSED".equals(status), forSender ? null : "CHARGED", null);
    }

    // ── Rôles ────────────────────────────────────────────────────────────────

    @Test
    void create_asTraveler_403() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/amendments").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void sansJeton_401() throws Exception {
        mockMvc.perform(get("/bids/" + bidId + "/amendments/options")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void actionsExpediteur_refuseesAuSeulRoleVoyageur() throws Exception {
        mockMvc.perform(get("/bids/" + bidId + "/amendments/options").with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/bids/" + bidId + "/amendments/quote").with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addedKg\": 1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/bids/" + bidId + "/amendments/" + amendmentId)
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void actionsVoyageur_refuseesAuSeulRoleExpediteur() throws Exception {
        for (String action : List.of("decline", "accept", "commission/confirm", "not-brought")) {
            mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/" + action)
                            .with(authentication(as("uid-s", "SENDER"))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void routesNonLivrees_absentes() throws Exception {
        // confirm-authorization (B9) et mobile money (B11) : pas de route sans implémentation.
        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/confirm-authorization")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/mobile-money/initiate")
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(service, paymentFlow);
    }

    // ── Expéditeur ───────────────────────────────────────────────────────────

    @Test
    void options_flagOff_422AmendmentDisabled() throws Exception {
        when(service.options(bidId, "uid-s")).thenThrow(BidAmendmentEligibility.exceptionFor("amendment-disabled"));

        mockMvc.perform(get("/bids/" + bidId + "/amendments/options").with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("amendment-disabled"));
    }

    @Test
    void options_expediteur() throws Exception {
        UUID gridId = UUID.randomUUID();
        when(service.options(bidId, "uid-s")).thenReturn(new AmendmentOptionsResponse("EUR", new BigDecimal("6.00"),
                new BigDecimal("11.20"), List.of(new AmendmentOptionsResponse.GridItemOption(gridId, "Téléphone",
                new BigDecimal("16.80"))), List.of("CASH", "STRIPE"), OffsetDateTime.parse("2026-10-25T10:00:00Z"),
                new BigDecimal("1.00"), new BigDecimal("300.00")));

        mockMvc.perform(get("/bids/" + bidId + "/amendments/options").with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableKg").value(6.00))
                .andExpect(jsonPath("$.gridItems[0].id").value(gridId.toString()))
                .andExpect(jsonPath("$.paymentMethods[1]").value("STRIPE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-25T10:00:00Z"));
    }

    @Test
    void quote_expediteur_brutSeul() throws Exception {
        when(service.quote(eq(bidId), eq("uid-s"), any(AmendmentQuoteRequest.class)))
                .thenReturn(new AmendmentQuoteResponse(new BigDecimal("33.60"), "EUR"));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/quote").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addedKg\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grossAmount").value(33.60))
                .andExpect(jsonPath("$.netAmount").doesNotExist())
                .andExpect(jsonPath("$.commissionAmount").doesNotExist());
    }

    @Test
    void quote_kilosNegatifs_422Validation() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/amendments/quote").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addedKg\": -1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void create_expediteur_201_sansNet() throws Exception {
        UUID gridId = UUID.randomUUID();
        when(paymentFlow.create(eq(bidId), eq("uid-s"), any(CreateAmendmentRequest.class)))
                .thenReturn(AmendmentCreateResponse.of(response("PROPOSED", true)));

        mockMvc.perform(post("/bids/" + bidId + "/amendments").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(gridId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amendment.id").value(amendmentId.toString()))
                .andExpect(jsonPath("$.amendment.status").value("PROPOSED"))
                .andExpect(jsonPath("$.amendment.grossAmount").value(50.40))
                .andExpect(jsonPath("$.amendment.netAmount").doesNotExist())
                .andExpect(jsonPath("$.amendment.commissionAmount").doesNotExist())
                .andExpect(jsonPath("$.amendment.items[0].netAmount").doesNotExist())
                .andExpect(jsonPath("$.amendment.blocksHandover").value(true))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

        ArgumentCaptor<CreateAmendmentRequest> request = ArgumentCaptor.forClass(CreateAmendmentRequest.class);
        verify(paymentFlow).create(eq(bidId), eq("uid-s"), request.capture());
        assertThat(request.getValue().addedKg()).isEqualByComparingTo("3");
        assertThat(request.getValue().items().get(0).gridItemId()).isEqualTo(gridId);
        assertThat(request.getValue().paymentMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void create_sansMoyenDePaiement_422Validation() throws Exception {
        mockMvc.perform(post("/bids/" + bidId + "/amendments").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addedKg\": 3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service, paymentFlow);
    }

    @Test
    void create_problemJson_onCapacity409() throws Exception {
        when(paymentFlow.create(eq(bidId), eq("uid-s"), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.CONFLICT, "amendment-capacity-unavailable", "Amendment Capacity Unavailable",
                "Il ne reste plus assez de place sur ce trajet"));

        mockMvc.perform(post("/bids/" + bidId + "/amendments").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("amendment-capacity-unavailable"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void create_interblocage_409PaymentBusy() throws Exception {
        when(paymentFlow.create(eq(bidId), eq("uid-s"), any())).thenThrow(PaymentBusy.exception());

        mockMvc.perform(post("/bids/" + bidId + "/amendments").with(authentication(as("uid-s", "SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payment-busy"));
    }

    @Test
    void annulation_expediteur() throws Exception {
        when(service.cancel(bidId, amendmentId, "uid-s")).thenReturn(response("CANCELLED", true));

        mockMvc.perform(delete("/bids/" + bidId + "/amendments/" + amendmentId)
                        .with(authentication(as("uid-s", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.netAmount").doesNotExist())
                .andExpect(jsonPath("$.commissionStatus").doesNotExist());
    }

    // ── Voyageur ─────────────────────────────────────────────────────────────

    @Test
    void refus_voyageur() throws Exception {
        when(service.decline(bidId, amendmentId, "uid-t")).thenReturn(response("DECLINED", false));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/decline")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.netAmount").value(45.00));
    }

    @Test
    void acceptation_voyageur_corpsTransmis() throws Exception {
        when(paymentFlow.accept(eq(bidId), eq(amendmentId), eq("uid-t"), any(AmendmentAcceptRequest.class)))
                .thenReturn(new AmendmentAcceptResponse(response("COMMISSION_PENDING", false), "pi_1_secret_x"));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/accept")
                        .with(authentication(as("uid-t", "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commissionSource\": \"CARD\", \"fundingCurrency\": \"XOF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amendment.status").value("COMMISSION_PENDING"))
                .andExpect(jsonPath("$.amendment.netAmount").value(45.00))
                .andExpect(jsonPath("$.commissionClientSecret").value("pi_1_secret_x"));

        ArgumentCaptor<AmendmentAcceptRequest> request = ArgumentCaptor.forClass(AmendmentAcceptRequest.class);
        verify(paymentFlow).accept(eq(bidId), eq(amendmentId), eq("uid-t"), request.capture());
        assertThat(request.getValue().commissionSource()).isEqualTo(CommissionSource.CARD);
        assertThat(request.getValue().fundingCurrency()).isEqualTo("XOF");
    }

    @Test
    void acceptation_sansCorps_requeteVide() throws Exception {
        when(paymentFlow.accept(eq(bidId), eq(amendmentId), eq("uid-t"), any(AmendmentAcceptRequest.class)))
                .thenReturn(new AmendmentAcceptResponse(response("ACCEPTED", false), null));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/accept")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amendment.status").value("ACCEPTED"));

        ArgumentCaptor<AmendmentAcceptRequest> request = ArgumentCaptor.forClass(AmendmentAcceptRequest.class);
        verify(paymentFlow).accept(eq(bidId), eq(amendmentId), eq("uid-t"), request.capture());
        assertThat(request.getValue().commissionSource()).isNull();
    }

    @Test
    void confirmationDeLaCommission_voyageur() throws Exception {
        when(paymentFlow.confirmCommission(bidId, amendmentId, "uid-t")).thenReturn(response("ACCEPTED", false));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/commission/confirm")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void nonApportee_voyageur() throws Exception {
        when(service.notBrought(bidId, amendmentId, "uid-t")).thenReturn(response("REFUNDED", false));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/not-brought")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void nonApportee_colisDejaRemis_422ProblemJson() throws Exception {
        when(service.notBrought(bidId, amendmentId, "uid-t"))
                .thenThrow(BidAmendmentEligibility.exceptionFor("invalid-bid-status"));

        mockMvc.perform(post("/bids/" + bidId + "/amendments/" + amendmentId + "/not-brought")
                        .with(authentication(as("uid-t", "TRAVELER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-bid-status"));
    }
}
