package com.yadony.api.gppro;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.KycStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GpProControllerIT {

    private static final String UID = "uid-gppro-ctrl";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applicationRepository;
    @Autowired GpProDocumentRepository documentRepository;
    @SpyBean UserRepository spyUsers;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        UserEntity user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setUsername("gppro-ctrl");
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

    private static final String BODY = "{\"travelFrequency\":\"MONTHLY\",\"source\":\"PROFILE\"}";

    @Test
    void applyThenGetMeReturnsPending() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"));
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.travelFrequency").value("MONTHLY"));
    }

    @Test
    void deleteCurrentReturns204ThenGetMeReturnsNone() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/gp-pro/applications/current").with(auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/gp-pro/me").with(auth()))
                .andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void applyWithMissingFrequencyIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/gp-pro/applications").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"PROFILE\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
