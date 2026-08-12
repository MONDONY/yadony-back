package com.yadony.api.payments.cash;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.CommissionMethodResponse;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.cash.dto.SetupCommissionMethodResponse;
import com.yadony.api.payments.cash.ExpirationStatus;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CashCommissionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CashCommissionService cashCommissionService;
    @MockBean UserRepository userRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    @BeforeEach
    void stubUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-test");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
    }

    private UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                "uid-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                "uid-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    // ── POST /traveler/commission-method/setup ───────────────────────────────────

    @Test
    void setupMethod_travelerRole_returns200WithClientSecret() throws Exception {
        when(cashCommissionService.setupCommissionMethod(USER_ID))
                .thenReturn(new SetupCommissionMethodResponse("seti_secret_test"));

        mockMvc.perform(post("/traveler/commission-method/setup")
                .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("seti_secret_test"));
    }

    @Test
    void setupMethod_senderRole_returns403() throws Exception {
        mockMvc.perform(post("/traveler/commission-method/setup")
                .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void setupMethod_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/traveler/commission-method/setup"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /traveler/commission-method/save ────────────────────────────────────

    @Test
    void saveMethod_travelerRole_returns204() throws Exception {
        doNothing().when(cashCommissionService).saveCommissionMethod(USER_ID, "pm_test_123");

        mockMvc.perform(post("/traveler/commission-method/save")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_test_123\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void saveMethod_blankPaymentMethodId_returns422() throws Exception {
        mockMvc.perform(post("/traveler/commission-method/save")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /traveler/commission-method ──────────────────────────────────────────

    @Test
    void getMethod_withMethod_returns200() throws Exception {
        when(cashCommissionService.getCommissionMethod(USER_ID))
                .thenReturn(new CommissionMethodResponse("Visa", "4242", 12, 2027, ExpirationStatus.VALID));

        mockMvc.perform(get("/traveler/commission-method")
                .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last4").value("4242"));
    }

    @Test
    void getMethod_noMethod_returns204() throws Exception {
        when(cashCommissionService.getCommissionMethod(USER_ID)).thenReturn(null);

        mockMvc.perform(get("/traveler/commission-method")
                .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());
    }

    // ── DELETE /traveler/commission-method ───────────────────────────────────────

    @Test
    void detachMethod_travelerRole_returns204() throws Exception {
        doNothing().when(cashCommissionService).detachCommissionMethod(USER_ID);

        mockMvc.perform(delete("/traveler/commission-method")
                .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());
    }

    // ── POST /bids/{bidId}/accept-with-commission ────────────────────────────────

    @Test
    void acceptCashBid_accepted_returns200() throws Exception {
        when(cashCommissionService.acceptCashBid(any(), any(), any()))
                .thenReturn(AcceptBidResponse.accepted());

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isOk());
    }

    @Test
    void acceptCashBid_requires3ds_returns202() throws Exception {
        when(cashCommissionService.acceptCashBid(any(), any(), any()))
                .thenReturn(AcceptBidResponse.requires3ds("pi_secret", "pi_id"));

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptCashBid_failed_returns422() throws Exception {
        when(cashCommissionService.acceptCashBid(any(), any(), any()))
                .thenReturn(AcceptBidResponse.failed("card_declined"));

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void acceptCashBid_insufficientWallet_returns409WithDetails() throws Exception {
        when(cashCommissionService.acceptCashBid(any(), any(), any()))
                .thenReturn(AcceptBidResponse.insufficientWallet(
                        new java.math.BigDecimal("3.00"), new java.math.BigDecimal("12.00"), true, "EUR"));

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.availableBalance").value(3.00))
                .andExpect(jsonPath("$.requiredCommission").value(12.00))
                .andExpect(jsonPath("$.hasCard").value(true));
    }

    @Test
    void acceptCashBid_userNotFoundForFirebaseUid_returns401() throws Exception {
        // uid authentifié mais aucun UserEntity correspondant → resolveUserId lève 401.
        var auth = new UsernamePasswordAuthenticationToken(
                "uid-unknown", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .with(authentication(auth)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptCashBid_commissionSourceCard_isPassedToService() throws Exception {
        when(cashCommissionService.acceptCashBid(any(), any(),
                org.mockito.ArgumentMatchers.eq(CommissionSource.CARD)))
                .thenReturn(AcceptBidResponse.accepted());

        mockMvc.perform(post("/bids/{bidId}/accept-with-commission", BID_ID)
                .param("commissionSource", "CARD")
                .with(authentication(asTraveler())))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(cashCommissionService).acceptCashBid(any(), any(),
                org.mockito.ArgumentMatchers.eq(CommissionSource.CARD));
    }

    // ── POST /bids/{bidId}/confirm-acceptance ────────────────────────────────────

    @Test
    void confirmAcceptance_accepted_returns200() throws Exception {
        when(cashCommissionService.confirmCommissionAcceptance(any(), any()))
                .thenReturn(ConfirmAcceptanceResponse.ok());

        mockMvc.perform(post("/bids/{bidId}/confirm-acceptance", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isOk());
    }

    @Test
    void confirmAcceptance_failed_returns422() throws Exception {
        when(cashCommissionService.confirmCommissionAcceptance(any(), any()))
                .thenReturn(ConfirmAcceptanceResponse.fail("payment_failed"));

        mockMvc.perform(post("/bids/{bidId}/confirm-acceptance", BID_ID)
                .with(authentication(asTraveler())))
                .andExpect(status().isUnprocessableEntity());
    }
}
