package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.topupcode.dto.TopupCodeRedeemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletTopupCodeControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean WalletTopupCodeRedemptionService redemptionService;
    @MockitoBean UserRepository userRepository;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByFirebaseUid(anyString())).thenReturn(Optional.of(user));
    }

    private static UsernamePasswordAuthenticationToken sender() {
        return new UsernamePasswordAuthenticationToken("uid-topup-code", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void redeem_renvoieLeCredit() throws Exception {
        when(redemptionService.redeem(eq(USER_ID), eq("dakar 2000"), anyString())).thenReturn(new TopupCodeRedeemResponse(
                new BigDecimal("2000"), "XOF", new BigDecimal("3500"), UUID.randomUUID()));
        mockMvc.perform(post("/wallet/topup-codes/redeem").with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"dakar 2000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2000))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.balance").value(3500));
    }

    @Test
    void redeem_erreurMetier_problemDetailAvecCode() throws Exception {
        when(redemptionService.redeem(eq(USER_ID), anyString(), anyString())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-exhausted", "Unprocessable", "Épuisé"));
        mockMvc.perform(post("/wallet/topup-codes/redeem").with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"DAKAR2000\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("topup-code-exhausted"));
    }

    @Test
    void redeem_codeVide_422() throws Exception {
        mockMvc.perform(post("/wallet/topup-codes/redeem").with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(redemptionService);
    }

    @Test
    void redeem_sansAuthentification_refuse() throws Exception {
        mockMvc.perform(post("/wallet/topup-codes/redeem")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"DAKAR2000\"}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(redemptionService);
    }
}
