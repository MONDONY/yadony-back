package com.yadony.api.travelerstatus;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TravelerStatusControllerTest {

    private static final String UID = "uid-traveler-status-ctrl";
    private static final String URL = "/traveler-status/me";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @SpyBean UserRepository spyUsers;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        UserEntity user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setUsername("ts-ctrl");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setKycStatus(KycStatus.VERIFIED);
        userRepository.save(user);
        // H2 ne connaît pas « FOR NO KEY UPDATE » : on neutralise le SQL du verrou.
        doReturn(Optional.empty()).when(spyUsers).findByIdForUpdate(any());
    }

    private RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(
                UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"))));
    }

    private UserEntity me() {
        return userRepository.findByFirebaseUid(UID).orElseThrow();
    }

    private static String pro(String number) {
        return "{\"status\":\"PROFESSIONAL\",\"businessName\":\"Acme Express\",\"registrationNumber\":\""
                + number + "\",\"country\":\"FR\"}";
    }

    @Test
    void sansJeton_401() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INDIVIDUAL\",\"personalUseConfirmed\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void particulierConfirme_200() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INDIVIDUAL\",\"personalUseConfirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.declaredAt").exists())
                .andExpect(jsonPath("$.registrationNumber").doesNotExist());
        assertThat(me().getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
    }

    @Test
    void professionnelComplet_200_etNumeroChiffreRelisible() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(pro("SIREN 123 456 789")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.businessName").value("Acme Express"))
                .andExpect(jsonPath("$.registrationNumber").value("SIREN 123 456 789"))
                .andExpect(jsonPath("$.country").value("FR"));
        UserEntity u = me();
        assertThat(u.getBusinessRegistrationNumber()).isEqualTo("SIREN 123 456 789");
        assertThat(u.getBusinessCountry()).isEqualTo("FR");
    }

    @Test
    void numeroTropCourt_422_rienEcrit() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON).content(pro("AB1")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(me().getTravelerStatus()).isNull();
    }

    @Test
    void numeroAvecCaracteresInterdits_422_rienEcrit() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON).content(pro("AB12;DROP TABLE")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON).content(pro("AB12\\n3456")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(me().getTravelerStatus()).isNull();
        assertThat(me().getBusinessRegistrationNumber()).isNull();
    }

    @Test
    void particulierNonConfirme_422() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INDIVIDUAL\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("traveler-status-invalid"));
    }

    @Test
    void statutAbsent_422() throws Exception {
        mockMvc.perform(put(URL).with(auth()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
