package com.yadony.api.cancellation.fee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /config/cancellation-fee} sans authentification, frais ALLUMÉS par le profil de test des frais. */
@SpringBootTest
@ActiveProfiles({"test", "cancellation-fee"})
@AutoConfigureMockMvc
class CancellationFeeConfigOnIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired CancellationFeeProperties properties;

    @Test
    void activeWithEffectiveFrom_tiersMatchConfiguration() throws Exception {
        var result = mockMvc.perform(get("/config/cancellation-fee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.effectiveFrom").value("2020-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.capEur").value(30))
                .andExpect(jsonPath("$.tiers", hasSize(properties.tiers().size())))
                .andExpect(jsonPath("$.length()").value(4));
        assertThat(result.andReturn().getResponse().getContentType()).contains("application/json");

        for (int i = 0; i < properties.tiers().size(); i++) {
            var tier = properties.tiers().get(i);
            mockMvc.perform(get("/config/cancellation-fee"))
                    .andExpect(jsonPath("$.tiers[" + i + "].minDaysBefore").value(tier.minDaysBefore()))
                    .andExpect(jsonPath("$.tiers[" + i + "].rate").value(tier.rate().doubleValue()));
        }
    }
}
