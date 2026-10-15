package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeEntity;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeRedemptionRepository;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminTopupCodeControllerIT — /admin/topup-codes")
class AdminTopupCodeControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WalletTopupCodeRepository codeRepository;
    @Autowired WalletTopupCodeRedemptionRepository redemptionRepository;

    @BeforeEach
    void setUp() {
        redemptionRepository.deleteAll();
        codeRepository.deleteAll();
    }

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-topup");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private String body(Object o) throws Exception { return objectMapper.writeValueAsString(o); }

    @Test
    void creerPuisLister_puisRedemptions() throws Exception {
        String created = mockMvc.perform(post("/admin/topup-codes").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("code", "dakar2000", "amount", 10, "currency", "EUR",
                                "maxRedemptions", 100, "note", "Lancement"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("DAKAR2000"))
                .andExpect(jsonPath("$.remaining").value(100))
                .andExpect(jsonPath("$.displayStatus").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/admin/topup-codes").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("DAKAR2000"));

        // La révocation (findByIdForUpdate = FOR NO KEY UPDATE) n'est pas exécutable sous H2 :
        // elle est couverte par AdminTopupCodeControllerTest.

        mockMvc.perform(get("/admin/topup-codes/{id}/redemptions", id).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void doublon_409() throws Exception {
        WalletTopupCodeEntity existing = new WalletTopupCodeEntity();
        existing.setCode("DAKAR2000");
        existing.setAmount(BigDecimal.TEN);
        existing.setCurrency("EUR");
        existing.setMaxRedemptions(1);
        existing.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        existing.setCreatedBy(ADMIN_ID);
        codeRepository.save(existing);

        mockMvc.perform(post("/admin/topup-codes").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("code", "DAKAR2000", "amount", 10, "currency", "EUR", "maxRedemptions", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("topup-code-duplicate"));
    }

    @Test
    void genererUnCode() throws Exception {
        mockMvc.perform(post("/admin/topup-codes/generate-code").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("YD-[A-Z2-9]{4}-[A-Z2-9]{4}")));
    }

    @Test
    void support_403_surToutesLesRoutes() throws Exception {
        var support = authentication(adminAuth(AdminRole.SUPPORT));
        mockMvc.perform(get("/admin/topup-codes").with(support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/topup-codes").with(support).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("amount", 10, "currency", "EUR")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/topup-codes/generate-code").with(support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/topup-codes/{id}/revoke", UUID.randomUUID()).with(support))
                .andExpect(status().isForbidden());
        assertThat(codeRepository.count()).isZero();
    }

    @Test
    void permissionSansRoleAdmin_403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("uid-x", null,
                List.of(new SimpleGrantedAuthority("TOPUP_CODE_MANAGE")));
        mockMvc.perform(get("/admin/topup-codes").with(authentication(auth))).andExpect(status().isForbidden());
    }
}
