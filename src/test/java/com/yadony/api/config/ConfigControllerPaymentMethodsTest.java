package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.config.dto.PaymentMethodsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /config/payment-methods} est lu par l'application à chaque ouverture de la feuille de
 * paiement, avant ou sans authentification : route publique, trois booléens, tous faux par défaut
 * (YADONY-FLUTTER-PROD-11).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerPaymentMethodsTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private FirebaseAuth firebaseAuth;

    @Test
    void publique_etToutEteintParDefaut() throws Exception {
        mockMvc.perform(get("/config/payment-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applePayEnabled").value(false))
                .andExpect(jsonPath("$.googlePayEnabled").value(false))
                .andExpect(jsonPath("$.paypalEnabled").value(false));
    }

    @Test
    void refleteLesReglagesPlateforme() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.applePayEnabled()).thenReturn(true);
        when(settings.googlePayEnabled()).thenReturn(false);
        when(settings.paypalEnabled()).thenReturn(true);
        ConfigController controller = new ConfigController(settings, mock(CommissionGridService.class));

        assertThat(controller.getPaymentMethods().getBody())
                .isEqualTo(new PaymentMethodsResponse(true, false, true));
    }
}
