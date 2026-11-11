package com.yadony.api.dac7;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Couche HTTP de l'export sans contexte Spring (le calcul, en SQL PostgreSQL, est couvert par
 * {@code Dac7ExportPostgresIT}) : en-têtes de la réponse, désérialisation de la requête, réponse des récapitulatifs, et
 * liaison des propriétés d'export.
 */
@DisplayName("Admin DAC7 (HTTP) : en-têtes de l'export, includeAlreadyExported, récapitulatifs, propriétés")
class Dac7AdminControllerWebTest {

    private final Dac7AdminService admin = mock(Dac7AdminService.class);
    private final Dac7ExportService exports = mock(Dac7ExportService.class);
    private final Dac7SellerStatementService statements = mock(Dac7SellerStatementService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Dac7AdminController(admin, exports, statements))
            .build();
    private final UUID adminId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(adminId, "admin@yadony.test", AdminRole.ADMIN, false, "uid"), null, List.of());

    @Test
    void export_enTetesEtOption() throws Exception {
        UUID run = UUID.randomUUID();
        when(exports.export(eq(2026), eq("PARTIAL"), isNull(), eq(true), eq(adminId))).thenReturn(
                new Dac7ExportService.ExportFile("DPIDAC7_2026_123456789_002_20270115100000.xml.gz.gpg",
                        new byte[]{1, 2}, true, 3, 4, 5, run, 2));

        mvc.perform(post("/admin/dac7/exports").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"mode\":\"PARTIAL\",\"includeAlreadyExported\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"DPIDAC7_2026_123456789_002_20270115100000.xml.gz.gpg\""))
                .andExpect(header().string("X-Dac7-Excluded", "4"))
                .andExpect(header().string("X-Dac7-Encrypted", "true"))
                .andExpect(header().string("X-Dac7-Warnings", "5"))
                .andExpect(header().string("X-Dac7-Run-Id", run.toString()))
                .andExpect(content().bytes(new byte[]{1, 2}));
    }

    @Test
    void export_sansOption_faux() throws Exception {
        when(exports.export(eq(2026), eq("FULL"), eq(1), eq(false), eq(adminId))).thenReturn(
                new Dac7ExportService.ExportFile("f.xml", new byte[0], false, 1, 0, 0, UUID.randomUUID(), 1));
        mvc.perform(post("/admin/dac7/exports").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"mode\":\"FULL\",\"sequence\":1}"))
                .andExpect(status().isOk());
        verify(exports).export(eq(2026), eq("FULL"), eq(1), eq(false), eq(adminId));
    }

    @Test
    void depot_204() throws Exception {
        UUID run = UUID.randomUUID();
        mvc.perform(post("/admin/dac7/exports/{id}/deposited", run).principal(principal))
                .andExpect(status().isNoContent());
        verify(exports).markDeposited(run, adminId);
    }

    @Test
    void recapitulatifs_sansEmail() throws Exception {
        UUID phoneOnly = UUID.randomUUID();
        when(statements.send(2026, adminId)).thenReturn(new com.yadony.api.dac7.dto.Dac7StatementsResponse(2, 1, 1,
                List.of(phoneOnly)));
        mvc.perform(post("/admin/dac7/statements/send").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).content("{\"year\":2026}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.skippedNoEmail").value(1))
                .andExpect(jsonPath("$.skippedNoEmailTravelerIds[0]").value(phoneOnly.toString()));
    }

    @Test
    void liaisonDesProprietes_allowUnencrypted() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "yadony.dac7.export.allow-unencrypted", "true",
                "yadony.dac7.export.declarant-siren", "123456789")));
        Dac7ExportProperties bound = binder.bind("yadony.dac7.export", Dac7ExportProperties.class).get();
        assertThat(bound.unencryptedAllowed()).isTrue();
        assertThat(bound.declarantSiren()).isEqualTo("123456789");
        Dac7ExportProperties absent = new Binder(new MapConfigurationPropertySource(Map.of(
                "yadony.dac7.export.declarant-siren", "123456789")))
                .bind("yadony.dac7.export", Dac7ExportProperties.class).get();
        assertThat(absent.unencryptedAllowed()).isFalse();
    }
}
