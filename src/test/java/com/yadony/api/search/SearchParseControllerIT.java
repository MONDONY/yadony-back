package com.yadony.api.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.search.dto.ParsedFilters;
import com.yadony.api.search.dto.SearchParseRequest;
import com.yadony.api.search.dto.SearchParseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SearchParseControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private SearchQueryParser parser;

    private static UsernamePasswordAuthenticationToken sender() {
        return new UsernamePasswordAuthenticationToken(
            "uid-test-sender", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static SearchParseResponse sample() {
        return new SearchParseResponse(
            new ParsedFilters(null, "Bamako",
                LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31),
                new BigDecimal("20"), null, null, null,
                null, null, null, null, null, null),
            List.of(), List.of(), List.of());
    }

    @Test
    void parse_withValidSentence_returns200AndFilters() throws Exception {
        when(parser.parse(anyString(), any(), any())).thenReturn(sample());

        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest("20 kilos à Bamako en mars", SearchMode.TRIPS, null)))
                .with(authentication(sender())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.arrivalCity").value("Bamako"))
            .andExpect(jsonPath("$.filters.minAvailableKg").value(20))
            .andExpect(jsonPath("$.filters.departureDateFrom").value("2027-03-01"));
    }

    @Test
    void parse_omitsNullFiltersFromTheJson() throws Exception {
        when(parser.parse(anyString(), any(), any())).thenReturn(sample());

        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest("à Bamako", SearchMode.TRIPS, null)))
                .with(authentication(sender())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.departureCity").doesNotExist())
            .andExpect(jsonPath("$.filters.urgent").doesNotExist());
    }

    @Test
    void parse_withBlankText_returns422() throws Exception {
        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest("   ", SearchMode.TRIPS, null)))
                .with(authentication(sender())))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void parse_withOverlongText_returns422() throws Exception {
        String tooLong = "a".repeat(201);

        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest(tooLong, SearchMode.TRIPS, null)))
                .with(authentication(sender())))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void parse_withExplicitToday_forwardsItToTheParserInsteadOfServerDate() throws Exception {
        when(parser.parse(anyString(), any(), any())).thenReturn(sample());
        LocalDate given = LocalDate.of(2026, 8, 19);

        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest("à Bamako", SearchMode.TRIPS, given)))
                .with(authentication(sender())))
            .andExpect(status().isOk());

        verify(parser).parse("à Bamako", SearchMode.TRIPS, given);
    }

    @Test
    void parse_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/search/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new SearchParseRequest("à Bamako", SearchMode.TRIPS, null))))
            .andExpect(status().isUnauthorized());
    }
}
