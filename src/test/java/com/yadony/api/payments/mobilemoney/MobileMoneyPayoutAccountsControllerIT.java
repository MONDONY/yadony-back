package com.yadony.api.payments.mobilemoney;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.ProviderView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MobileMoneyPayoutAccountsControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean MobileMoneyPayoutAccountService service;
    @MockitoBean UserRepository userRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.fromString("0b6f6b3e-2f0a-4c43-9d36-0d5a3c1e9a11");
    private static final String UID = "traveler-uid";
    private static final String BASE = "/payments/mobile-money/accounts";

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(u));
    }

    private static UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static AccountView orangeCiv(String masked, int pending) {
        return new AccountView(ACCOUNT_ID, "ORANGE_CIV", "Orange Money", "ORANGE", "CI", "XOF", masked,
                Instant.parse("2026-10-19T08:30:00Z"), pending);
    }

    @Test
    void list_returnsTheAccountsAndTheSuggestedProviders() throws Exception {
        when(service.list(USER_ID)).thenReturn(new MobileMoneyPayoutAccountsResponse(
                List.of(orangeCiv("+225 •••• 67", 1)), List.of(new ProviderView("MTN_MOMO_CIV", "MTN MoMo"))));

        mockMvc.perform(get(BASE).with(authentication(traveler())))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "accounts": [{
                            "id": "0b6f6b3e-2f0a-4c43-9d36-0d5a3c1e9a11",
                            "provider": "ORANGE_CIV",
                            "providerLabel": "Orange Money",
                            "brand": "ORANGE",
                            "country": "CI",
                            "currency": "XOF",
                            "msisdnMasked": "+225 •••• 67",
                            "verifiedAt": "2026-10-19T08:30:00Z",
                            "pendingPayouts": 1
                          }],
                          "suggestedProviders": [{"code": "MTN_MOMO_CIV", "label": "MTN MoMo"}]
                        }
                        """, true));
    }

    @Test
    void post_creates_with201() throws Exception {
        when(service.add(USER_ID, "+225 07 01 23 45 67", "ORANGE_CIV")).thenReturn(orangeCiv("+225 •••• 67", 0));

        mockMvc.perform(post(BASE).with(authentication(traveler())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+225 07 01 23 45 67\",\"provider\":\"ORANGE_CIV\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id": "0b6f6b3e-2f0a-4c43-9d36-0d5a3c1e9a11", "provider": "ORANGE_CIV",
                         "providerLabel": "Orange Money", "brand": "ORANGE", "country": "CI", "currency": "XOF",
                         "msisdnMasked": "+225 •••• 67", "verifiedAt": "2026-10-19T08:30:00Z", "pendingPayouts": 0}
                        """, true));
    }

    @Test
    void putNumber_returns200() throws Exception {
        when(service.changeNumber(USER_ID, ACCOUNT_ID, "+225 07 09 99 99 99")).thenReturn(orangeCiv("+225 •••• 99", 0));

        mockMvc.perform(put(BASE + "/" + ACCOUNT_ID + "/number").with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+225 07 09 99 99 99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msisdnMasked").value("+225 •••• 99"))
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete(BASE + "/" + ACCOUNT_ID).with(authentication(traveler())))
                .andExpect(status().isNoContent());
        verify(service).remove(USER_ID, ACCOUNT_ID);
    }

    @Test
    void lockedAccount_is409ProblemWithPendingPayouts() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "mobile-money-account-locked", "Mobile Money Account Locked",
                "2 colis attendent encore leur versement sur ce compte.", Map.of("pendingPayouts", 2)))
                .when(service).remove(USER_ID, ACCOUNT_ID);

        mockMvc.perform(delete(BASE + "/" + ACCOUNT_ID).with(authentication(traveler())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("mobile-money-account-locked")))
                .andExpect(jsonPath("$.pendingPayouts").value(2))
                .andExpect(jsonPath("$.detail").value("2 colis attendent encore leur versement sur ce compte."));
    }

    @Test
    void anonymous_is401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+225 07 01 23 45 67\",\"provider\":\"ORANGE_CIV\"}"))
                .andExpect(status().isUnauthorized());
    }
}
