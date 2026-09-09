package com.yadony.api.payments.mobilemoney;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import java.time.Instant;
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
class MobileMoneyAccountControllerIT {

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

    private static MobileMoneyAccountResponse active() {
        return new MobileMoneyAccountResponse("ACTIVE", "+221 •••• 67", "ORANGE_SEN", "Orange Money", "SN", "XOF", Instant.now());
    }

    @Test
    void get_post_delete_routeToService() throws Exception {
        when(service.get(USER_ID)).thenReturn(active());
        when(service.activate(USER_ID, null)).thenReturn(active());
        when(service.disable(USER_ID)).thenReturn(new MobileMoneyAccountResponse("DISABLED", "+221 •••• 67", "ORANGE_SEN", "Orange Money", "SN", "XOF", null));

        mockMvc.perform(get("/payments/mobile-money/account").with(authentication(traveler())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.msisdnMasked").value("+221 •••• 67"))
                .andExpect(jsonPath("$.providerLabel").value("Orange Money"));
        // Sans corps : toujours accepté, le numéro Firebase reste la source (@RequestBody
        // required = false, comme MobileMoneyPaymentController#initiate).
        mockMvc.perform(post("/payments/mobile-money/account").with(authentication(traveler())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(delete("/payments/mobile-money/account").with(authentication(traveler())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    }

    /**
     * Corps facultatif avec un numéro : transmis tel quel (brut, non normalisé) au service,
     * qui décide seul de l'utiliser ou de l'ignorer selon que le compte Firebase a ou non un
     * téléphone — la normalisation elle-même est testée côté service, pas ici.
     */
    @Test
    void activate_withPhoneBody_passesRawPhoneToService() throws Exception {
        when(service.activate(USER_ID, "+221 77 345 67 89")).thenReturn(active());

        mockMvc.perform(post("/payments/mobile-money/account").with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+221 77 345 67 89\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anonymous_isRejected() throws Exception {
        mockMvc.perform(get("/payments/mobile-money/account")).andExpect(status().is4xxClientError());
    }
}
