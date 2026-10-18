package com.yadony.api.payments.mobilemoney;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig.Limits;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MobileMoneyCountriesControllerIT {

    private static final String FIREBASE_UID = "uid-test-mm-countries";

    @Autowired MockMvc mockMvc;
    @MockitoBean PawapayClient pawapayClient;

    @BeforeEach
    void setUp() {
        Map<String, PawapayProviderConfig> conf = new LinkedHashMap<>();
        conf.put("ORANGE_CIV", new PawapayProviderConfig("ORANGE_CIV", "CIV", "XOF",
                new Limits(new BigDecimal("100"), new BigDecimal("2000000"), "PROVIDER_AUTH", "OPERATIONAL"),
                new Limits(new BigDecimal("200"), new BigDecimal("300000"), "PROVIDER_AUTH", "OPERATIONAL")));
        conf.put("MTN_MOMO_CIV", new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF",
                new Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL"), null));
        when(pawapayClient.activeConfiguration()).thenReturn(conf);
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void countries_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/payments/mobile-money/countries").with(authentication(authAs(FIREBASE_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available[0].country").value("CI"))
                .andExpect(jsonPath("$.available[0].currency").value("XOF"))
                .andExpect(jsonPath("$.available[0].operators[0].code").value("ORANGE_CIV"))
                .andExpect(jsonPath("$.available[0].operators[0].validationHint").value("SMS_LINK"))
                .andExpect(jsonPath("$.available[0].operators[0].maxAmount").value(2000000))
                .andExpect(jsonPath("$.available[0].operators.length()").value(2))
                .andExpect(jsonPath("$.upcoming[0]").value("ML"));
    }

    @Test
    void countries_payoutOperation_usesPayoutLimits() throws Exception {
        mockMvc.perform(get("/payments/mobile-money/countries").param("operation", "payout")
                        .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available[0].operators.length()").value(1))
                .andExpect(jsonPath("$.available[0].operators[0].maxAmount").value(300000));
    }

    @Test
    void countries_unknownOperation_returns422() throws Exception {
        mockMvc.perform(get("/payments/mobile-money/countries").param("operation", "REFUND")
                        .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void countries_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/payments/mobile-money/countries")).andExpect(status().isUnauthorized());
    }
}
