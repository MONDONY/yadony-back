package com.yadony.api.matching;

import com.yadony.api.matching.dto.PriceSuggestionResponse;
import com.yadony.api.matching.dto.PriceSuggestionResponse.ItemPriceSuggestion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PriceSuggestionControllerIT {

    private static final String URL = "/announcements/price-suggestions";

    @Autowired private MockMvc mockMvc;
    @MockBean private PriceSuggestionService service;

    private static UsernamePasswordAuthenticationToken authAs(String role) {
        return new UsernamePasswordAuthenticationToken("uid-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void returnsMediansForTheRoute() throws Exception {
        when(service.suggest("Paris", "Abidjan", "EUR")).thenReturn(new PriceSuggestionResponse(
                "EUR", new BigDecimal("8"), 12,
                List.of(new ItemPriceSuggestion("Téléphone", new BigDecimal("15"), 5))));

        mockMvc.perform(get(URL).param("departureCity", "Paris").param("arrivalCity", "Abidjan")
                        .with(authentication(authAs("SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.pricePerKg").value(8))
                .andExpect(jsonPath("$.sampleSize").value(12))
                .andExpect(jsonPath("$.items[0].label").value("Téléphone"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(15))
                .andExpect(jsonPath("$.items[0].sampleSize").value(5));
    }

    @Test
    void passesTheRequestedCurrency() throws Exception {
        when(service.suggest("Paris", "Dakar", "XOF"))
                .thenReturn(new PriceSuggestionResponse("XOF", null, 1, List.of()));

        mockMvc.perform(get(URL).param("departureCity", "Paris").param("arrivalCity", "Dakar")
                        .param("currency", "XOF").with(authentication(authAs("TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerKg").doesNotExist());
    }

    @Test
    void withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get(URL).param("departureCity", "Paris").param("arrivalCity", "Abidjan"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).suggest(any(), any(), any());
    }

    @Test
    void guest_isForbidden() throws Exception {
        mockMvc.perform(get(URL).param("departureCity", "Paris").param("arrivalCity", "Abidjan")
                        .with(authentication(authAs("GUEST"))))
                .andExpect(status().isForbidden());
        verify(service, never()).suggest(any(), any(), any());
    }

    @Test
    void blankCity_isRejected() throws Exception {
        mockMvc.perform(get(URL).param("departureCity", " ").param("arrivalCity", "Abidjan")
                        .with(authentication(authAs("SENDER"))))
                .andExpect(status().is4xxClientError());
        verify(service, never()).suggest(any(), any(), any());
    }

    @Test
    void invalidCurrency_isRejected() throws Exception {
        mockMvc.perform(get(URL).param("departureCity", "Paris").param("arrivalCity", "Abidjan")
                        .param("currency", "EURO").with(authentication(authAs("SENDER"))))
                .andExpect(status().is4xxClientError());
        verify(service, never()).suggest(any(), any(), any());
    }
}
