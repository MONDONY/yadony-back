package com.yadony.api.assistant.formdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.formdraft.dto.FormDraftRequest;
import com.yadony.api.assistant.formdraft.dto.FormDraftTurn;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"yadony.assistant.enabled=true", "yadony.assistant.form-draft.enabled=true",
        "yadony.assistant.form-draft.daily-quota=3"})
class FormDraftControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean LlmRouter router;

    private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();
    private static final String NEXT_MONTH = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString();

    private static UsernamePasswordAuthenticationToken asUser(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private String body(String... texts) throws Exception {
        List<FormDraftTurn> turns = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) turns.add(new FormDraftTurn(i % 2 == 0 ? "user" : "assistant", texts[i]));
        return objectMapper.writeValueAsString(new FormDraftRequest(turns, null, "fr", TODAY, "Europe/Paris", "EUR"));
    }

    @Test
    void trajetRenvoieLeBrouillon() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("claude",
                new ModelOutput(Map.of("departureCity", "Paris", "arrivalCity", "Abidjan", "pricePerKg", 10.0,
                        "departureDate", NEXT_MONTH), false, null, List.of()))));
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-trip")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("Je voyage de Paris à Abidjan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.departureCity").value("Paris"))
                .andExpect(jsonPath("$.draft.pricePerKg").value(10))
                .andExpect(content().string(containsString("\"pricePerKg\":10")))
                .andExpect(jsonPath("$.missing[0]").value("departureTime"))
                .andExpect(jsonPath("$.question").value("À quelle heure partez-vous ?"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.remainingToday").value(2));
    }

    /** Contrat §3.3 : toutes les clés du brouillon et question présentes, même nulles (malgré NON_NULL global). */
    @Test
    void contratGardeLesClesNulles() throws Exception {
        Map<String, Object> complet = new java.util.HashMap<>();
        complet.put("departureCity", "Paris");
        complet.put("arrivalCity", "Abidjan");
        complet.put("departureDate", NEXT_MONTH);
        complet.put("departureTime", "15:00");
        complet.put("availableKg", 23);
        complet.put("pricePerKg", 10);
        complet.put("cashEnabled", true);
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("claude",
                new ModelOutput(complet, false, null, List.of()))));
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-contrat")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("tout est dit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$", hasKey("question")))
                .andExpect(jsonPath("$.draft", hasKey("negotiable")))
                .andExpect(jsonPath("$.draft", hasKey("transportMode")));
    }

    @Test
    void demandeDEnvoiCalculeLaTaille() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("claude",
                new ModelOutput(Map.of("weightKg", 5), false, null, List.of()))));
        mockMvc.perform(post("/assistant/form-draft/package-request").with(authentication(asUser("uid-colis")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("5 kilos de vêtements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.parcelSize").value("SMALL"))
                .andExpect(jsonPath("$.understood[0]").value("weight"));
    }

    @Test
    void quotaDepasse429() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-quota")))
                    .contentType(MediaType.APPLICATION_JSON).content(body("x"))).andExpect(status().isOk());
        }
        mockMvc.perform(post("/assistant/form-draft/package-request").with(authentication(asUser("uid-quota")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("assistant-form-draft-quota-exceeded"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.retryAfter").exists());
    }

    @Test
    void sansFournisseur200Degrade() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-degrade")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));
    }

    @Test
    void longueurDeLaConversation() throws Exception {
        String[] seventeen = new String[17];
        Arrays.fill(seventeen, "x");
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-tours")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(seventeen)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(containsString("validation")));

        String[] fifteen = new String[15];          // 8 tours user, le dernier est user : autorisé
        Arrays.fill(fifteen, "x");
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-tours")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(fifteen)))
                .andExpect(status().isOk());

        List<FormDraftTurn> nineUsers = new ArrayList<>();
        for (int i = 0; i < 7; i++) nineUsers.add(new FormDraftTurn("assistant", "y"));
        for (int i = 0; i < 9; i++) nineUsers.add(new FormDraftTurn("user", "x"));
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-tours-422")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FormDraftRequest(nineUsers, null, "fr", TODAY, "UTC", null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("form-draft-too-many-turns"));
    }

    @Test
    void roleInconnuRefuseParLaValidation() throws Exception {
        String json = "{\"messages\":[{\"role\":\"system\",\"text\":\"x\"}],\"today\":\"" + TODAY + "\"}";
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(asUser("uid-role")))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(containsString("validation")));
        verifyNoInteractions(router);
    }

    @Test
    void sansJeton401() throws Exception {
        mockMvc.perform(post("/assistant/form-draft/trip").contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invite403() throws Exception {
        UsernamePasswordAuthenticationToken guest = new UsernamePasswordAuthenticationToken("uid-guest", null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        mockMvc.perform(post("/assistant/form-draft/trip").with(authentication(guest))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isForbidden());
    }
}
