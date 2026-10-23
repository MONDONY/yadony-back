package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Demandes d'envoi d'un utilisateur sur la fiche admin. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserRequestsIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PackageRequestRepository requestRepository;
    @Autowired NegotiationThreadRepository threadRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity user;
    private UserEntity other;

    @BeforeEach
    void setUp() {
        user = saveUser();
        other = saveUser();
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith("USER_VIEW", "BID_VIEW");
    }

    private PackageRequestEntity request(UserEntity sender, PackageRequestStatus st, LocalDate desired) {
        PackageRequestEntity r = new PackageRequestEntity();
        r.setSenderId(sender.getId());
        r.setDepartureCity("Paris");
        r.setArrivalCity("Dakar");
        r.setDesiredDate(desired);
        r.setWeightKg(new BigDecimal("3.50"));
        r.setParcelSize(ParcelSize.SMALL);
        r.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        r.setContentCategory("clothes");
        r.setStatus(st);
        return requestRepository.save(r);
    }

    private void thread(PackageRequestEntity r, NegotiationThreadStatus st) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(r.getId());
        t.setTravelerId(other.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(5));
        t.setTravelerAvailableKg(new BigDecimal("10"));
        t.setStatus(st);
        t.setCurrentPriceEur(new BigDecimal("20"));
        t.setRoundsCount((short) 0);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepository.save(t);
    }

    @Test
    void requests_listsSenderRequests() throws Exception {
        PackageRequestEntity mine = request(user, PackageRequestStatus.NEGOTIATING, LocalDate.of(2026, 11, 2));
        PackageRequestEntity noDate = request(user, PackageRequestStatus.OPEN, null);
        request(other, PackageRequestStatus.OPEN, null);
        jdbc.update("UPDATE package_requests SET created_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), mine.getId());
        thread(mine, NegotiationThreadStatus.OPEN);
        thread(mine, NegotiationThreadStatus.AWAITING_PAYMENT);
        thread(mine, NegotiationThreadStatus.REJECTED);

        mockMvc.perform(get("/admin/users/{id}/requests", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(noDate.getId().toString()))
                .andExpect(jsonPath("$.content[0].desiredDate").doesNotExist())
                .andExpect(jsonPath("$.content[0].openNegotiationCount").value(0))
                .andExpect(jsonPath("$.content[1].id").value(mine.getId().toString()))
                .andExpect(jsonPath("$.content[1].corridor").isNotEmpty())
                .andExpect(jsonPath("$.content[1].status").value("NEGOTIATING"))
                .andExpect(jsonPath("$.content[1].weightKg").value(3.5))
                .andExpect(jsonPath("$.content[1].desiredDate").value("2026-11-02T00:00:00Z"))
                .andExpect(jsonPath("$.content[1].openNegotiationCount").value(2));
    }

    @Test
    void requests_isPaginatedAndSizeCapped() throws Exception {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        PackageRequestEntity a = request(user, PackageRequestStatus.OPEN, null);
        PackageRequestEntity b = request(user, PackageRequestStatus.OPEN, null);
        PackageRequestEntity old = request(user, PackageRequestStatus.OPEN, null);
        jdbc.update("UPDATE package_requests SET created_at = ? WHERE id IN (?, ?)", t, a.getId(), b.getId());
        jdbc.update("UPDATE package_requests SET created_at = ? WHERE id = ?", t.minusDays(5), old.getId());
        // Ordre des identifiants tel que la base le voit : départage les deux demandes à égalité.
        var ids = jdbc.queryForList("SELECT id FROM package_requests WHERE id IN (?, ?) ORDER BY id DESC",
                UUID.class, a.getId(), b.getId());

        mockMvc.perform(get("/admin/users/{id}/requests?page=0&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(0).toString()))
                .andExpect(jsonPath("$.content[1].id").value(ids.get(1).toString()));
        mockMvc.perform(get("/admin/users/{id}/requests?page=1&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(old.getId().toString()));
        mockMvc.perform(get("/admin/users/{id}/requests?size=100000", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void requests_withoutBidView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/requests", user.getId())
                        .with(authentication(authWith("USER_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void requests_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/requests", UUID.randomUUID())
                        .with(authentication(fullAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    void requests_newUser_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/requests", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
