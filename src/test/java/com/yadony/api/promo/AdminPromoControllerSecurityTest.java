package com.yadony.api.promo;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RBAC et erreurs RFC 7807 de {@code GET /admin/promo-codes} (MockMvc, vraie chaîne de sécurité). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminPromoController — sécurité et filtre origin")
class AdminPromoControllerSecurityTest {

    @Autowired
    MockMvc mockMvc;

    private static UsernamePasswordAuthenticationToken auth(AdminRole role, String... authorities) {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), role.name().toLowerCase() + "@yadony.test", role, false, "uid-promo-" + role);
        List<SimpleGrantedAuthority> granted = new java.util.ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String a : authorities) {
            granted.add(new SimpleGrantedAuthority(a));
        }
        return new UsernamePasswordAuthenticationToken(principal, null, granted);
    }

    @Test
    @DisplayName("origin=BOGUS → 400 application/problem+json (RFC 7807)")
    void list_unknownOrigin_returns400Problem() throws Exception {
        mockMvc.perform(get("/admin/promo-codes").param("origin", "BOGUS")
                        .with(authentication(auth(AdminRole.ADMIN, "PROMO_MANAGE"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.parameter").value("origin"));
    }

    @Test
    @DisplayName("ADMIN sans PROMO_MANAGE → 403")
    void list_withoutPromoManage_returns403() throws Exception {
        mockMvc.perform(get("/admin/promo-codes")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
    }
}
