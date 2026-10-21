package com.yadony.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /auth/me} expose {@code gpPro} : l'onglet Moi en tire la couleur de la coche
 * d'identité (argent), comme les écrans qui la reçoivent déjà par {@code TravelerProfileDto}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthMeGpProIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @MockitoBean FirebaseContactService firebaseContact;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-me-gppro-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        u.setTotalTrips(0);
        user = userRepository.save(u);
        lenient().when(firebaseContact.getContact(anyString()))
                .thenReturn(new FirebaseContactService.Contact("+33612000077", null));
    }

    private UsernamePasswordAuthenticationToken self() {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void compteOrdinaire_gpProFaux() throws Exception {
        mockMvc.perform(get("/auth/me").with(authentication(self())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gpPro").value(false));
    }

    @Test
    void voyageurGpPro_gpProVrai() throws Exception {
        user.setGpPro(true);
        userRepository.save(user);

        mockMvc.perform(get("/auth/me").with(authentication(self())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gpPro").value(true));
    }
}
