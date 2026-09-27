package com.yadony.api.tracking;

import com.yadony.api.tracking.dto.ConfirmDeliveryRequest;
import com.yadony.api.tracking.dto.QrScanRequest;
import com.yadony.api.tracking.dto.TrackingEventResponse;
import com.yadony.api.tracking.dto.TripScanHistoryEntryDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat JSON de la provenance d'une étape de suivi ({@code scanMethod} : {@code QR} ou
 * {@code MANUAL}, facultatif). Une provenance inconnue est omise du JSON (inclusion NON_NULL
 * globale), le client la lit comme absente. Service mocké : on vérifie la désérialisation de la requête,
 * la sérialisation de la réponse et le refus d'une valeur inconnue.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrackingControllerScanMethodTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TrackingService trackingService;

    private static UsernamePasswordAuthenticationToken user(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static TrackingEventResponse response(UUID bidId, String eventType, String scanMethod) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 10, 0);
        return new TrackingEventResponse(UUID.randomUUID(), bidId, eventType, now,
                null, null, null, null, null, now, scanMethod);
    }

    @Test
    void scan_withScanMethod_isPassedToServiceAndReturned() throws Exception {
        UUID bidId = UUID.randomUUID();
        when(trackingService.processScan(any(), anyString())).thenReturn(response(bidId, "TRANSIT", "MANUAL"));

        mockMvc.perform(post("/tracking/events")
                        .with(authentication(user("uid-traveler-scan-method", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bidId\":\"" + bidId + "\",\"eventType\":\"TRANSIT\",\"scanMethod\":\"MANUAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scanMethod").value("MANUAL"));

        ArgumentCaptor<QrScanRequest> captor = ArgumentCaptor.forClass(QrScanRequest.class);
        verify(trackingService).processScan(captor.capture(), eq("uid-traveler-scan-method"));
        assertThat(captor.getValue().scanMethod()).isEqualTo(ScanMethod.MANUAL);
    }

    @Test
    void scan_withoutScanMethod_staysCompatibleAndReturnsNull() throws Exception {
        UUID bidId = UUID.randomUUID();
        when(trackingService.processScan(any(), anyString())).thenReturn(response(bidId, "DEPART", null));

        mockMvc.perform(post("/tracking/events")
                        .with(authentication(user("uid-traveler-legacy", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bidId\":\"" + bidId + "\",\"eventType\":\"DEPART\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scanMethod").doesNotExist());

        ArgumentCaptor<QrScanRequest> captor = ArgumentCaptor.forClass(QrScanRequest.class);
        verify(trackingService).processScan(captor.capture(), anyString());
        assertThat(captor.getValue().scanMethod()).isNull();
    }

    @Test
    void scan_withUnknownScanMethod_returns400() throws Exception {
        mockMvc.perform(post("/tracking/events")
                        .with(authentication(user("uid-traveler-bad", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bidId\":\"" + UUID.randomUUID()
                                + "\",\"eventType\":\"TRANSIT\",\"scanMethod\":\"NFC\"}"))
                .andExpect(status().isBadRequest());

        verify(trackingService, never()).processScan(any(), anyString());
    }

    @Test
    void confirmDelivery_withScanMethod_isPassedToServiceAndReturned() throws Exception {
        UUID bidId = UUID.randomUUID();
        when(trackingService.confirmDelivery(eq(bidId), any(), anyString()))
                .thenReturn(response(bidId, "ARRIVEE", "QR"));

        mockMvc.perform(post("/tracking/" + bidId + "/confirm-delivery")
                        .with(authentication(user("uid-traveler-confirm", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationCode\":\"123456\",\"scanMethod\":\"QR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("ARRIVEE"))
                .andExpect(jsonPath("$.scanMethod").value("QR"));

        ArgumentCaptor<ConfirmDeliveryRequest> captor = ArgumentCaptor.forClass(ConfirmDeliveryRequest.class);
        verify(trackingService).confirmDelivery(eq(bidId), captor.capture(), anyString());
        assertThat(captor.getValue().scanMethod()).isEqualTo(ScanMethod.QR);
    }

    @Test
    void confirmDelivery_withoutScanMethod_passesNull() throws Exception {
        UUID bidId = UUID.randomUUID();
        when(trackingService.confirmDelivery(eq(bidId), any(), anyString()))
                .thenReturn(response(bidId, "ARRIVEE", null));

        mockMvc.perform(post("/tracking/" + bidId + "/confirm-delivery")
                        .with(authentication(user("uid-traveler-confirm-legacy", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationCode\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanMethod").doesNotExist());

        ArgumentCaptor<ConfirmDeliveryRequest> captor = ArgumentCaptor.forClass(ConfirmDeliveryRequest.class);
        verify(trackingService).confirmDelivery(eq(bidId), captor.capture(), anyString());
        assertThat(captor.getValue().scanMethod()).isNull();
    }

    @Test
    void getEvents_exposesScanMethod() throws Exception {
        UUID bidId = UUID.randomUUID();
        when(trackingService.getEvents(eq(bidId), anyString()))
                .thenReturn(List.of(response(bidId, "DEPART", "QR"), response(bidId, "TRANSIT", null)));

        mockMvc.perform(get("/tracking/" + bidId + "/events")
                        .with(authentication(user("uid-sender-events", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scanMethod").value("QR"))
                .andExpect(jsonPath("$[1].scanMethod").doesNotExist());
    }

    @Test
    void getTripScanHistory_exposesScanMethod() throws Exception {
        UUID announcementId = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.of(2026, 9, 28, 9, 30);
        when(trackingService.getTripScanHistory(eq(announcementId), anyString())).thenReturn(List.of(
                new TripScanHistoryEntryDto("DNY123456789", "Awa", "TRANSIT", at, "MANUAL"),
                new TripScanHistoryEntryDto("DNY987654321", "Moussa", "DEPART", at, null)));

        mockMvc.perform(get("/tracking/announcements/" + announcementId + "/events")
                        .with(authentication(user("uid-traveler-history", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].donNumber").value("DNY123456789"))
                .andExpect(jsonPath("$[0].scanMethod").value("MANUAL"))
                .andExpect(jsonPath("$[1].scanMethod").doesNotExist());
    }
}
