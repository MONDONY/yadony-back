package com.yadony.api.favorites;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.favorites.dto.FavoriteIdsResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FavoriteService favoriteService;

    @MockBean
    private UserRepository userRepository;

    private static final String SENDER_UID = "uid-sender-test";
    private static final String TRAVELER_UID = "uid-traveler-test";
    private static final String GUEST_UID = "uid-invite-test";
    private static final String MISSING_ROW_UID = "uid-sans-ligne-test";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Résolution de la ligne {@code users} pour SENDER_UID/TRAVELER_UID, comme le ferait
     * la vraie base : {@code FavoriteController} interroge désormais {@code UserRepository}
     * pour scoper la tolérance d'absence de ligne aux seuls invités (ronde de correction 1,
     * constat 4). {@code MISSING_ROW_UID} reste volontairement sans ligne : c'est le compte
     * inscrit "cassé" utilisé par les tests de non-régression.
     */
    @BeforeEach
    void setupUserRepository() {
        UserEntity sender = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(sender, "id", SENDER_ID);
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

        UserEntity traveler = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(traveler, "id", TRAVELER_ID);
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

        when(userRepository.findByFirebaseUid(MISSING_ROW_UID)).thenReturn(Optional.empty());
    }

    // ── Auth helpers (same pattern as PriceGridControllerTest) ────────────────

    private static UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                SENDER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                TRAVELER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static UsernamePasswordAuthenticationToken asGuest() {
        return new UsernamePasswordAuthenticationToken(
                GUEST_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    /** Un compte inscrit (rôle réel) dont la ligne {@code users} a disparu. */
    private static UsernamePasswordAuthenticationToken asSenderWithMissingRow() {
        return new UsernamePasswordAuthenticationToken(
                MISSING_ROW_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    // ── PUT /favorites/trip/{id} ──────────────────────────────────────────────

    @Test
    void putTrip_asSender_returns200_andCallsService() throws Exception {
        doNothing().when(favoriteService)
                .addFavorite(eq(SENDER_UID), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));

        mockMvc.perform(put("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(SENDER_UID, FavoriteTargetType.TRIP, TARGET_ID);
    }

    @Test
    void putPackageRequest_asSender_returns403() throws Exception {
        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void putPackageRequest_asTraveler_returns200() throws Exception {
        doNothing().when(favoriteService)
                .addFavorite(eq(TRAVELER_UID), eq(FavoriteTargetType.PACKAGE_REQUEST), eq(TARGET_ID));

        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(TRAVELER_UID, FavoriteTargetType.PACKAGE_REQUEST, TARGET_ID);
    }

    @Test
    void putUnknownType_returns400() throws Exception {
        mockMvc.perform(put("/favorites/bogus/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /favorites/trip/{id} ──────────────────────────────────────────

    @Test
    void deleteTrip_asTraveler_returns204() throws Exception {
        doNothing().when(favoriteService)
                .removeFavorite(eq(TRAVELER_ID), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));

        mockMvc.perform(delete("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());
    }

    // ── DELETE /favorites/package-request/{id} ───────────────────────────────

    @Test
    void deletePackageRequest_asTraveler_returns204() throws Exception {
        doNothing().when(favoriteService)
                .removeFavorite(eq(TRAVELER_ID), eq(FavoriteTargetType.PACKAGE_REQUEST), eq(TARGET_ID));

        mockMvc.perform(delete("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(TRAVELER_ID, FavoriteTargetType.PACKAGE_REQUEST, TARGET_ID);
    }

    // ── Ronde de correction 1, constat 4 : tolérance scopée aux invités ──────

    /**
     * Un invité (ROLE_GUEST) n'a jamais de ligne {@code users} avant son premier favori :
     * le contrôleur doit passer {@code null} au service (jamais provisionner sur un
     * chemin de suppression/lecture), et la requête doit tout de même réussir.
     */
    @Test
    void deleteTrip_asGuest_passesNullCallerId_returns204() throws Exception {
        doNothing().when(favoriteService)
                .removeFavorite(isNull(), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));

        mockMvc.perform(delete("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asGuest())))
                .andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(isNull(), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));
        verify(userRepository, never()).findByFirebaseUid(GUEST_UID);
    }

    /**
     * Un compte inscrit (rôle réel, pas invité) dont la ligne {@code users} a disparu ne
     * doit PAS bénéficier de la tolérance réservée aux invités : 404, comme avant
     * l'ouverture aux invités (non-régression du constat 4).
     */
    @Test
    void deleteTrip_registeredUserWithMissingRow_returns404_neverReachesService() throws Exception {
        mockMvc.perform(delete("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asSenderWithMissingRow())))
                .andExpect(status().isNotFound());

        verify(favoriteService, never()).removeFavorite(any(), any(), any());
    }

    // ── RFC 7807 ProblemDetail assertions ────────────────────────────────────

    @Test
    void putUnknownType_returns400_withProblemDetail() throws Exception {
        mockMvc.perform(put("/favorites/bogus/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void putPackageRequest_asSender_returns403_withProblemDetail() throws Exception {
        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists());
    }

    // ── GET /favorites/trips ──────────────────────────────────────────────────

    @Test
    void getTrips_asSender_returns200WithList() throws Exception {
        // Use an empty list — the controller just passes through whatever service returns
        when(favoriteService.getFavoriteTrips(SENDER_ID))
                .thenReturn(List.of());

        mockMvc.perform(get("/favorites/trips")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTrips_asGuest_passesNullCallerId_returns200() throws Exception {
        when(favoriteService.getFavoriteTrips(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/favorites/trips")
                        .with(authentication(asGuest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(userRepository, never()).findByFirebaseUid(GUEST_UID);
    }

    @Test
    void getTrips_registeredUserWithMissingRow_returns404_neverReachesService() throws Exception {
        mockMvc.perform(get("/favorites/trips")
                        .with(authentication(asSenderWithMissingRow())))
                .andExpect(status().isNotFound());

        verify(favoriteService, never()).getFavoriteTrips(any());
    }

    // ── GET /favorites/package-requests ─────────────────────────────────────

    @Test
    void getPackageRequests_asSender_returns403() throws Exception {
        mockMvc.perform(get("/favorites/package-requests")
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPackageRequests_asTraveler_returns200() throws Exception {
        when(favoriteService.getFavoritePackageRequests(TRAVELER_ID))
                .thenReturn(List.of());

        mockMvc.perform(get("/favorites/package-requests")
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getPackageRequests_asGuest_passesNullCallerId_returns200() throws Exception {
        when(favoriteService.getFavoritePackageRequests(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/favorites/package-requests")
                        .with(authentication(asGuest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── GET /favorites/ids ────────────────────────────────────────────────────

    @Test
    void getIds_authenticated_returns200() throws Exception {
        when(favoriteService.getFavoriteIds(SENDER_ID))
                .thenReturn(new FavoriteIdsResponse(Set.of(), Set.of()));

        mockMvc.perform(get("/favorites/ids")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips").isArray())
                .andExpect(jsonPath("$.packageRequests").isArray());
    }

    @Test
    void getIds_asGuest_passesNullCallerId_returns200() throws Exception {
        when(favoriteService.getFavoriteIds(isNull()))
                .thenReturn(new FavoriteIdsResponse(Set.of(), Set.of()));

        mockMvc.perform(get("/favorites/ids")
                        .with(authentication(asGuest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips").isArray())
                .andExpect(jsonPath("$.packageRequests").isArray());

        verify(userRepository, never()).findByFirebaseUid(GUEST_UID);
    }

    @Test
    void getIds_registeredUserWithMissingRow_returns404_neverReachesService() throws Exception {
        mockMvc.perform(get("/favorites/ids")
                        .with(authentication(asSenderWithMissingRow())))
                .andExpect(status().isNotFound());

        verify(favoriteService, never()).getFavoriteIds(any());
    }

    // ── Unauthenticated requests ──────────────────────────────────────────────

    @Test
    void putTrip_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/favorites/trip/" + TARGET_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTrips_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/favorites/trips"))
                .andExpect(status().isUnauthorized());
    }
}
