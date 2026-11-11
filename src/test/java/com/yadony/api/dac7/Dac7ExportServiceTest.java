package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import com.yadony.api.dac7.export.Dac7TestPgpKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Export DAC7 : blocages, mode partiel, chiffrement, nom de fichier, aucune fuite du NIF")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class Dac7ExportServiceTest {

    private static final String NIF = "SECRET-NIF-4242";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-15T09:30:05Z"), ZoneOffset.UTC);

    private final Dac7DeclarationService declarations = mock(Dac7DeclarationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final UUID admin = UUID.randomUUID();
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);

    @BeforeEach
    void setUp() {
        when(declarations.requireYear(2026)).thenReturn(2026);
    }

    /** Opérateur configuré ; export en clair autorisé (essais) seulement sans clé. */
    private static Dac7ExportProperties operator(String keyPath) {
        return new Dac7ExportProperties(keyPath, "123456789", "Yadony SAS", null, null, "10 rue de la Paix", "75002",
                "Paris", keyPath == null);
    }

    private Dac7ExportService service(Dac7ExportProperties properties) {
        return new Dac7ExportService(declarations, users, properties, audit, reads,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(), CLOCK);
    }

    private static List<QuarterReport> quarters(String q1) {
        return List.of(new QuarterReport(1, 2, List.of(), new BigDecimal(q1), new BigDecimal("5.50"), true, true),
                new QuarterReport(2, 0, List.of(), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), true, true),
                new QuarterReport(3, 0, List.of(), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), true, true),
                new QuarterReport(4, 1, List.of(), new BigDecimal("10.49"), new BigDecimal("0.50"), true, true));
    }

    private Seller seller(List<String> exclusions) {
        return seller(exclusions, false, null);
    }

    private Seller seller(List<String> exclusions, boolean alreadyExported, String line2) {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        user.setTaxIdentificationNumber(NIF);
        user.setTaxCountry("FR");
        when(users.findAllById(any())).thenReturn(List.of(user));
        Dac7SellerIdentity identity = new Dac7SellerIdentity(id, Dac7SellerIdentity.SellerType.INDIVIDUAL,
                Dac7IdentityStatus.COMPLETE, List.of(), "Awa", "Diallo", LocalDate.of(1990, 1, 2),
                new Dac7Address("1 rue de Paris", line2, "69001", "Lyon", "RE", null), null, null);
        TravelerReport report = new TravelerReport(id, "RE", true, Dac7IdentityStatus.COMPLETE, quarters("95.50"));
        return new Seller(id, "Awa Diallo", "RE", true, Dac7IdentityStatus.COMPLETE, identity, 0, null, report, 3,
                new BigDecimal("105.99"), new BigDecimal("6.00"), true, true, exclusions, alreadyExported);
    }

    private void declaration(List<Seller> sellers, List<String> blockers) {
        when(declarations.declaration(2026, Dac7DeclarationService.IdentityMode.FRESH)).thenReturn(new Declaration(2026,
                new Dac7Report(2026, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of()), sellers, List.of(), List.of(), blockers, Map.of(), 3));
    }

    @SuppressWarnings("unchecked")
    private static List<String> blockersOf(Throwable e) {
        return (List<String>) ((YadonyBusinessException) e).getProperties().get("blockers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDENTITY_INCOMPLETE", "IDENTITY_UNAVAILABLE", "TAX_INFO_MISSING", "EUR_INCOMPLETE",
            "COMMISSION_INCOMPLETE", "PENDING_SPLITS", "INVALID_PARTNER_JURISDICTION", "NO_SELLERS"})
    @DisplayName("FULL refusé en 409 dac7-export-blocked avec chaque code de blocage")
    void fullBloque(String code) {
        declaration(List.of(seller(List.of(code))), List.of(code));
        assertThatThrownBy(() -> service(operator(null)).export(2026, "FULL", null, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("dac7-export-blocked");
                })
                .satisfies(e -> assertThat(blockersOf(e)).containsExactly(code))
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getProperties()).containsKey("travelerIds"));
        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(reads, never()).recordExport(anyInt(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("opérateur non configuré (SIREN absent) : refusé dans les deux modes")
    void operateurAbsent() {
        declaration(List.of(seller(List.of())), List.of(Dac7DeclarationService.OPERATOR_CONFIG_MISSING));
        Dac7ExportProperties none = new Dac7ExportProperties(null, null, null, null, null, null, null, null);
        for (String mode : List.of("FULL", "PARTIAL")) {
            assertThatThrownBy(() -> service(none).export(2026, mode, null, admin))
                    .satisfies(e -> assertThat(blockersOf(e)).containsExactly("OPERATOR_CONFIG_MISSING"));
        }
        // Vérifiée AVANT le calcul : aucun téléchargement BCE ni lecture Didit pour un fichier impossible.
        verify(declarations, never()).declaration(anyInt(), any(Dac7DeclarationService.IdentityMode.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("PARTIAL : seuls les complets partent, les autres comptés exclus ; aucun complet : NO_SELLERS")
    void partiel() {
        Seller ok = seller(List.of());
        Seller missing = seller(List.of("TAX_INFO_MISSING"));
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", ok.userId());
        user.setTaxIdentificationNumber(NIF);
        user.setTaxCountry("FR");
        when(users.findAllById(List.of(ok.userId()))).thenReturn(List.of(user));
        declaration(List.of(ok, missing), List.of("TAX_INFO_MISSING"));

        Dac7ExportService.ExportFile file = service(operator(null)).export(2026, "partial", 1, admin);

        assertThat(file.sellers()).isEqualTo(1);
        assertThat(file.excluded()).isEqualTo(1);
        assertThat(file.warnings()).isEqualTo(3);
        assertThat(file.encrypted()).isFalse();
        assertThat(file.fileName()).isEqualTo("DPIDAC7_2026_123456789_001_20270115103005.xml");
        String xml = new String(file.content(), StandardCharsets.UTF_8);
        // Arrondi à l'euro à l'export seulement : 95,50 → 96, 10,49 → 10, 5,50 → 6, 0,50 → 1.
        assertThat(xml).contains("<dpi:ConsQ1 currCode=\"EUR\">96</dpi:ConsQ1>")
                .contains("<dpi:ConsQ4 currCode=\"EUR\">10</dpi:ConsQ4>")
                .contains("<dpi:FeesQ1 currCode=\"EUR\">6</dpi:FeesQ1>")
                .contains("<dpi:FeesQ4 currCode=\"EUR\">1</dpi:FeesQ4>")
                // La Réunion est déclarée FR (annexe 6.4).
                .contains("<dpi:ResCountryCode>FR</dpi:ResCountryCode>")
                .contains("<dpi:TIN issuedBy=\"FR\">" + NIF + "</dpi:TIN>")
                // DocRefId déterministe : identifiant interne du voyageur + ordre.
                .contains("<stf:DocRefId>OP_2026_123456789_" + ok.userId().toString().replace("-", "")
                        + "_001</stf:DocRefId>")
                .contains("<stf:DocRefId>OP_2026_123456789_OP001</stf:DocRefId>");
        ArgumentCaptor<List<Dac7AdminReadModel.ExportSellerRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(reads).recordExport(eq(2026), any(), eq(1), eq("PARTIAL"), eq(admin), any(), rows.capture());
        assertThat(rows.getValue()).containsExactlyInAnyOrder(
                new Dac7AdminReadModel.ExportSellerRow(ok.userId(), true,
                        "OP_2026_123456789_" + ok.userId().toString().replace("-", "") + "_001", null),
                new Dac7AdminReadModel.ExportSellerRow(missing.userId(), false, null, "TAX_INFO_MISSING"));

        declaration(List.of(missing), List.of("TAX_INFO_MISSING"));
        assertThatThrownBy(() -> service(operator(null)).export(2026, "PARTIAL", null, admin))
                .satisfies(e -> assertThat(blockersOf(e)).contains("NO_SELLERS"));
    }

    @Test
    @DisplayName("audit DAC7_EXPORT_GENERATED : année, mode, nombres, chiffré ; aucune donnée personnelle")
    @SuppressWarnings("unchecked")
    void auditSansDonnee() {
        declaration(List.of(seller(List.of())), List.of());
        service(operator(null)).export(2026, "FULL", null, admin);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("DAC7_EXPORT"), any(), eq("DAC7_EXPORT_GENERATED"), eq(admin), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("year", "mode", "sequence", "sellers", "excluded", "encrypted",
                        "warnings", "runId")
                .containsEntry("mode", "FULL").containsEntry("encrypted", false);
        assertThat(payload.getValue().toString()).doesNotContain(NIF).doesNotContain("Diallo");
    }

    @Test
    @DisplayName("clé configurée : .xml.gz.gpg, déchiffrable, XML d'origine ; clé illisible : 422 sans contenu")
    void chiffre(@TempDir Path dir) throws Exception {
        Path key = Dac7TestPgpKeys.writeArmoredPublicKey(dir);
        declaration(List.of(seller(List.of())), List.of());

        Dac7ExportService.ExportFile file = service(operator(key.toString())).export(2026, "FULL", null, admin);

        assertThat(file.encrypted()).isTrue();
        assertThat(file.fileName()).isEqualTo("DPIDAC7_2026_123456789_000_20270115103005.xml.gz.gpg");
        assertThat(new String(file.content(), StandardCharsets.ISO_8859_1)).doesNotContain(NIF);
        assertThat(Dac7TestPgpKeys.recipientKeyId(file.content()))
                .isEqualTo(Dac7TestPgpKeys.standard().subkeys().get(0).getKeyID());
        Dac7TestPgpKeys.Decrypted clear = Dac7TestPgpKeys.decrypt(file.content());
        assertThat(clear.literalName()).isEqualTo("DPIDAC7_2026_123456789_000_20270115103005.xml.gz");
        assertThat(new String(clear.xml(), StandardCharsets.UTF_8)).contains(NIF);

        assertThatThrownBy(() -> service(operator(dir.resolve("absente.asc").toString()))
                .export(2026, "FULL", null, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-export-key-unavailable"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("valeur refusée par le XSD : voyageur identifié (identifiant seul) ; FULL 409, PARTIAL l'exclut ; aucune fuite")
    void nonConformeIdentifieSansFuite(org.springframework.boot.test.system.CapturedOutput output) {
        Seller bad = seller(List.of());
        Seller good = seller(List.of());
        UserEntity badUser = new UserEntity();
        ReflectionTestUtils.setField(badUser, "id", bad.userId());
        badUser.setTaxIdentificationNumber(NIF + "X".repeat(200));
        badUser.setTaxCountry("FR");
        UserEntity goodUser = new UserEntity();
        ReflectionTestUtils.setField(goodUser, "id", good.userId());
        goodUser.setTaxIdentificationNumber(NIF);
        goodUser.setTaxCountry("FR");
        when(users.findAllById(any())).thenReturn(List.of(badUser, goodUser));
        declaration(List.of(bad, good), List.of());

        assertThatThrownBy(() -> service(operator(null)).export(2026, "FULL", null, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("dac7-export-blocked");
                    assertThat(e.getProperties()).containsEntry("blockers", List.of("XSD_INVALID"))
                            .containsEntry("travelerIds", List.of(bad.userId().toString()));
                    assertThat(e.getMessage() + e.getProperties()).doesNotContain(NIF);
                });

        Dac7ExportService.ExportFile file = service(operator(null)).export(2026, "PARTIAL", null, admin);
        assertThat(file.sellers()).isEqualTo(1);
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).doesNotContain("XXXX");
        ArgumentCaptor<List<Dac7AdminReadModel.ExportSellerRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(reads).recordExport(eq(2026), any(), eq(0), eq("PARTIAL"), eq(admin), any(), rows.capture());
        assertThat(rows.getValue()).contains(new Dac7AdminReadModel.ExportSellerRow(bad.userId(), false, null,
                "XSD_INVALID"));
        assertThat(output.getAll()).doesNotContain(NIF).doesNotContain("Diallo");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("second PARTIAL : les voyageurs déjà retenus sont écartés (ALREADY_EXPORTED), sauf option explicite")
    void dejaExportes() {
        Seller done = seller(List.of(), true, null);
        declaration(List.of(done), List.of());
        assertThatThrownBy(() -> service(operator(null)).export(2026, "PARTIAL", 1, admin))
                .satisfies(e -> assertThat(blockersOf(e)).contains("NO_SELLERS"));
        ArgumentCaptor<List<Dac7AdminReadModel.ExportSellerRow>> rows = ArgumentCaptor.forClass(List.class);

        Dac7ExportService.ExportFile file = service(operator(null)).export(2026, "PARTIAL", 1, true, admin);
        assertThat(file.sellers()).isEqualTo(1);
        verify(reads).recordExport(eq(2026), any(), eq(1), eq("PARTIAL"), eq(admin), any(), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(r -> assertThat(r.included()).isTrue());
    }

    @Test
    @DisplayName("complément d'adresse : l'adresse part entière dans la zone libre (AddressFree), jamais en BuildingIdentifier")
    void complementDAdresse() {
        declaration(List.of(seller(List.of(), false, "chez M. Koné")), List.of());
        String xml = new String(service(operator(null)).export(2026, "FULL", null, admin).content(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<dpi:AddressFree>1 rue de Paris, chez M. Koné, 69001 Lyon</dpi:AddressFree>")
                .doesNotContain("BuildingIdentifier");
    }

    @Test
    @DisplayName("ordre déjà employé pour l'année : 409 dac7-sequence-used ; ordre par défaut = suivant de l'année")
    void ordre() {
        declaration(List.of(seller(List.of())), List.of());
        when(reads.sequenceUsed(2026, 0)).thenReturn(true);
        assertThatThrownBy(() -> service(operator(null)).export(2026, "FULL", 0, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-sequence-used"));
        when(reads.nextSequence(2026)).thenReturn(4);
        Dac7ExportService.ExportFile file = service(operator(null)).export(2026, "FULL", null, admin);
        assertThat(file.sequence()).isEqualTo(4);
        assertThat(file.fileName()).contains("_004_");
        assertThat(file.runId()).isNotNull();
        verify(reads, org.mockito.Mockito.times(2)).lockYear(2026);
        verify(reads).insertRun(eq(file.runId()), eq(2026), eq(4), eq("FULL"), eq(file.fileName()), eq(1), eq(admin),
                any());
    }

    @Test
    @DisplayName("raisons : dédoublonnées, jamais plus de 255 caractères, toujours des codes entiers")
    void raisons() {
        assertThat(Dac7ExportService.reasonText(List.of("TAX_INFO_MISSING", "TAX_INFO_MISSING", "EUR_INCOMPLETE")))
                .isEqualTo("TAX_INFO_MISSING,EUR_INCOMPLETE");
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add("RAISON_NUMERO_" + i);
        String text = Dac7ExportService.reasonText(many);
        assertThat(text.length()).isLessThanOrEqualTo(255);
        assertThat(text).startsWith("RAISON_NUMERO_0,").doesNotEndWith(",");
        assertThat(text.split(",")).allMatch(code -> code.matches("RAISON_NUMERO_\\d+"));
    }

    @Test
    @DisplayName("sans clé et sans autorisation explicite : 409 dac7-unencrypted-not-allowed, rien calculé")
    void clairRefuseParDefaut() {
        Dac7ExportProperties noKey = new Dac7ExportProperties(null, "123456789", "Yadony SAS", null, null, null, null,
                "Paris");
        assertThatThrownBy(() -> service(noKey).export(2026, "FULL", null, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("dac7-unencrypted-not-allowed");
                });
        verify(declarations, never()).declaration(anyInt(), any(Dac7DeclarationService.IdentityMode.class));
    }

    @Test
    @DisplayName("requête invalide : mode inconnu ou numéro d'ordre hors 0-999")
    void requeteInvalide() {
        assertThatThrownBy(() -> service(operator(null)).export(2026, "TOUT", null, admin))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-export-invalid-request"));
        assertThatThrownBy(() -> service(operator(null)).export(2026, "FULL", 1000, admin))
                .isInstanceOf(YadonyBusinessException.class);
        verify(declarations, never()).declaration(anyInt(), anyBoolean());
        verify(declarations, never()).declaration(anyInt(), any(Dac7DeclarationService.IdentityMode.class));
    }

    @Test
    @DisplayName("CSV de contrôle : jamais le NIF, oui/non seulement, formule neutralisée, audit dédié")
    @SuppressWarnings("unchecked")
    void csvDeControle() {
        Seller s = seller(List.of("TAX_INFO_MISSING"));
        Seller evil = new Seller(UUID.randomUUID(), "=HYPERLINK(\"x\")", "FR", false, Dac7IdentityStatus.INCOMPLETE,
                null, 1, null, s.report(), 3, s.netEur(), s.commissionEur(), true, true, List.of("TAX_INFO_MISSING"),
                false);
        when(declarations.declaration(2026, Dac7DeclarationService.IdentityMode.CACHED)).thenReturn(new Declaration(2026,
                new Dac7Report(2026, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of()), List.of(s, evil),
                List.of(new Dac7DeclarationService.Other(UUID.randomUUID(), "Koffi", "CI",
                        Dac7ResidencyStatus.NON_DECLARABLE, false, Dac7IdentityStatus.COMPLETE, 0, null)),
                List.of(), List.of(), Map.of(), 0));

        String csv = service(operator(null)).controlCsv(2026, admin);

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).startsWith("﻿userId;voyageur;pays;residence;nifFourni;identite;t1NetEur");
        assertThat(lines[0]).contains(";dejaExporte;exclusions");
        assertThat(csv).doesNotContain(NIF).contains(";oui;").contains("TAX_INFO_MISSING").contains("NON_DECLARE")
                .contains("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(lines[1].split(";", -1)).hasSize(lines[0].split(";", -1).length);
        assertThat(lines[3].split(";", -1)).hasSize(lines[0].split(";", -1).length);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("DAC7_EXPORT"), any(), eq("DAC7_CONTROL_CSV_GENERATED"), eq(admin), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("year", "travelers").containsEntry("travelers", 3);
        verify(audit, never()).log(anyString(), any(), eq("DAC7_EXPORT_GENERATED"), any(), any());
    }
}
