package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
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
        when(service.create(eq(bidId), eq("uid-t"), any(CreateSupplementRequest.class)))
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
        verifyNoInteractions(service);
    }

    @Test
    void create_erreurMetier_problemJsonAvecCodeEtProprietes() throws Exception {
        when(service.create(eq(bidId), eq("uid-t"), any())).thenThrow(new YadonyBusinessException(
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
        verifyNoInteractions(service);
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
        when(service.accept(bidId, supplementId, "uid-s")).thenThrow(new YadonyBusinessException(
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
        verify(service, never()).accept(any(), any(), any());
        verify(service, never()).decline(any(), any(), any());
    }
}
