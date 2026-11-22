package com.yadony.api.admin;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.PhoneLookupService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.admin.account.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recherche admin par numéro sur H2 (profil test) : la requête native doit rester portable entre
 * H2 et PostgreSQL (le pendant PostgreSQL est {@code UserRepositoryAdminSearchIT}).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserPhoneSearchH2IT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean PhoneLookupService phoneLookup;

    private UserEntity first;
    private UserEntity second;
    private UserEntity other;

    @BeforeEach
    void setUp() {
        first = userRepository.save(newUser("uid-h2-a-"));
        second = userRepository.save(newUser("uid-h2-b-"));
        other = userRepository.save(newUser("uid-h2-c-"));
        lenient().when(firebaseContact.getContacts(any())).thenReturn(Map.of());
    }

    private static UserEntity newUser(String uidPrefix) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uidPrefix + UUID.randomUUID());
        u.setFirstName("Phone");
        u.setLastName("Search" + UUID.randomUUID().toString().substring(0, 8));
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(Set.of(Role.SENDER));
        u.setTotalTrips(0);
        return u;
    }

    @Test
    void phoneQuery_severalAccounts_listsEachOfThem() throws Exception {
        when(phoneLookup.findAllByPhone("+224621234567")).thenReturn(List.of(first, second));

        mockMvc.perform(get("/admin/users").param("query", "+224621234567")
                        .with(authentication(AdminRecetteBulkIT.admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void phoneQuery_singleAccount_listsIt() throws Exception {
        when(phoneLookup.findAllByPhone("+224621234568")).thenReturn(List.of(other));

        mockMvc.perform(get("/admin/users").param("query", "+224621234568")
                        .with(authentication(AdminRecetteBulkIT.admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(other.getId().toString()));
    }

    @Test
    void phoneQuery_unknownNumber_listsNothing() throws Exception {
        when(phoneLookup.findAllByPhone("+224620000000")).thenReturn(List.of());

        mockMvc.perform(get("/admin/users").param("query", "+224620000000")
                        .with(authentication(AdminRecetteBulkIT.admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
