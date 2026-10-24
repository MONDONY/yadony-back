package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filtre {@code entityId} de {@code GET /admin/audit-log} sur une vraie base : seules les entrées de l'entité
 * demandée reviennent, sans lui le filtre ne change rien, un identifiant mal formé répond 400.
 * {@code audit_log} est immuable : le test n'écrit que des lignes neuves, d'un type propre à chaque exécution,
 * sans {@code payload} (la colonne jsonb de H2 ne se relit pas en {@code Map}).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminAuditEntityIdIT {

    @Autowired MockMvc mockMvc;
    @Autowired AuditService auditService;

    @Test
    void entityId_neRenvoieQueLesEntreesDeCetteEntite() throws Exception {
        String type = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        auditService.log(type, target, "PAYMENT_HOLD", null, null);
        auditService.log(type, target, "PAYMENT_RELEASE", null, null);
        auditService.log(type, other, "PAYMENT_HOLD", null, null);

        mockMvc.perform(get("/admin/audit-log").param("entityType", type).param("entityId", target.toString())
                        .with(authentication(auditor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].entityId").value(target.toString()))
                .andExpect(jsonPath("$.content[1].entityId").value(target.toString()));
        // L'identifiant seul suffit, et un identifiant inconnu ne renvoie rien.
        mockMvc.perform(get("/admin/audit-log").param("entityId", other.toString()).with(authentication(auditor())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/admin/audit-log").param("entityId", UUID.randomUUID().toString())
                        .with(authentication(auditor())))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void sansEntityId_lesFiltresExistantsRestentIdentiques() throws Exception {
        String type = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        auditService.log(type, UUID.randomUUID(), "A", null, null);
        auditService.log(type, UUID.randomUUID(), "B", null, null);

        mockMvc.perform(get("/admin/audit-log").param("entityType", type).with(authentication(auditor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/admin/audit-log").param("entityType", type).param("action", "A")
                        .with(authentication(auditor())))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void entityIdMalForme_400() throws Exception {
        mockMvc.perform(get("/admin/audit-log").param("entityId", "pas-un-uuid").with(authentication(auditor())))
                .andExpect(status().isBadRequest());
    }

    private static UsernamePasswordAuthenticationToken auditor() {
        return new UsernamePasswordAuthenticationToken("admin-uid", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("AUDIT_VIEW")));
    }
}
