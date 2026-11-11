package com.yadony.api.city;

import com.yadony.api.city.dto.CitySearchResponse;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CityService cityService;

    private static UsernamePasswordAuthenticationToken authenticatedAsSender() {
        return new UsernamePasswordAuthenticationToken(
            "uid-test-sender", null,
            List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken authenticatedAsTraveler() {
        return new UsernamePasswordAuthenticationToken(
            "uid-test-traveler", null,
            List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void search_withValidQuery_returns200() throws Exception {
        when(cityService.search(eq("Dak"), anyInt()))
            .thenReturn(List.of(
                new CitySearchResponse("Dakar", "SN", "Sénégal", 14.71, -17.47)
            ));

        mockMvc.perform(get("/cities/search")
                .param("q", "Dak")
                .with(authentication(authenticatedAsSender())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Dakar"))
            .andExpect(jsonPath("$[0].countryCode").value("SN"));
    }

    @Test
    void search_withOneCharQuery_returns422() throws Exception {
        mockMvc.perform(get("/cities/search")
                .param("q", "D")
                .with(authentication(authenticatedAsTraveler())))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/cities/search").param("q", "Dakar"))
            .andExpect(status().isUnauthorized());
    }

    private static UsernamePasswordAuthenticationToken authenticatedAsGuest() {
        return new UsernamePasswordAuthenticationToken(
            "uid-test-guest", null,
            List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    @Test
    void search_asGuest_returns200WithSuggestions() throws Exception {
        when(cityService.search(eq("Dak"), anyInt()))
            .thenReturn(List.of(
                new CitySearchResponse("Dakar", "SN", "Sénégal", 14.71, -17.47)
            ));

        mockMvc.perform(get("/cities/search")
                .param("q", "Dak")
                .with(authentication(authenticatedAsGuest())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Dakar"));
    }

    @Test
    void search_asGuestWithOneCharQuery_returns422() throws Exception {
        mockMvc.perform(get("/cities/search")
                .param("q", "D")
                .with(authentication(authenticatedAsGuest())))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void popularCorridors_asGuest_staysForbidden() throws Exception {
        mockMvc.perform(get("/cities/corridors/popular")
                .with(authentication(authenticatedAsGuest())))
            .andExpect(status().isForbidden());
    }
}
