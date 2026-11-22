package com.yadony.api.cancellation.fee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /config/cancellation-fee} sans authentification, frais ÉTEINTS (état par défaut, comme en production). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CancellationFeeConfigOffIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void publicAndInactiveByDefault_shapeLocked() throws Exception {
        mockMvc.perform(get("/config/cancellation-fee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                // Présent mais nul : la forme ne varie pas selon l'état.
                .andExpect(content().string(containsString("\"effectiveFrom\":null")))
                .andExpect(jsonPath("$.effectiveFrom").value(nullValue()))
                .andExpect(jsonPath("$.capEur").value(30))
                .andExpect(jsonPath("$.tiers", hasSize(4)))
                .andExpect(jsonPath("$.tiers[0].minDaysBefore").value(5))
                .andExpect(jsonPath("$.tiers[0].rate").value(0))
                .andExpect(jsonPath("$.tiers[1].minDaysBefore").value(3))
                .andExpect(jsonPath("$.tiers[1].rate").value(0.10))
                .andExpect(jsonPath("$.tiers[2].minDaysBefore").value(1))
                .andExpect(jsonPath("$.tiers[2].rate").value(0.20))
                .andExpect(jsonPath("$.tiers[3].minDaysBefore").value(0))
                .andExpect(jsonPath("$.tiers[3].rate").value(0.30))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.tiers[0].length()").value(2));
    }
}
