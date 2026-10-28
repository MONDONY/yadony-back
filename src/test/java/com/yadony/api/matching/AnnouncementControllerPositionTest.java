package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.tracking.SampleSource;
import com.yadony.api.tracking.TripPositionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Câblage HTTP de position, depart et mark-arrived (services mockés, comme AnnouncementArrivalControllerTest). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementControllerPositionTest {

    private static final String UID = "uid-traveler-position";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AnnouncementService announcementService;
    @MockitoBean private TripPositionService tripPositionService;

    private static UsernamePasswordAuthenticationToken as(String role) {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void positionRecordsOuvertureSample() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/announcements/" + id + "/position").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":48.85,\"lon\":2.35}"))
                .andExpect(status().isOk());

        verify(tripPositionService).recordForFirebaseUid(id, UID, 48.85, 2.35, SampleSource.OUVERTURE);
    }

    @Test
    void positionWithOutOfRangeCoordinatesIs422() throws Exception {
        UUID id = UUID.randomUUID();
        for (String body : List.of("{\"lat\":91,\"lon\":2}", "{\"lat\":10,\"lon\":-181}", "{\"lat\":10}")) {
            mockMvc.perform(post("/announcements/" + id + "/position").with(authentication(as("ROLE_TRAVELER")))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
        }
        verify(tripPositionService, never()).recordForFirebaseUid(any(), anyString(), any(), any(), any());
    }

    @Test
    void positionByNonOwnerIs403() throws Exception {
        UUID id = UUID.randomUUID();
        when(tripPositionService.recordForFirebaseUid(eq(id), anyString(), any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "non"));

        mockMvc.perform(post("/announcements/" + id + "/position").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":48.85,\"lon\":2.35}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void positionAndDepartRequireTravelerRole() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/announcements/" + id + "/position").with(authentication(as("ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":48.85,\"lon\":2.35}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/announcements/" + id + "/depart").with(authentication(as("ROLE_SENDER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/announcements/" + id + "/position")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":48.85,\"lon\":2.35}"))
                .andExpect(status().isUnauthorized());
        verify(tripPositionService, never()).recordForFirebaseUid(any(), anyString(), any(), any(), any());
        verify(tripPositionService, never()).depart(any(), anyString(), any(), any());
    }

    @Test
    void departWithoutBodyIs200() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/announcements/" + id + "/depart").with(authentication(as("ROLE_TRAVELER"))))
                .andExpect(status().isOk());

        verify(tripPositionService).depart(id, UID, null, null);
    }

    @Test
    void departWithPositionPassesCoordinates() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/announcements/" + id + "/depart").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":48.85,\"lon\":2.35}"))
                .andExpect(status().isOk());

        verify(tripPositionService).depart(id, UID, 48.85, 2.35);
    }

    @Test
    void markArrivedWithPositionStoresArriveeSample() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.markArrived(eq(id), eq(UID), eq("Sortie 3")))
                .thenReturn(mock(AnnouncementDetailResponse.class));

        mockMvc.perform(post("/announcements/" + id + "/mark-arrived").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalInstructions\":\"Sortie 3\",\"position\":{\"lat\":14.7,\"lon\":-17.4}}"))
                .andExpect(status().isOk());

        verify(tripPositionService).recordForFirebaseUid(id, UID, 14.7, -17.4, SampleSource.ARRIVEE);
    }

    @Test
    void markArrivedNeverFailsWhenPositionPartFails() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.markArrived(eq(id), eq(UID), isNull()))
                .thenReturn(mock(AnnouncementDetailResponse.class));
        when(tripPositionService.recordForFirebaseUid(any(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/announcements/" + id + "/mark-arrived").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":{\"lat\":14.7,\"lon\":-17.4}}"))
                .andExpect(status().isOk());
    }

    @Test
    void markArrivedWithoutPositionRecordsNothing() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.markArrived(eq(id), eq(UID), isNull()))
                .thenReturn(mock(AnnouncementDetailResponse.class));

        mockMvc.perform(post("/announcements/" + id + "/mark-arrived").with(authentication(as("ROLE_TRAVELER"))))
                .andExpect(status().isOk());

        verify(tripPositionService, never()).recordForFirebaseUid(any(), anyString(), any(), any(), any());
    }

    @Test
    void markArrivedWithInvalidPositionIs422() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/announcements/" + id + "/mark-arrived").with(authentication(as("ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":{\"lat\":200,\"lon\":0}}"))
                .andExpect(status().isUnprocessableEntity());
        verify(announcementService, never()).markArrived(any(), anyString(), any());
    }
}
