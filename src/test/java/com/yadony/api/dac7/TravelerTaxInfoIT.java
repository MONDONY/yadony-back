package com.yadony.api.dac7;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /travelers/me/tax-info} de bout en bout (H2, contexte partagé : {@code yadony.dac7.enabled} est allumé par
 * application-test.yml, l'état éteint est couvert par {@link TravelerTaxInfoControllerTest}). Le NIF ne doit jamais
 * ressortir en clair : ni dans la réponse, ni dans la colonne, ni dans l'audit, ni dans les journaux.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TravelerTaxInfoIT {

    private static final String URL = "/travelers/me/tax-info";
    private static final String NIF = "3023217600053";
    private static final String VAT = "FR40303265045";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired Dac7DueDiligenceLogRepository diligenceLog;
    @Autowired JdbcTemplate jdbc;

    private UserEntity me;

    @BeforeEach
    void setUp() {
        me = traveler("FR");
    }

    private UserEntity traveler(String country) {
        UserEntity u = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setFirebaseUid("uid-tax-" + suffix);
        u.setUsername("tax-" + suffix);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        u.setCountry(country);
        return users.saveAndFlush(u);
    }

    private static RequestPostProcessor as(UserEntity user, String role) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getFirebaseUid(), null, List.of(new SimpleGrantedAuthority(role))));
    }

    private static String body(String nif, String country, String vat) {
        return "{\"taxIdentificationNumber\":\"" + nif + "\",\"taxCountry\":\"" + country + "\""
                + (vat == null ? "" : ",\"vatNumber\":\"" + vat + "\"") + "}";
    }

    /** Payloads (texte) des audits TRAVELER_TAX_INFO_SET du voyageur. Lus en SQL : la base H2 est partagée. */
    private List<String> taxAudits(UUID userId) {
        return jdbc.queryForList("SELECT CAST(payload AS VARCHAR) FROM audit_log "
                + "WHERE entity_id = ? AND action = 'TRAVELER_TAX_INFO_SET'", String.class, userId);
    }

    @Test
    void sansJeton_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invite_403() throws Exception {
        mockMvc.perform(get(URL).with(as(me, "ROLE_GUEST"))).andExpect(status().isForbidden());
        mockMvc.perform(put(URL).with(as(me, "ROLE_GUEST"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                .andExpect(status().isForbidden());
        assertThat(users.findById(me.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();
    }

    @Test
    void get_avantSaisie_nonFourniEtStatutDeclarable() throws Exception {
        mockMvc.perform(get(URL).with(as(me, "ROLE_TRAVELER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provided").value(false))
                .andExpect(jsonPath("$.residenceCountry").value("FR"))
                .andExpect(jsonPath("$.dac7Status").value("DECLARABLE"));
    }

    @Test
    void get_residentHorsUnion_nonDeclarable() throws Exception {
        UserEntity ivorian = traveler("CI");
        mockMvc.perform(get(URL).with(as(ivorian, "ROLE_TRAVELER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dac7Status").value("NON_DECLARABLE"));
    }

    @Test
    void put_chiffreAuditeSansValeurEtInscritAuRegistre(CapturedOutput output) throws Exception {
        String response = mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "fr", VAT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provided").value(true))
                .andExpect(jsonPath("$.maskedTaxIdentificationNumber").value("****0053"))
                .andExpect(jsonPath("$.taxCountry").value("FR"))
                .andExpect(jsonPath("$.vatNumberProvided").value(true))
                .andExpect(jsonPath("$.maskedVatNumber").value("****5045"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(NIF).doesNotContain(VAT);

        // Colonnes chiffrées au repos : le texte stocké n'est pas la valeur.
        String storedNif = jdbc.queryForObject("SELECT tax_identification_number FROM users WHERE id = ?",
                String.class, me.getId());
        String storedVat = jdbc.queryForObject("SELECT vat_number FROM users WHERE id = ?", String.class, me.getId());
        String storedCountry = jdbc.queryForObject("SELECT tax_country FROM users WHERE id = ?", String.class, me.getId());
        assertThat(storedNif).isNotBlank().doesNotContain(NIF);
        assertThat(storedVat).isNotBlank().doesNotContain(VAT);
        assertThat(storedCountry).isNotEqualTo("FR");
        UserEntity reloaded = users.findById(me.getId()).orElseThrow();
        assertThat(reloaded.getTaxIdentificationNumber()).isEqualTo(NIF);
        assertThat(reloaded.getTaxInfoProvidedAt()).isNotNull();

        List<String> audits = taxAudits(me.getId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("firstTime").doesNotContain(NIF).doesNotContain(VAT).doesNotContain("FR");
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(me.getId()))
                .extracting(Dac7DueDiligenceLogEntity::getAction, Dac7DueDiligenceLogEntity::getChannel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        Dac7DueDiligenceAction.PROVIDED, Dac7DueDiligenceChannel.IN_APP));

        String afterGet = mockMvc.perform(get(URL).with(as(me, "ROLE_TRAVELER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provided").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterGet).doesNotContain(NIF).doesNotContain(VAT);
        assertThat(output.getAll()).doesNotContain(NIF).doesNotContain(VAT);
    }

    @Test
    void put_doubleClic_uneSeuleEcriture() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.provided").value(true));
        }
        assertThat(taxAudits(me.getId())).hasSize(1);
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(me.getId())).hasSize(1);
    }

    @Test
    void put_invalide_422SansEcriture(CapturedOutput output) throws Exception {
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("12#4567", "FR", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("tax-info-invalid")));
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FRA", null)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(users.findById(me.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();
        assertThat(taxAudits(me.getId())).isEmpty();
        assertThat(output.getAll()).doesNotContain("12#4567");
    }

    @Test
    void unVoyageurNAgitQueSurLuiMeme() throws Exception {
        UserEntity other = traveler("BE");
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                .andExpect(status().isOk());
        assertThat(users.findById(other.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();
        mockMvc.perform(get(URL).with(as(other, "ROLE_TRAVELER")))
                .andExpect(jsonPath("$.provided").value(false));
    }
}
