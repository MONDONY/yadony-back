package com.yadony.api.calls;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminCallsControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired CallRepository callRepository;
    @Autowired UserRepository userRepository;

    @Test
    void lAdminLitLHistoriqueDuPlusRecentAuPlusAncien() throws Exception {
        UUID bidId = UUID.randomUUID();
        UserEntity a = persistUser(Role.SENDER);
        UserEntity b = persistUser(Role.TRAVELER);
        CallEntity first = new CallEntity(UUID.randomUUID(), bidId, a.getId(), b.getId(), UUID.randomUUID().toString());
        first.finish(CallStatus.MISSED, OffsetDateTime.now(ZoneOffset.UTC));
        callRepository.saveAndFlush(first);
        Thread.sleep(5);
        CallEntity second = callRepository.saveAndFlush(
                new CallEntity(UUID.randomUUID(), bidId, b.getId(), a.getId(), UUID.randomUUID().toString()));
        callRepository.saveAndFlush(new CallEntity(UUID.randomUUID(), UUID.randomUUID(), a.getId(), b.getId(),
                UUID.randomUUID().toString()));

        mockMvc.perform(get("/admin/bids/{bidId}/calls", bidId).with(authentication(as(persistUser(Role.ADMIN), "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("RINGING"))
                .andExpect(jsonPath("$[1].status").value("MISSED"))
                .andExpect(jsonPath("$[1].callerId").value(a.getId().toString()));
    }

    @Test
    void unUtilisateurNonAdminEstRefuse() throws Exception {
        mockMvc.perform(get("/admin/bids/{bidId}/calls", UUID.randomUUID())
                        .with(authentication(as(persistUser(Role.SENDER), "ROLE_SENDER"))))
                .andExpect(status().isForbidden());
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user, String role) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null, List.of(new SimpleGrantedAuthority(role)));
    }

    private UserEntity persistUser(Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-admin-calls-" + UUID.randomUUID());
        u.setFirstName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
