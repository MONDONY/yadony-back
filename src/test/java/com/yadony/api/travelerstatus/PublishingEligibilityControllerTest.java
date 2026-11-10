package com.yadony.api.travelerstatus;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.gppro.GpProApplicationEntity;
import com.yadony.api.gppro.GpProApplicationRepository;
import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublishingEligibilityControllerTest {

    private static final String UID = "uid-publishing-eligibility";
    private static final String URL = "/travelers/me/publishing-eligibility";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applicationRepository;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setUsername("pub-elig");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setKycStatus(KycStatus.VERIFIED);
        user = userRepository.save(user);
    }

    private RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(
                UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"))));
    }

    @Test
    void sansJeton_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void sansLimite_okEtLimiteNulle_formeDuContrat() throws Exception {
        String resetsOn = YearMonth.now().plusMonths(1).atDay(1).toString();

        mockMvc.perform(get(URL).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("OK"))
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.resetsOn").value(resetsOn))
                .andExpect(jsonPath("$.travelerStatus").doesNotExist());
    }

    @Test
    void limiteZeroTrajetUtilise_ok_avecLaLimite() throws Exception {
        user.setMonthlyTripLimit(2);
        userRepository.save(user);

        mockMvc.perform(get(URL).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("OK"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.used").value(0));
    }

    @Test
    void demandeGpProOuverte_etStatutDeclare_travelerStatusRenvoye() throws Exception {
        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        userRepository.save(user);
        GpProApplicationEntity app = new GpProApplicationEntity();
        app.setUserId(user.getId());
        app.setStatus(GpProApplicationStatus.PENDING);
        app.setSource(GpProApplicationSource.PROFILE);
        app.setTravelFrequency(GpProTravelFrequency.WEEKLY);
        applicationRepository.save(app);

        mockMvc.perform(get(URL).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travelerStatus").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.reason").value("OK"));
    }
}
