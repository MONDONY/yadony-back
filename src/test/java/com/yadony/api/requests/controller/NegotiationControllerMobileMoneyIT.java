package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.mobilemoney.MobileMoneyNegotiationPaymentService;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyNegotiationStatusResponse;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.service.NegotiationService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NegotiationControllerMobileMoneyIT {

    @Autowired private MockMvc mockMvc;

    @MockBean private NegotiationService service;
    @MockBean private MobileMoneyNegotiationPaymentService mobileMoney;
    @MockBean private com.yadony.api.payments.PaymentService paymentService;
    @MockBean private UserRepository userRepository;

    private static final UUID SENDER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() {
        UserEntity sender = new UserEntity();
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(sender, SENDER_UUID);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void initiate_preparesThenInitiates_returns201() throws Exception {
        UUID threadId = UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        when(service.prepareMobileMoneyDeposit(SENDER_UUID, threadId))
                .thenReturn(new NegotiationService.PreparedDeposit(threadId, UUID.randomUUID(), new BigDecimal("33000"), "XOF", expiresAt));
        when(mobileMoney.initiateDeposit(eq(threadId), eq(SENDER_UUID), eq("+221771234567"), eq(expiresAt)))
                .thenReturn(new MobileMoneyNegotiationStatusResponse(threadId, "PENDING", expiresAt, new BigDecimal("33000"), "XOF", null));

        mockMvc.perform(post("/negotiations/" + threadId + "/mobile-money/initiate")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+221771234567\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.threadId").value(threadId.toString()))
            .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.amount").value(33000));

        var order = inOrder(service, mobileMoney);
        order.verify(service).prepareMobileMoneyDeposit(SENDER_UUID, threadId);
        order.verify(mobileMoney).initiateDeposit(threadId, SENDER_UUID, "+221771234567", expiresAt);
    }

    @Test
    void initiate_methodNotAvailable_returns422ProblemDetail() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.prepareMobileMoneyDeposit(SENDER_UUID, threadId))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "payment-method/not-in-available-set"));

        mockMvc.perform(post("/negotiations/" + threadId + "/mobile-money/initiate")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.code").value("payment-method/not-in-available-set"));
        verify(mobileMoney, never()).initiateDeposit(any(), any(), any(), any());
    }

    @Test
    void status_participant_returnsStatusWithThreadDeadline() throws Exception {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(12);
        thread.setDepositExpiresAt(expiresAt);
        when(service.requireParticipantThread(SENDER_UUID, threadId)).thenReturn(thread);
        when(mobileMoney.status(threadId, expiresAt))
                .thenReturn(new MobileMoneyNegotiationStatusResponse(threadId, "PENDING", expiresAt, new BigDecimal("33000"), "XOF", null));

        mockMvc.perform(get("/negotiations/" + threadId + "/mobile-money/status")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    void cancelDeposit_returns204() throws Exception {
        UUID threadId = UUID.randomUUID();
        mockMvc.perform(post("/negotiations/" + threadId + "/mobile-money/cancel-deposit")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isNoContent());
        verify(service).cancelMobileMoneyDeposit(SENDER_UUID, threadId);
    }

    @Test
    void initiate_asTraveler_is403() throws Exception {
        mockMvc.perform(post("/negotiations/" + UUID.randomUUID() + "/mobile-money/initiate")
                .with(authentication(authAs("uid-sender", "TRAVELER"))))
            .andExpect(status().isForbidden());
    }
}
