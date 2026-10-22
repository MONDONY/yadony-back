package com.yadony.api.listings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.listings.dto.UserListingItem;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserListingsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean UserListingsService userListingsService;
    @MockBean UserRepository userRepository;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final String VIEWER_UID = "uid-viewer-listings";

    private static UsernamePasswordAuthenticationToken asRole(String role) {
        return new UsernamePasswordAuthenticationToken(
                VIEWER_UID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void openListings_returnsTheItemsOfTheService() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserEntity viewer = mock(UserEntity.class);
        when(viewer.getId()).thenReturn(viewerId);
        when(userRepository.findByFirebaseUid(VIEWER_UID)).thenReturn(Optional.of(viewer));
        when(userListingsService.openListings(OWNER_ID, viewerId)).thenReturn(List.of(
                new UserListingItem(UserListingItem.KIND_REQUEST, LocalDateTime.of(2026, 10, 21, 18, 0), null, null)));

        mockMvc.perform(get("/users/{userId}/open-listings", OWNER_ID)
                        .with(authentication(asRole("SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("REQUEST"))
                .andExpect(jsonPath("$[0].trip").doesNotExist());
        verify(userListingsService).openListings(OWNER_ID, viewerId);
    }

    @Test
    void openListings_unknownViewer_stillServesTheList() throws Exception {
        when(userRepository.findByFirebaseUid(VIEWER_UID)).thenReturn(Optional.empty());
        when(userListingsService.openListings(eq(OWNER_ID), any())).thenReturn(List.of());

        mockMvc.perform(get("/users/{userId}/open-listings", OWNER_ID)
                        .with(authentication(asRole("TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verify(userListingsService).openListings(OWNER_ID, null);
    }

    @Test
    void openListings_blockedOwner_returns404() throws Exception {
        when(userRepository.findByFirebaseUid(VIEWER_UID)).thenReturn(Optional.empty());
        when(userListingsService.openListings(eq(OWNER_ID), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"));

        mockMvc.perform(get("/users/{userId}/open-listings", OWNER_ID)
                        .with(authentication(asRole("SENDER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void openListings_withoutRole_isRefused() throws Exception {
        mockMvc.perform(get("/users/{userId}/open-listings", OWNER_ID)
                        .with(authentication(asRole("GUEST"))))
                .andExpect(status().isForbidden());
    }
}
