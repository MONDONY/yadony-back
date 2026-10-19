package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.dto.DashboardLinkResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectDashboardControllerIT {

    @Autowired MockMvc mockMvc;
    @MockBean ConnectDashboardLinkService service;

    private static UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken("uid-dashboard", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void returnsTheOneTimeUrl_neverCached() throws Exception {
        when(service.createDashboardLink("uid-dashboard"))
                .thenReturn(new DashboardLinkResponse("https://connect.stripe.com/express/xyz"));

        mockMvc.perform(post("/payments/connect/dashboard-link").with(authentication(traveler())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").value("https://connect.stripe.com/express/xyz"));
    }

    @Test
    void noConnectAccount_is404Problem() throws Exception {
        when(service.createDashboardLink("uid-dashboard")).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "stripe-account-missing", "Stripe Account Missing", "Aucun compte"));

        mockMvc.perform(post("/payments/connect/dashboard-link").with(authentication(traveler())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("stripe-account-missing"));
    }

    @Test
    void onboardingIncomplete_is409Problem() throws Exception {
        when(service.createDashboardLink("uid-dashboard")).thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "stripe-onboarding-incomplete", "Stripe Onboarding Incomplete", "Terminez"));

        mockMvc.perform(post("/payments/connect/dashboard-link").with(authentication(traveler())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe-onboarding-incomplete"));
    }

    @Test
    void anonymous_is401() throws Exception {
        mockMvc.perform(post("/payments/connect/dashboard-link"))
                .andExpect(status().isUnauthorized());
    }
}
