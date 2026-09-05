package com.yadony.api.payments.mobilemoney;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MobileMoneyPaymentControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean MobileMoneyBidPaymentService service;
    @MockitoBean UserRepository userRepository;

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserEntity s = new UserEntity();
        ReflectionTestUtils.setField(s, "id", SENDER_ID);
        UserEntity t = new UserEntity();
        ReflectionTestUtils.setField(t, "id", TRAVELER_ID);
        when(userRepository.findByFirebaseUid("s-uid")).thenReturn(Optional.of(s));
        when(userRepository.findByFirebaseUid("t-uid")).thenReturn(Optional.of(t));
    }

    private static UsernamePasswordAuthenticationToken auth(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static MobileMoneyPaymentStatusResponse response(String bidStatus, String paymentStatus) {
        return new MobileMoneyPaymentStatusResponse(BID_ID, bidStatus, paymentStatus, LocalDateTime.of(2026, 9, 4, 12, 0),
                new BigDecimal("16800"), "XOF",
                new MobileMoneyPaymentStatusResponse.OperationView(UUID.randomUUID(), "ACCEPTED", "ORANGE_SEN", "Orange Money",
                        "+221 •••• 67", null, null, null));
    }

    @Test
    void accept_travelerOnly() throws Exception {
        when(service.acceptBid(BID_ID, TRAVELER_ID)).thenReturn(response("AWAITING_PAYMENT", "PENDING"));
        mockMvc.perform(post("/bids/{bidId}/mobile-money/accept", BID_ID).with(authentication(auth("t-uid", "ROLE_TRAVELER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bidStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.deadlineAt").exists());
        mockMvc.perform(post("/bids/{bidId}/mobile-money/accept", BID_ID).with(authentication(auth("s-uid", "ROLE_SENDER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void initiate_senderOnly_withOptionalPhone() throws Exception {
        when(service.initiateDeposit(BID_ID, SENDER_ID, "+221771234567")).thenReturn(response("AWAITING_PAYMENT", "PENDING"));
        when(service.initiateDeposit(eq(BID_ID), eq(SENDER_ID), isNull())).thenReturn(response("AWAITING_PAYMENT", "PENDING"));
        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID).with(authentication(auth("s-uid", "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+221771234567\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.deposit.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.deposit.msisdnMasked").value("+221 •••• 67"));
        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID).with(authentication(auth("s-uid", "ROLE_SENDER"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID).with(authentication(auth("t-uid", "ROLE_TRAVELER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void status_forBothParties_andAnonymousRejected() throws Exception {
        when(service.status(BID_ID, SENDER_ID)).thenReturn(response("ACCEPTED", "ESCROW"));
        when(service.status(BID_ID, TRAVELER_ID)).thenReturn(response("ACCEPTED", "ESCROW"));
        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID).with(authentication(auth("s-uid", "ROLE_SENDER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("ESCROW"));
        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID).with(authentication(auth("t-uid", "ROLE_TRAVELER"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID)).andExpect(status().is4xxClientError());
    }
}
