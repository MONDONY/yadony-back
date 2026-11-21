package com.yadony.api.likes;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.likes.dto.TripLikeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripLikeControllerTest {

    private static final String SENDER_UID = "uid-sender-like-test";
    private static final String TRAVELER_UID = "uid-traveler-like-test";
    private static final String GUEST_UID = "uid-invite-like-test";
    private static final String MISSING_UID = "uid-sans-ligne-like-test";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired MockMvc mockMvc;
    @MockBean TripLikeService tripLikeService;
    @MockBean UserRepository userRepository;

    @BeforeEach
    void setUp() {
        UserEntity sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", SENDER_ID);
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", TRAVELER_ID);
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(userRepository.findByFirebaseUid(MISSING_UID)).thenReturn(Optional.empty());
    }

    private static UsernamePasswordAuthenticationToken as(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void put_like_en_expediteur_rend_200_et_le_corps() throws Exception {
        when(tripLikeService.like(SENDER_ID, TRIP_ID)).thenReturn(new TripLikeResponse(1, true));

        mockMvc.perform(put("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(SENDER_UID, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"likeCount\":1,\"likedByMe\":true}", true));
    }

    @Test
    void put_like_en_voyageur_rend_200() throws Exception {
        when(tripLikeService.like(TRAVELER_ID, TRIP_ID)).thenReturn(new TripLikeResponse(2, true));

        mockMvc.perform(put("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(TRAVELER_UID, "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(2));
    }

    @Test
    void delete_like_rend_200_et_le_corps() throws Exception {
        when(tripLikeService.unlike(SENDER_ID, TRIP_ID)).thenReturn(new TripLikeResponse(0, false));

        mockMvc.perform(delete("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(SENDER_UID, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"likeCount\":0,\"likedByMe\":false}", true));
        verify(tripLikeService).unlike(SENDER_ID, TRIP_ID);
    }

    @Test
    void put_like_en_invite_rend_403() throws Exception {
        mockMvc.perform(put("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(GUEST_UID, "ROLE_GUEST"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tripLikeService);
    }

    @Test
    void delete_like_en_invite_rend_403() throws Exception {
        mockMvc.perform(delete("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(GUEST_UID, "ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_like_sans_jeton_rend_401() throws Exception {
        mockMvc.perform(put("/announcements/" + TRIP_ID + "/like"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_like_sans_jeton_rend_401() throws Exception {
        mockMvc.perform(delete("/announcements/" + TRIP_ID + "/like"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void put_like_compte_sans_ligne_users_rend_404() throws Exception {
        mockMvc.perform(put("/announcements/" + TRIP_ID + "/like")
                        .with(authentication(as(MISSING_UID, "ROLE_SENDER"))))
                .andExpect(status().isNotFound());
    }
}
