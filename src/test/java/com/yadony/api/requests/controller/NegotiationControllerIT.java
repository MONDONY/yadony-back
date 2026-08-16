package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.requests.dto.*;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.service.NegotiationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NegotiationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private NegotiationService service;
    @MockBean private UserRepository userRepository;
    @MockBean private com.yadony.api.payments.PaymentService paymentService;

    private static final UUID SENDER_UUID = UUID.randomUUID();
    private static final UUID TRAVELER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() {
        UserEntity sender = new UserEntity();
        UserEntity traveler = new UserEntity();
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(sender, SENDER_UUID);
            idField.set(traveler, TRAVELER_UUID);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private NegotiationThreadResponse fakeThread(UUID threadId, NegotiationThreadStatus status, String clientSecret) {
        return new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, // travelerCapacityUnit
            status, new BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            List.of(), clientSecret,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new BigDecimal("5"),
            "Chaka D.",
            null,   // senderPhotoUrl
            false,  // isMyTurn
            false,  // canAccept
            false,  // canCounter
            4,      // roundsRemaining
            null,   // linkedTrip
            new BigDecimal("33.60"), // grossPriceEur (30 * 1.12)
            null,   // paymentMethod
            null,   // materializedBidId
            true,   // cashCommissionAvailable
            null,   // availablePaymentMethods
            false,  // canNudge
            false   // hasUnread
        );
    }

    @Test
    void post_start_returns201() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.start(eq(TRAVELER_UUID), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        var req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );

        mockMvc.perform(post("/negotiations")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(threadId.toString()));
    }

    @Test
    void post_start_withoutTravelerRole_returns403() throws Exception {
        var req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );

        mockMvc.perform(post("/negotiations")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_counter_returnsThread() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.counter(eq(SENDER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        var req = new NegotiationCounterRequest(new BigDecimal("25"), null);

        mockMvc.perform(post("/negotiations/" + threadId + "/counter")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(threadId.toString()));
    }

    @Test
    void post_accept_returnsThreadWithClientSecret() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.accept(eq(SENDER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.ACCEPTED, "pi_test_secret"));

        var req = new NegotiationAcceptRequest("Deal!");

        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentIntentClientSecret").value("pi_test_secret"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void accept_asTraveler_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.accept(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.AWAITING_TRIP, null));

        var req = new NegotiationAcceptRequest(null);
        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }

    @Test
    void accept_response_contains_calculated_fields() throws Exception {
        UUID threadId = UUID.randomUUID();
        // Build a fakeThread with isMyTurn=true, canAccept=true, canCounter=false, roundsRemaining=3
        NegotiationThreadResponse thread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, // travelerCapacityUnit
            NegotiationThreadStatus.OPEN, new BigDecimal("30"), 2,
            LocalDateTime.now(), LocalDateTime.now(),
            List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new BigDecimal("5"),
            "Chaka D.",
            null,   // senderPhotoUrl
            true,   // isMyTurn
            true,   // canAccept
            false,  // canCounter
            3,      // roundsRemaining
            null,   // linkedTrip
            new BigDecimal("33.60"), // grossPriceEur
            null,   // paymentMethod
            null,   // materializedBidId
            true,   // cashCommissionAvailable
            null,   // availablePaymentMethods
            false,  // canNudge
            false   // hasUnread
        );
        when(service.accept(eq(SENDER_UUID), eq(threadId), any())).thenReturn(thread);

        var req = new NegotiationAcceptRequest("Deal!");
        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isMyTurn").value(true))
            .andExpect(jsonPath("$.canAccept").value(true))
            .andExpect(jsonPath("$.canCounter").value(false))
            .andExpect(jsonPath("$.roundsRemaining").value(3));
    }

    @Test
    void post_reject_returns204() throws Exception {
        UUID threadId = UUID.randomUUID();
        var req = new NegotiationRejectRequest("Trop cher");
        mockMvc.perform(post("/negotiations/" + threadId + "/reject")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());
    }

    @Test
    void counter_serviceThrowsConflict_returnsProblemDetail() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.counter(eq(SENDER_UUID), eq(threadId), any()))
            .thenThrow(new ResponseStatusException(CONFLICT, "negotiation/not-your-turn"));

        var req = new NegotiationCounterRequest(new BigDecimal("25"), null);

        mockMvc.perform(post("/negotiations/" + threadId + "/counter")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("negotiation/not-your-turn")));
    }

    @Test
    void refuseTrip_asSender_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadResponse updated = fakeThread(
            threadId, NegotiationThreadStatus.AWAITING_TRIP, null);
        when(service.refuseTrip(eq(SENDER_UUID), eq(threadId), any())).thenReturn(updated);

        mockMvc.perform(post("/negotiations/{id}/refuse-trip", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AWAITING_TRIP"));
    }

    @Test
    void refuseTrip_asTraveler_returns403() throws Exception {
        mockMvc.perform(post("/negotiations/{id}/refuse-trip", UUID.randomUUID())
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_start_unauthenticated_returns401() throws Exception {
        var req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );

        mockMvc.perform(post("/negotiations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_findMine_returnsThreadList() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.listMine(eq(SENDER_UUID)))
            .thenReturn(List.of(fakeThread(threadId, NegotiationThreadStatus.OPEN, null)));

        mockMvc.perform(get("/negotiations/me")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(threadId.toString()));
    }

    @Test
    void post_checkout_returns200_andDelegatesFinalize() throws Exception {
        UUID threadId = UUID.randomUUID();
        // No paymentMethod in the body → controller delegates with a null method.
        when(service.checkout(eq(SENDER_UUID), eq(threadId), eq("pi_test"), isNull()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.ACCEPTED, "pi_test"));

        var req = new java.util.HashMap<String, String>();
        req.put("paymentIntentId", "pi_test");

        mockMvc.perform(post("/negotiations/{id}/checkout", threadId)
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void post_checkout_withPaymentMethod_delegatesChosenMethod() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.checkout(eq(SENDER_UUID), eq(threadId), eq("CASH"),
                eq(com.yadony.api.payments.cash.PaymentMethod.CASH)))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.ACCEPTED, "CASH"));

        var req = new java.util.HashMap<String, String>();
        req.put("paymentIntentId", "CASH");
        req.put("paymentMethod", "CASH");

        mockMvc.perform(post("/negotiations/{id}/checkout", threadId)
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
        verify(service).checkout(eq(SENDER_UUID), eq(threadId), eq("CASH"),
            eq(com.yadony.api.payments.cash.PaymentMethod.CASH));
    }

    // ── POST /negotiations/{id}/settle-commission ────────────────────────────────

    @Test
    void post_settleCommission_accepted_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId),
                eq(com.yadony.api.payments.cash.CommissionSource.WALLET_FIRST)))
            .thenReturn(com.yadony.api.payments.cash.dto.AcceptBidResponse.accepted());

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void post_settleCommission_withCardSource_delegatesCommissionSource() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId),
                eq(com.yadony.api.payments.cash.CommissionSource.CARD)))
            .thenReturn(com.yadony.api.payments.cash.dto.AcceptBidResponse.accepted());

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .param("commissionSource", "CARD")
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk());

        verify(service).settleCommission(eq(TRAVELER_UUID), eq(threadId),
            eq(com.yadony.api.payments.cash.CommissionSource.CARD));
    }

    @Test
    void post_settleCommission_asSender_returns403() throws Exception {
        UUID threadId = UUID.randomUUID();

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_settleCommission_requires3ds_returns202() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(com.yadony.api.payments.cash.dto.AcceptBidResponse
                .requires3ds("pi_secret", "pi_id"));

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.clientSecret").value("pi_secret"));
    }

    @Test
    void post_settleCommission_failed_returns422() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(com.yadony.api.payments.cash.dto.AcceptBidResponse.failed("card-declined"));

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void post_settleCommission_insufficientWallet_returns409WithAmounts() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(com.yadony.api.payments.cash.dto.AcceptBidResponse.insufficientWallet(
                new BigDecimal("1.00"), new BigDecimal("5.00"), true, "EUR"));

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("INSUFFICIENT_WALLET"))
            .andExpect(jsonPath("$.requiredCommission").value(5.00))
            .andExpect(jsonPath("$.hasCard").value(true));
    }

    @Test
    void post_settleCommission_threadNotAwaitingCommission_returnsProblemDetail() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.settleCommission(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenThrow(new ResponseStatusException(CONFLICT, "thread/not-awaiting-commission"));

        mockMvc.perform(post("/negotiations/{id}/settle-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("thread/not-awaiting-commission")));
    }

    // ── POST /negotiations/{id}/confirm-commission ───────────────────────────────

    @Test
    void post_confirmCommission_accepted_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.confirmCommission(eq(TRAVELER_UUID), eq(threadId)))
            .thenReturn(com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse.ok());

        mockMvc.perform(post("/negotiations/{id}/confirm-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void post_confirmCommission_failed_returns422() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.confirmCommission(eq(TRAVELER_UUID), eq(threadId)))
            .thenReturn(com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse.fail("PaymentIntent status: requires_payment_method"));

        mockMvc.perform(post("/negotiations/{id}/confirm-commission", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void post_confirmCommission_asSender_returns403() throws Exception {
        UUID threadId = UUID.randomUUID();

        mockMvc.perform(post("/negotiations/{id}/confirm-commission", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_refuseTrip_asSender_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.refuseTrip(eq(SENDER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.AWAITING_TRIP, null));

        var req = new java.util.HashMap<String, String>();
        req.put("reason", "Ce voyageur ne convient pas");

        mockMvc.perform(post("/negotiations/{id}/refuse-trip", threadId)
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AWAITING_TRIP"));
    }

    @Test
    void post_submitTrip_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.submitTrip(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.AWAITING_PAYMENT, null));

        var req = new NegotiationSubmitTripRequest(UUID.randomUUID(),
            com.yadony.api.payments.cash.PaymentMethod.STRIPE);

        mockMvc.perform(post("/negotiations/{id}/submit-trip", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void patch_changeTrip_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.changeTrip(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        var req = new com.yadony.api.requests.dto.NegotiationChangeTripRequest(UUID.randomUUID());

        mockMvc.perform(patch("/negotiations/{id}/trip", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void patch_changeTrip_returns409_whenThreadAwaitingPayment() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.changeTrip(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "negotiation-trip-locked"));

        var req = new com.yadony.api.requests.dto.NegotiationChangeTripRequest(UUID.randomUUID());

        mockMvc.perform(patch("/negotiations/{id}/trip", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("negotiation-trip-locked")));
    }

    @Test
    void post_initiatePayment_withAwaitingPaymentThread_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadResponse awaitingPaymentThread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new java.math.BigDecimal("10"),
            null, // travelerCapacityUnit
            NegotiationThreadStatus.AWAITING_PAYMENT, new java.math.BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            java.util.List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new java.math.BigDecimal("5"),
            "Chaka D.",
            null, // senderPhotoUrl
            false, false, false, 4, null,
            new java.math.BigDecimal("33.60"), null,
            null, // materializedBidId
            true, // cashCommissionAvailable
            null, // availablePaymentMethods
            false, // canNudge
            false, // hasUnread
            null,  // promoCode
            "CAD"  // devise serveur du thread
        );
        when(service.getById(eq(SENDER_UUID), eq(threadId))).thenReturn(awaitingPaymentThread);
        when(paymentService.createNegotiationEscrow(
                eq(threadId), eq(SENDER_UUID), eq(TRAVELER_UUID), any(), isNull(), isNull(), eq("CAD")))
            .thenReturn(new com.yadony.api.payments.dto.PaymentResponse(
                UUID.randomUUID(), null, "pi_test_secret",
                new java.math.BigDecimal("33.60"), new java.math.BigDecimal("3.60"),
                "PENDING", "pi_test_id", "CAD"));

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("cad"));
        verify(paymentService).createNegotiationEscrow(
                threadId, SENDER_UUID, TRAVELER_UUID, new java.math.BigDecimal("30"), null, null, "CAD");
        verify(service, org.mockito.Mockito.never()).recordAppliedPromo(any(), any(), any());
    }

    @Test
    void post_initiatePayment_withPromoCode_persistsAppliedPromoOnThread() throws Exception {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadResponse awaitingPaymentThread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new java.math.BigDecimal("10"),
            null,
            NegotiationThreadStatus.AWAITING_PAYMENT, new java.math.BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            java.util.List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new java.math.BigDecimal("5"),
            "Chaka D.",
            null,
            false, false, false, 4, null,
            new java.math.BigDecimal("33.60"), null,
            null,
            true,
            null,
            false,
            false
        );
        when(service.getById(eq(SENDER_UUID), eq(threadId))).thenReturn(awaitingPaymentThread);
        var promoResponse = new com.yadony.api.payments.dto.PaymentResponse(
                UUID.randomUUID(), null, "pi_test_secret",
                new java.math.BigDecimal("31.80"), new java.math.BigDecimal("1.80"),
                "PENDING", "pi_test_id");
        promoResponse.setCommissionRate(new java.math.BigDecimal("0.06"));
        promoResponse.setPromoApplied(true);
        when(paymentService.createNegotiationEscrow(
                eq(threadId), eq(SENDER_UUID), eq(TRAVELER_UUID), any(), eq("WELCOME6"), isNull(), eq("EUR")))
            .thenReturn(promoResponse);

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .param("promoCode", "WELCOME6")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commissionRate").value(0.06))
            .andExpect(jsonPath("$.promoApplied").value(true));
        verify(service).recordAppliedPromo(threadId, "WELCOME6", new java.math.BigDecimal("0.06"));
    }

    @Test
    void post_initiatePayment_noExplicitPromoCode_autoAppliesThreadStoredCode() throws Exception {
        UUID threadId = UUID.randomUUID();
        // Code déjà porté par le thread (copié depuis la demande à start()) — le
        // client n'envoie AUCUN promoCode ; le backend doit l'appliquer seul.
        NegotiationThreadResponse awaitingPaymentThread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new java.math.BigDecimal("10"),
            null,
            NegotiationThreadStatus.AWAITING_PAYMENT, new java.math.BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            java.util.List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new java.math.BigDecimal("5"),
            "Chaka D.",
            null,
            false, false, false, 4, null,
            new java.math.BigDecimal("33.60"), null,
            null,
            true,
            null,
            false,
            false,
            "AUTOCODE"
        );
        when(service.getById(eq(SENDER_UUID), eq(threadId))).thenReturn(awaitingPaymentThread);
        var promoResponse = new com.yadony.api.payments.dto.PaymentResponse(
                UUID.randomUUID(), null, "pi_test_secret",
                new java.math.BigDecimal("31.80"), new java.math.BigDecimal("1.80"),
                "PENDING", "pi_test_id");
        promoResponse.setCommissionRate(new java.math.BigDecimal("0.06"));
        promoResponse.setPromoApplied(true);
        when(paymentService.createNegotiationEscrow(
                eq(threadId), eq(SENDER_UUID), eq(TRAVELER_UUID), any(), eq("AUTOCODE"), isNull(), eq("EUR")))
            .thenReturn(promoResponse);

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promoApplied").value(true));
        verify(service).recordAppliedPromo(threadId, "AUTOCODE", new java.math.BigDecimal("0.06"));
    }

    @Test
    void post_initiatePayment_promoFallsBackToBaseRate_clearsStaleThreadPromoCode() throws Exception {
        // Le thread porte encore un promoCode auto-appliqué (copié depuis la demande), mais
        // createNegotiationEscrow retombe sur le tarif de base (code expiré, limite atteinte…).
        // Le controller doit nettoyer l'état stale du thread, sinon ThreadAcceptedBidListener
        // tentera plus tard un rachat sur un code jamais réellement appliqué.
        UUID threadId = UUID.randomUUID();
        NegotiationThreadResponse staleAppliedPromoThread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new java.math.BigDecimal("10"),
            null,
            NegotiationThreadStatus.AWAITING_PAYMENT, new java.math.BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            java.util.List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new java.math.BigDecimal("5"),
            "Chaka D.",
            null,
            false, false, false, 4, null,
            new java.math.BigDecimal("33.60"), null,
            null,
            true,
            null,
            false,
            false,
            "WELCOME6"
        );
        when(service.getById(eq(SENDER_UUID), eq(threadId))).thenReturn(staleAppliedPromoThread);
        var baseRateResponse = new com.yadony.api.payments.dto.PaymentResponse(
                UUID.randomUUID(), null, "pi_test_secret",
                new java.math.BigDecimal("33.60"), new java.math.BigDecimal("3.60"),
                "PENDING", "pi_test_id");
        // promoApplied reste false par défaut : le fallback tarif de base.
        when(paymentService.createNegotiationEscrow(
                eq(threadId), eq(SENDER_UUID), eq(TRAVELER_UUID), any(), eq("WELCOME6"), isNull(), eq("EUR")))
            .thenReturn(baseRateResponse);

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promoApplied").value(false));
        verify(service).recordAppliedPromo(threadId, null, null);
    }

    @Test
    void post_initiatePayment_twice_preservesPersistedPromoAndCommissionRate() throws Exception {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadResponse firstRead = paymentThread(threadId, null);
        NegotiationThreadResponse persistedRead = paymentThread(threadId, new BigDecimal("0.06"));
        when(service.getById(SENDER_UUID, threadId)).thenReturn(firstRead, persistedRead);

        var created = new com.yadony.api.payments.dto.PaymentResponse(
                UUID.randomUUID(), null, "pi_promo_secret",
                new BigDecimal("31.80"), new BigDecimal("1.80"),
                "PENDING", "pi_promo", "EUR");
        created.setCommissionRate(new BigDecimal("0.06"));
        created.setPromoApplied(true);
        var resumed = new com.yadony.api.payments.dto.PaymentResponse(
                created.getId(), null, "pi_promo_secret",
                new BigDecimal("31.80"), new BigDecimal("1.80"),
                "PENDING", "pi_promo", "EUR");
        resumed.setCommissionRate(new BigDecimal("0.06"));
        resumed.setPromoApplied(true);

        when(paymentService.createNegotiationEscrow(
                threadId, SENDER_UUID, TRAVELER_UUID, new BigDecimal("30"),
                "WELCOME6", null, "EUR"))
                .thenReturn(created);
        when(paymentService.createNegotiationEscrow(
                threadId, SENDER_UUID, TRAVELER_UUID, new BigDecimal("30"),
                "WELCOME6", new BigDecimal("0.06"), "EUR"))
                .thenReturn(resumed);

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commissionRate").value(0.06))
            .andExpect(jsonPath("$.promoApplied").value(true));
        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .param("promoCode", "OTHER")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commissionRate").value(0.06))
            .andExpect(jsonPath("$.promoApplied").value(true));

        verify(service, org.mockito.Mockito.times(2))
                .recordAppliedPromo(threadId, "WELCOME6", new BigDecimal("0.06"));
        verify(paymentService, org.mockito.Mockito.never()).createNegotiationEscrow(
                threadId, SENDER_UUID, TRAVELER_UUID, new BigDecimal("30"),
                "OTHER", new BigDecimal("0.06"), "EUR");
        verify(service, org.mockito.Mockito.never())
                .recordAppliedPromo(threadId, "OTHER", new BigDecimal("0.06"));
        verify(service, org.mockito.Mockito.never()).recordAppliedPromo(threadId, null, null);
    }

    private NegotiationThreadResponse paymentThread(UUID threadId, BigDecimal persistedRate) {
        return org.mockito.Mockito.mock(NegotiationThreadResponse.class, invocation -> switch (
                invocation.getMethod().getName()) {
            case "id" -> threadId;
            case "travelerId" -> TRAVELER_UUID;
            case "status" -> NegotiationThreadStatus.AWAITING_PAYMENT;
            case "currentPriceEur" -> new BigDecimal("30");
            case "promoCode" -> "WELCOME6";
            case "commissionRate" -> persistedRate;
            case "currency" -> "EUR";
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    @Test
    void get_quote_withPromoCode_returnsBreakdown() throws Exception {
        UUID threadId = UUID.randomUUID();
        var quote = new NegotiationQuoteResponse(
            new java.math.BigDecimal("40.00"), new java.math.BigDecimal("0.12"),
            new java.math.BigDecimal("4.80"), new java.math.BigDecimal("42.40"),
            true, "Code WELCOME6 : 6 % de réduction");
        when(service.quote(eq(SENDER_UUID), eq(threadId), eq("WELCOME6"))).thenReturn(quote);

        mockMvc.perform(get("/negotiations/{id}/quote", threadId)
                .param("promoCode", "WELCOME6")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalEur").value(42.40))
            .andExpect(jsonPath("$.promoApplied").value(true));
    }

    @Test
    void post_initiatePayment_withWrongStatus_returns409() throws Exception {
        UUID threadId = UUID.randomUUID();
        // Thread is OPEN, not AWAITING_PAYMENT
        when(service.getById(eq(SENDER_UUID), eq(threadId)))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        mockMvc.perform(post("/negotiations/{id}/initiate-payment", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isConflict());
    }

    @Test
    void post_createDedicatedTrip_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.createDedicatedTrip(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.AWAITING_PAYMENT, null));

        var pickup = new com.yadony.api.matching.dto.AddressDto("10 rue de la Paix, Paris", 48.86, 2.33);
        var delivery = new com.yadony.api.matching.dto.AddressDto("Plateau, Dakar", 14.69, -17.44);
        var req = new NegotiationCreateDedicatedTripRequest(
            LocalDate.now().plusDays(7), null, null,
            pickup, delivery, null, null, null
        );

        mockMvc.perform(post("/negotiations/{id}/create-dedicated-trip", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void get_listForRequest_returns200_viaNegotiationEndpoint() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.listMine(eq(TRAVELER_UUID)))
            .thenReturn(java.util.List.of(fakeThread(threadId, NegotiationThreadStatus.OPEN, null)));

        mockMvc.perform(get("/negotiations/me")
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(threadId.toString()));
    }

    // ─── Task 13 — nouveaux cas IT ──────────────────────────────────────────────

    /**
     * Cas 3 : contre-offre sur une demande à prix ferme → 409 avec code "counter-not-allowed-firm-price"
     */
    @Test
    void counter_onFirmPriceRequest_returns409_withExpectedCode() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.counter(eq(SENDER_UUID), eq(threadId), any()))
            .thenThrow(new ResponseStatusException(CONFLICT, "negotiation/counter-not-allowed-firm-price"));

        var req = new NegotiationCounterRequest(new BigDecimal("25"), null);

        mockMvc.perform(post("/negotiations/" + threadId + "/counter")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("negotiation/counter-not-allowed-firm-price")));
    }

    /**
     * Cas 4 : le thread contient grossPriceEur non null quand currentPriceEur est défini.
     */
    @Test
    void thread_response_grossPriceEur_isNotNull_whenCurrentPriceSet() throws Exception {
        UUID threadId = UUID.randomUUID();
        // fakeThread sets currentPriceEur=30 and grossPriceEur=33.60 (30 * 1.12)
        when(service.getById(eq(TRAVELER_UUID), eq(threadId)))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        mockMvc.perform(get("/negotiations/{id}", threadId)
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentPriceEur").value(30))
            .andExpect(jsonPath("$.grossPriceEur").isNotEmpty())
            .andExpect(jsonPath("$.grossPriceEur").value(33.60));
    }

    @Test
    void thread_response_contains_linkedTrip_field() throws Exception {
        UUID threadId = UUID.randomUUID();
        var trip = new com.yadony.api.requests.dto.LinkedTripSummary(
            UUID.randomUUID(), "Paris", "Dakar", "2026-06-12", "14:30",
            "PLANE", "CDG Terminal 2E", "Yoff Virage", 8, "KG_FREE", "Colis fragile");
        NegotiationThreadResponse withTrip = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID,
            trip.announcementId(), LocalDate.now(), new BigDecimal("5.0"),
            "KG_FREE", // travelerCapacityUnit
            NegotiationThreadStatus.AWAITING_PAYMENT, new BigDecimal("45.0"), 2,
            LocalDateTime.now(), LocalDateTime.now(),
            List.of(), null,
            "Moussa T.", new BigDecimal("4.5"), 12, null,
            "Paris", "Dakar", new BigDecimal("5.0"),
            "Amadou S.",
            null, // senderPhotoUrl
            false, false, false, 3,
            trip,
            new BigDecimal("50.40"), // grossPriceEur (45 * 1.12)
            null, // paymentMethod
            null, // materializedBidId
            true, // cashCommissionAvailable
            null, // availablePaymentMethods
            false, // canNudge
            false  // hasUnread
        );
        when(service.getById(eq(SENDER_UUID), eq(threadId))).thenReturn(withTrip);

        mockMvc.perform(get("/negotiations/{id}", threadId)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkedTrip.departureCity").value("Paris"))
            .andExpect(jsonPath("$.linkedTrip.arrivalCity").value("Dakar"))
            .andExpect(jsonPath("$.linkedTrip.availableKg").value(8))
            .andExpect(jsonPath("$.linkedTrip.capacityUnit").value("KG_FREE"))
            .andExpect(jsonPath("$.travelerCapacityUnit").value("KG_FREE"));
    }

    // ─── open-surplus ────────────────────────────────────────────────────────────

    @Test
    void post_openSurplus_asTraveler_returns204_andDelegates() throws Exception {
        UUID announcementId = UUID.randomUUID();
        var req = new OpenSurplusRequest(new BigDecimal("8"), new BigDecimal("7"));

        mockMvc.perform(post("/negotiations/trip/{announcementId}/open-surplus", announcementId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());

        verify(service).openSurplus(eq(TRAVELER_UUID), eq(announcementId),
            eq(new BigDecimal("8")), eq(new BigDecimal("7")));
    }

    @Test
    void post_openSurplus_missingSurplusKg_returns422() throws Exception {
        UUID announcementId = UUID.randomUUID();
        // pricePerKg only — surplusKg null → Bean Validation rejects
        var body = new java.util.HashMap<String, Object>();
        body.put("pricePerKg", 7);

        mockMvc.perform(post("/negotiations/trip/{announcementId}/open-surplus", announcementId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void post_openSurplus_asSender_returns403() throws Exception {
        UUID announcementId = UUID.randomUUID();
        var req = new OpenSurplusRequest(new BigDecimal("8"), new BigDecimal("7"));

        mockMvc.perform(post("/negotiations/trip/{announcementId}/open-surplus", announcementId)
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_openSurplus_serviceConflict_returnsProblemDetail() throws Exception {
        UUID announcementId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResponseStatusException(CONFLICT, "surplus/already-open"))
            .when(service).openSurplus(eq(TRAVELER_UUID), eq(announcementId), any(), any());

        var req = new OpenSurplusRequest(new BigDecimal("8"), new BigDecimal("7"));

        mockMvc.perform(post("/negotiations/trip/{announcementId}/open-surplus", announcementId)
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("surplus/already-open")));
    }
}
