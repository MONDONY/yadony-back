package com.yadony.api.payments.mobilemoney;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
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
class MobileMoneyProvidersControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean MobileMoneyAccountService service;
    @MockitoBean UserRepository userRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String UID = "traveler-uid";

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(u));
    }

    private static UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static MobileMoneyProvidersResponse catalogue() {
        return new MobileMoneyProvidersResponse("SN", "XOF", "+221 •••• 67", "ORANGE_SEN",
                List.of(new MobileMoneyProvidersResponse.ProviderOption("ORANGE_SEN", "Orange Money", true),
                        new MobileMoneyProvidersResponse.ProviderOption("WAVE_SEN", "Wave", false)));
    }

    @Test
    void providers_withPhoneBody_passesRawPhoneToService() throws Exception {
        when(service.providers(USER_ID, "+221 77 123 45 67")).thenReturn(catalogue());
        mockMvc.perform(post("/payments/mobile-money/providers").with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+221 77 123 45 67\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detected").value("ORANGE_SEN"))
                .andExpect(jsonPath("$.providers[1].label").value("Wave"))
                .andExpect(jsonPath("$.msisdnMasked").value("+221 •••• 67"));
    }

    @Test
    void providers_withoutBody_passesNullToService() throws Exception {
        when(service.providers(USER_ID, null)).thenReturn(catalogue());
        mockMvc.perform(post("/payments/mobile-money/providers").with(authentication(traveler())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value("SN"));
    }

    @Test
    void anonymous_isRejected() throws Exception {
        mockMvc.perform(post("/payments/mobile-money/providers")).andExpect(status().is4xxClientError());
    }
}
