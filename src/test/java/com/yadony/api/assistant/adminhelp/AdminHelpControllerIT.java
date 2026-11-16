package com.yadony.api.assistant.adminhelp;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminHelpControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean LlmRouter router;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM assistant_user_daily_usage WHERE firebase_uid = ?", "admin:" + ADMIN_ID);
    }

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "help@yadony.test", role, false, "uid-help");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void sansAuthentificationRefuse() throws Exception {
        mockMvc.perform(get("/admin/help/articles")).andExpect(status().is4xxClientError());
    }

    @Test
    void leSupportLitLeSommaireSansLesLiensInterdits() throws Exception {
        mockMvc.perform(get("/admin/help/articles").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(6))
                .andExpect(jsonPath("$.articles[?(@.id == 'administrateurs')].accessible").value(hasItem(false)))
                .andExpect(jsonPath("$.articles[?(@.id == 'administrateurs')].routes[*]").isEmpty());
    }

    @Test
    void ficheEtFicheInconnue() throws Exception {
        mockMvc.perform(get("/admin/help/articles/voir-colis-en-cours").with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].path").value("/colis?view=ACTIVE"))
                .andExpect(jsonPath("$.accessible").value(true));
        mockMvc.perform(get("/admin/help/articles/nope").with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    void recherche() throws Exception {
        mockMvc.perform(get("/admin/help/search").param("q", "colis en cours").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("voir-colis-en-cours"));
    }

    @Test
    void questionALAssistant() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                new ParsedAnswer("Ouvrez **Colis**, vue **En cours**.", true,
                        List.of("/colis?view=ACTIVE", "/administrateurs"), false, List.of(), null, null))));
        mockMvc.perform(post("/admin/help/ask").with(authentication(auth(AdminRole.SUPPORT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"comment je fais pour regarder les colis en cours\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(true))
                .andExpect(jsonPath("$.actions[*].path").value(hasItem("/colis?view=ACTIVE")))
                .andExpect(jsonPath("$.actions[*].path").value(not(hasItem("/administrateurs"))))
                .andExpect(jsonPath("$.sources[0].id").value("voir-colis-en-cours"));
    }

    @Test
    void questionVideRefusee() throws Exception {
        mockMvc.perform(post("/admin/help/ask").with(authentication(auth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
