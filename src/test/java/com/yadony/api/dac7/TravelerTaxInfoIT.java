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
    @Autowired Dac7ReminderService reminders;
    @Autowired Dac7Properties dac7Properties;
    @Autowired java.time.Clock clock;
    @Autowired com.yadony.api.config.PlatformSettingsService platformSettings;

    /**
     * Écouteur d'acceptation avec les messages DAC7 allumés (le profil de test laisse
     * {@code yadony.dac7.reminders.enabled} éteint, donc l'écouteur du contexte partagé ne fait rien), construit autour
     * des vrais beans, exécuté sur le fil du test.
     */
    private void accepted(UUID travelerId) {
        new Dac7AcceptanceListener(dac7Properties, Dac7ReminderProperties.defaults(true), reminders, clock,
                Runnable::run).onBidAccepted(new com.yadony.api.matching.events.BidAcceptedEvent(UUID.randomUUID(),
                UUID.randomUUID(), travelerId, UUID.randomUUID()));
    }

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
    void interrupteurDuBackOffice_sAppliqueSansRedemarrage() throws Exception {
        java.util.UUID admin = UUID.randomUUID();
        try {
            // Amorcé à vrai depuis application-test.yml ; le back-office fait ensuite autorité, tout de suite.
            platformSettings.update(java.util.Map.of(com.yadony.api.config.PlatformSettingKey.DAC7_ENABLED, "false"),
                    admin);
            mockMvc.perform(get(URL).with(as(me, "ROLE_TRAVELER"))).andExpect(status().isNotFound());
            mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                    .content(body(NIF, "FR", null))).andExpect(status().isNotFound());
            assertThat(users.findById(me.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();

            platformSettings.update(java.util.Map.of(com.yadony.api.config.PlatformSettingKey.DAC7_ENABLED, "true"),
                    admin);
            mockMvc.perform(get(URL).with(as(me, "ROLE_TRAVELER"))).andExpect(status().isOk());
        } finally {
            platformSettings.update(java.util.Map.of(com.yadony.api.config.PlatformSettingKey.DAC7_ENABLED, "true"),
                    admin);
        }
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
                .andExpect(jsonPath("$.dac7Status").value("DECLARABLE"))
                // Jamais payé : l'ancien contrat ne lui demande rien.
                .andExpect(jsonPath("$.solicitation").value("NE_PAS_SOLLICITER"))
                // Collecte ciblée : résident de l'Union, tout manque, invite non bloquante.
                .andExpect(jsonPath("$.collectionStatus").value("INFO_REQUIRED"))
                .andExpect(jsonPath("$.missingFields[0]").value("NAME"))
                .andExpect(jsonPath("$.missingFields[3]").value("TAX_IDENTIFICATION_NUMBER"))
                .andExpect(jsonPath("$.actionRequired").value(true))
                .andExpect(jsonPath("$.readOnlyFields").isEmpty())
                .andExpect(jsonPath("$.identity.birthPlaceProvided").value(false))
                .andExpect(jsonPath("$.requestedAt").doesNotExist());
    }

    @Test
    void get_residentHorsUnion_nonDeclarable() throws Exception {
        UserEntity ivorian = traveler("CI");
        mockMvc.perform(get(URL).with(as(ivorian, "ROLE_TRAVELER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dac7Status").value("NON_DECLARABLE"))
                // Test 1 de la spec : jamais d'écran fiscal, aucune identité renvoyée.
                .andExpect(jsonPath("$.collectionStatus").value("NE_PAS_SOLLICITER"))
                .andExpect(jsonPath("$.actionRequired").value(false))
                .andExpect(jsonPath("$.missingFields").isEmpty())
                .andExpect(jsonPath("$.identity").doesNotExist());
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
                .andExpect(jsonPath("$.solicitation").value("DEJA_FOURNI"))
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
    void put_sansCorps_400(CapturedOutput output) throws Exception {
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("malformed-request")));
        // JSON mal formé (NIF tapé sans guillemets) : 400, et le jeton ne part jamais au journal.
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxIdentificationNumber\": ZX98NIF76, \"taxCountry\": \"FR\"}"))
                .andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain("ZX98NIF76");
        assertThat(users.findById(me.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();
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

    // ---- Collecte ciblée (spec du 11/11/2026) ----

    @Test
    void test2_paysInconnu_seulLePaysEstDemande() throws Exception {
        UserEntity unknown = traveler(null);
        mockMvc.perform(get(URL).with(as(unknown, "ROLE_TRAVELER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionStatus").value("RESIDENCE_INCONNUE"))
                .andExpect(jsonPath("$.missingFields.length()").value(1))
                .andExpect(jsonPath("$.missingFields[0]").value("RESIDENCE_COUNTRY"))
                .andExpect(jsonPath("$.identity").doesNotExist());
    }

    @Test
    void test4_nifFrancais_douzeChiffres422_treizeAccepte() throws Exception {
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("302321760005", "FR", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("tax-info-invalid")))
                .andExpect(jsonPath("$.field").value("taxIdentificationNumber"));
        assertThat(users.findById(me.getId()).orElseThrow().getTaxIdentificationNumber()).isNull();
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                .andExpect(status().isOk())
                // Le NIF ne manque plus ; nom, naissance et adresse restent à fournir.
                .andExpect(jsonPath("$.missingFields.length()").value(3))
                .andExpect(jsonPath("$.missingFields[2]").value("ADDRESS"))
                .andExpect(jsonPath("$.collectionStatus").value("INFO_REQUIRED"));
    }

    @Test
    void identiteSaisie_profilEtLieuDeNaissanceChiffre_sansFuite(CapturedOutput output) throws Exception {
        String identity = "{\"firstName\":\"Jeanne\",\"lastName\":\"Martin\",\"birthDate\":\"1988-07-14\","
                + "\"birthPlace\":\"Saint-Louis du Sénégal\",\"address\":{\"street\":\"12 rue des Lilas\","
                + "\"postalCode\":\"13001\",\"city\":\"Marseille\"}}";
        String response = mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(identity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionStatus").value("INFO_REQUIRED"))
                .andExpect(jsonPath("$.missingFields.length()").value(1))
                .andExpect(jsonPath("$.missingFields[0]").value("TAX_IDENTIFICATION_NUMBER"))
                .andExpect(jsonPath("$.identity.nameSource").value("PROFILE"))
                .andExpect(jsonPath("$.identity.birthDate").value("1988-07-14"))
                .andExpect(jsonPath("$.identity.address.city").value("Marseille"))
                .andExpect(jsonPath("$.identity.address.country").value("FR"))
                .andExpect(jsonPath("$.identity.addressReplaceable").value(false))
                .andExpect(jsonPath("$.identity.birthPlaceProvided").value(true))
                .andReturn().getResponse().getContentAsString();
        // Le lieu de naissance ne ressort jamais, et il est chiffré en base.
        assertThat(response).doesNotContain("Saint-Louis");
        String stored = jdbc.queryForObject("SELECT tax_birth_place FROM users WHERE id = ?", String.class,
                me.getId());
        assertThat(stored).isNotBlank().doesNotContain("Saint-Louis");
        UserEntity reloaded = users.findById(me.getId()).orElseThrow();
        assertThat(reloaded.getTaxBirthPlace()).isEqualTo("Saint-Louis du Sénégal");
        assertThat(reloaded.getResidenceStreet()).isEqualTo("12 rue des Lilas");
        assertThat(reloaded.getBirthDate()).isEqualTo(java.time.LocalDate.of(1988, 7, 14));
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(me.getId()))
                .extracting(Dac7DueDiligenceLogEntity::getSource, Dac7DueDiligenceLogEntity::getNote)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Dac7DiligenceSource.USER,
                        "NAME,BIRTH_DATE,ADDRESS,BIRTH_PLACE"));
        assertThat(taxAudits(me.getId())).singleElement().asString().contains("ADDRESS")
                .doesNotContain("Lilas").doesNotContain("Saint-Louis").doesNotContain("1988");

        // Le NIF arrive : COMPLETE, le lieu de naissance (devenu inutile) est effacé.
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NIF, "FR", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.actionRequired").value(false))
                .andExpect(jsonPath("$.identity.birthPlaceProvided").value(false));
        assertThat(jdbc.queryForObject("SELECT tax_birth_place FROM users WHERE id = ?", String.class, me.getId()))
                .isNull();
        assertThat(output.getAll()).doesNotContain("Saint-Louis").doesNotContain("Lilas").doesNotContain(NIF);
    }

    @Test
    void residentHorsUnion_identite422_rienEcrit() throws Exception {
        UserEntity senegalese = traveler("SN");
        mockMvc.perform(put(URL).with(as(senegalese, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"1990-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("tax-info-not-applicable")));
        assertThat(users.findById(senegalese.getId()).orElseThrow().getBirthDate()).isNull();
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(senegalese.getId())).isEmpty();
    }

    @Test
    void dateDeNaissanceMalFormee_422SansRecopie(CapturedOutput output) throws Exception {
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"14/07/1988\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.field").value("birthDate"));
        assertThat(output.getAll()).doesNotContain("14/07/1988");
    }

    @Test
    void acceptation_premiereDemandeNonBloquante_puisVisibleDansLeGet() throws Exception {
        accepted(me.getId());

        List<Dac7DueDiligenceLogEntity> rows = diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(me.getId());
        assertThat(rows).extracting(Dac7DueDiligenceLogEntity::getAction, Dac7DueDiligenceLogEntity::getNote)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Dac7DueDiligenceAction.REQUEST,
                        "ACCEPTANCE:NAME,BIRTH_DATE,ADDRESS,TAX_IDENTIFICATION_NUMBER"));
        mockMvc.perform(get(URL).with(as(me, "ROLE_TRAVELER")))
                .andExpect(jsonPath("$.requestedAt").isNotEmpty())
                .andExpect(jsonPath("$.actionRequired").value(true));
    }

    @Test
    void acceptation_paysInconnu_demandeDuSeulPays() throws Exception {
        UserEntity unknown = traveler(null);
        accepted(unknown.getId());

        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(unknown.getId()))
                .extracting(Dac7DueDiligenceLogEntity::getAction, Dac7DueDiligenceLogEntity::getNote)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Dac7DueDiligenceAction.REQUEST,
                        "ACCEPTANCE:RESIDENCE_COUNTRY"));
        assertThat(users.findById(unknown.getId()).orElseThrow().getTaxInfoRequestedAt()).isNull();
        // Une seule fois : une deuxième acceptation ne redemande pas le pays.
        accepted(unknown.getId());
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(unknown.getId())).hasSize(1);
    }

    @Test
    void acceptation_residentHorsUnion_rien() throws Exception {
        UserEntity ivorian = traveler("CI");
        accepted(ivorian.getId());
        assertThat(diligenceLog.findByUserIdOrderByCreatedAtAscIdAsc(ivorian.getId())).isEmpty();
        assertThat(users.findById(ivorian.getId()).orElseThrow().getTaxInfoRequestedAt()).isNull();
    }

    @Test
    void paysDeResidence_sueDeclareSansToucherAuProfil() throws Exception {
        UserEntity unknown = traveler(null);
        mockMvc.perform(get(URL).with(as(unknown, "ROLE_TRAVELER")))
                .andExpect(jsonPath("$.collectionStatus").value("RESIDENCE_INCONNUE"))
                .andExpect(jsonPath("$.residenceCountryChoices").value(org.hamcrest.Matchers.hasItems("SE", "FR", "CI")));
        mockMvc.perform(put(URL).with(as(unknown, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residenceCountry\":\"SE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residenceCountry").value("SE"))
                .andExpect(jsonPath("$.collectionStatus").value("INFO_REQUIRED"))
                .andExpect(jsonPath("$.residenceCountryChoices").doesNotExist())
                .andExpect(jsonPath("$.identity").exists());
        UserEntity reloaded = users.findById(unknown.getId()).orElseThrow();
        assertThat(reloaded.getCountry()).isNull();
        assertThat(reloaded.getTaxDeclaredResidenceCountry()).isEqualTo("SE");
        // Profil renseigné : le pays se change dans le profil, pas ici.
        mockMvc.perform(put(URL).with(as(me, "ROLE_TRAVELER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residenceCountry\":\"SE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.field").value("residenceCountry"));
    }
}
