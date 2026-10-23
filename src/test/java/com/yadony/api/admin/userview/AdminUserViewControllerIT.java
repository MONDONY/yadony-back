package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Squelette de la synthèse par utilisateur : 404, droits, compte neuf, gating par domaine. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserViewControllerIT {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean UserRepository userRepository;

    private static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return token(role, all);
    }

    private static UsernamePasswordAuthenticationToken authWith(AdminRole role, String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        return token(role, all);
    }

    private static UsernamePasswordAuthenticationToken token(AdminRole role,
                                                             java.util.List<SimpleGrantedAuthority> all) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UserEntity newUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return u;
    }

    @Test
    void overview_unknownUser_returns404UserNotFound() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    void overview_softDeletedUser_returns404() throws Exception {
        // @Where(deleted_at IS NULL) : un compte supprimé n'est pas retrouvé par findById.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    void overview_newUser_returnsZeroCountsAndNullRating() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser()));

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.ratingCount").value(0))
                .andExpect(jsonPath("$.kpis.averageRating").doesNotExist())
                .andExpect(jsonPath("$.kpis.parcelsInProgress").value(0))
                .andExpect(jsonPath("$.kpis.activeTrips").value(0))
                .andExpect(jsonPath("$.kpis.openDisputes").value(0))
                .andExpect(jsonPath("$.kpis.totalDisputes").value(0))
                .andExpect(jsonPath("$.kpis.reportsReceived").value(0))
                .andExpect(jsonPath("$.kpis.reportsSent").value(0))
                .andExpect(jsonPath("$.kpis.reportsPending").value(0))
                .andExpect(jsonPath("$.kpis.heldByCurrency").isEmpty())
                .andExpect(jsonPath("$.inProgress").isEmpty())
                .andExpect(jsonPath("$.timeline").isEmpty());
    }

    @Test
    void overview_withRating_returnsAverageAndCount() throws Exception {
        UserEntity u = newUser();
        u.setAverageRating(new BigDecimal("4.50"));
        u.setRatingCount(12);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.averageRating").value(4.5))
                .andExpect(jsonPath("$.kpis.ratingCount").value(12));
    }

    @Test
    void overview_withoutUserView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(authWith(AdminRole.SUPPORT, "BID_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_supportRole_omitsNothingItAlreadyMayRead() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser()));

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.parcelsInProgress").value(0))
                .andExpect(jsonPath("$.kpis.activeTrips").value(0))
                .andExpect(jsonPath("$.kpis.heldByCurrency").isEmpty())
                .andExpect(jsonPath("$.kpis.openDisputes").value(0))
                .andExpect(jsonPath("$.kpis.reportsReceived").value(0));
    }

    @Test
    void overview_onlyUserView_nullsEveryDomainField() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser()));

        mockMvc.perform(get("/admin/users/{id}/overview", USER_ID)
                        .with(authentication(authWith(AdminRole.SUPPORT, "USER_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.parcelsInProgress").doesNotExist())
                .andExpect(jsonPath("$.kpis.activeTrips").doesNotExist())
                .andExpect(jsonPath("$.kpis.heldByCurrency").doesNotExist())
                .andExpect(jsonPath("$.kpis.openDisputes").doesNotExist())
                .andExpect(jsonPath("$.kpis.totalDisputes").doesNotExist())
                .andExpect(jsonPath("$.kpis.reportsReceived").doesNotExist())
                .andExpect(jsonPath("$.kpis.reportsSent").doesNotExist())
                .andExpect(jsonPath("$.kpis.reportsPending").doesNotExist())
                .andExpect(jsonPath("$.kpis.ratingCount").value(0));
    }
}
