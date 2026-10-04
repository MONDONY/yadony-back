package com.yadony.api.common.metrics;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PrometheusEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private FirebaseAuth firebaseAuth;

    @Test
    void prometheusEndpoint_isReachableForInternalScrape() throws Exception {
        // /actuator/prometheus is permitAll so the internal monitoring stack
        // (Prometheus/Alloy on the private Docker network) can scrape it without a
        // Firebase token. Public exposure is prevented at the network level:
        // port 8080 is never published to the host and Nginx 404s /api/v1/actuator/*.
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("# HELP")));
    }

    @Test
    void latenceHttp_exposeDesBucketsPourLesPercentilesGrafana() throws Exception {
        // Les dashboards Grafana calculent p95/p99 par histogram_quantile sur
        // http_server_requests_seconds_bucket : sans bornes configurées, ces panneaux
        // restaient vides.
        mockMvc.perform(get("/actuator/health"));

        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "http_server_requests_seconds_bucket")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("le=\"0.1\"")));
    }
}
