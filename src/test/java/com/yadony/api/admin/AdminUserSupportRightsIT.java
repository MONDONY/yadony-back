package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le support ne suspend, ne bannit et ne coupe plus la messagerie (permissions retirées pour la fiche
 * utilisateur) ; l'administrateur garde ces gestes. Pile de sécurité réelle, service métier simulé.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserSupportRightsIT {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean UserService userService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean FirebaseContactService firebaseContact;

    private static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static String body(String path) {
        return path.equals("mute-messaging")
                ? "{\"durationHours\":24,\"reason\":\"Messages abusifs\"}"
                : "{\"reason\":\"Fraude avérée\"}";
    }

    private static UserEntity user() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return u;
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "ban", "mute-messaging"})
    void support_isForbiddenOnSuspendBanAndMute(String path) throws Exception {
        mockMvc.perform(post("/admin/users/{id}/" + path, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body(path))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "ban", "mute-messaging"})
    void admin_isNotBlockedOnSuspendBanAndMute(String path) throws Exception {
        when(firebaseContact.getContact(any())).thenReturn(FirebaseContactService.Contact.EMPTY);
        when(userService.suspendUser(eq(USER_ID), anyString(), eq(ADMIN_ID))).thenReturn(user());
        when(userService.banUser(eq(USER_ID), anyString(), eq(ADMIN_ID))).thenReturn(user());
        when(userService.muteMessaging(eq(USER_ID), any(), anyString(), eq(ADMIN_ID))).thenReturn(user());

        mockMvc.perform(post("/admin/users/{id}/" + path, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body(path))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk());
    }
}
