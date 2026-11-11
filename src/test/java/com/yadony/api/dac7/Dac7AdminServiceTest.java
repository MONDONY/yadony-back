package com.yadony.api.dac7;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Other;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.Dac7Report.Anomaly;
import com.yadony.api.dac7.Dac7Report.RefundToReconcile;
import com.yadony.api.dac7.dto.Dac7OverviewResponse;
import com.yadony.api.dac7.dto.Dac7SellerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Admin DAC7 : vue d'ensemble, filtres, relance, remboursements et anomalies")
class Dac7AdminServiceTest {

    private static final Instant NOW = Instant.parse("2027-01-15T09:00:00Z");
    private final Dac7DeclarationService declarations = mock(Dac7DeclarationService.class);
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);
    private final Dac7ReminderService reminders = mock(Dac7ReminderService.class);
    private final Dac7DueDiligenceService diligence = mock(Dac7DueDiligenceService.class);
    private final AuditService audit = mock(AuditService.class);
    private final Dac7AdminService service = new Dac7AdminService(declarations, reads, reminders, diligence,
            Dac7ReminderProperties.defaults(true), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID withTax = UUID.randomUUID();
    private final UUID withoutTax = UUID.randomUUID();
    private final UUID unknown = UUID.randomUUID();
    private final UUID ivorian = UUID.randomUUID();

    private Seller seller(UUID id, boolean tax, Dac7IdentityStatus identity, boolean eur) {
        return new Seller(id, "Voyageur " + id.toString().substring(0, 4), "FR", tax, identity, null, tax ? 0 : 1,
                null, null, 2, new BigDecimal("100.00"), new BigDecimal("5.00"), eur, true, List.of(), false);
    }

    @BeforeEach
    void setUp() {
        when(declarations.requireYear(2026)).thenReturn(2026);
        when(declarations.collectionEnabled()).thenReturn(true);
        Dac7Report report = new Dac7Report(2026, List.of(), List.of(),
                List.of(new RefundToReconcile(withTax, Dac7Source.PAYMENT, UUID.randomUUID(), "EUR",
                        new BigDecimal("10.00"), NOW, 2026, NOW, 2026, false, false, new BigDecimal("10.00"))),
                List.of(), List.of(unknown), List.of(ivorian), List.of(),
                List.of(new Anomaly(Dac7Report.AnomalyType.EXCHANGE_RATE_MISSING, withoutTax, Dac7Source.PAYMENT,
                        UUID.randomUUID(), "USD", LocalDate.of(2026, 8, 3)),
                        new Anomaly(Dac7Report.AnomalyType.TRAVELER_UNKNOWN, null, Dac7Source.PAYMENT,
                                UUID.randomUUID(), "EUR", LocalDate.of(2026, 8, 3))));
        Declaration d = new Declaration(2026, report,
                List.of(seller(withTax, true, Dac7IdentityStatus.COMPLETE, true),
                        seller(withoutTax, false, Dac7IdentityStatus.INCOMPLETE, false)),
                List.of(new Other(unknown, "Inconnu", null, Dac7ResidencyStatus.RESIDENCE_INCONNUE, false,
                                Dac7IdentityStatus.INCOMPLETE, 0, null),
                        new Other(ivorian, "Koffi", "CI", Dac7ResidencyStatus.NON_DECLARABLE, false,
                                Dac7IdentityStatus.COMPLETE, 0, null)),
                List.of(), List.of("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING", "EUR_INCOMPLETE"),
                Map.of(withTax, "Awa D.", withoutTax, "Moussa K."), 3);
        when(declarations.declaration(2026, false)).thenReturn(d);
        when(declarations.declaration(2026, Dac7DeclarationService.IdentityMode.NONE)).thenReturn(d);
    }

    @Test
    @DisplayName("vue d'ensemble : compteurs, totaux, blocages")
    void overview() {
        Dac7OverviewResponse o = service.overview(2026);
        assertThat(o.declarableSellers()).isEqualTo(2);
        assertThat(o.withTaxInfo()).isEqualTo(1);
        assertThat(o.missingTaxInfo()).isEqualTo(1);
        assertThat(o.identityReview()).isEqualTo(1);
        assertThat(o.unknownResidence()).isEqualTo(1);
        assertThat(o.nonDeclarable()).isEqualTo(1);
        assertThat(o.refundsToReconcile()).isEqualTo(1);
        assertThat(o.anomalies()).isEqualTo(2);
        assertThat(o.totals().operations()).isEqualTo(4);
        assertThat(o.totals().netEur()).isEqualByComparingTo("200.00");
        assertThat(o.eurComplete()).isFalse();
        assertThat(o.exportReady()).isFalse();
        assertThat(o.enabled()).isTrue();
        assertThat(o.remindersEnabled()).isTrue();
        assertThat(o.blockers()).containsExactly("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING", "EUR_INCOMPLETE");
        assertThat(o.warnings()).isEqualTo(3);
    }

    @Test
    @DisplayName("note de décision avec 9 chiffres ou plus (NIF, compte) : 422 dac7-note-sensitive, jamais inscrite")
    void noteSensible() {
        UUID admin = UUID.randomUUID();
        for (String note : List.of("NIF 302321760", "Appelé au 06 12 34 56 78", "IBAN FR76 3000 1007 9412")) {
            assertThatThrownBy(() -> service.decision(withTax, admin, note))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("dac7-note-sensitive");
                        assertThat(e.getMessage()).doesNotContain("302321760");
                    });
        }
        assertThat(Dac7AdminService.looksSensitive("Rappel le 12/01/2027, 3 colis")).isFalse();
        verifyNoInteractions(diligence);
    }

    @ParameterizedTest
    @CsvSource({"ALL,4", "MISSING_TAX_INFO,1", "IDENTITY_REVIEW,1", "UNKNOWN_RESIDENCE,1", "NON_DECLARABLE,1",
            "missing_tax_info,1"})
    @DisplayName("filtres de la liste des voyageurs")
    void filtres(String filter, int expected) {
        assertThat(service.sellers(2026, filter)).hasSize(expected);
    }

    @Test
    @DisplayName("lignes : sollicitation et rappels toujours présents, montants omis hors périmètre ; filtre inconnu 422")
    void lignes() {
        List<Dac7SellerRow> rows = service.sellers(2026, null);
        Dac7SellerRow missing = rows.stream().filter(r -> r.userId().equals(withoutTax)).findFirst().orElseThrow();
        assertThat(missing.solicitation()).isEqualTo("SOLLICITER");
        assertThat(missing.remindersSent()).isEqualTo(1);
        assertThat(missing.netEur()).isEqualByComparingTo("100.00");
        Dac7SellerRow given = rows.stream().filter(r -> r.userId().equals(withTax)).findFirst().orElseThrow();
        assertThat(given.solicitation()).isEqualTo("DEJA_FOURNI");
        Dac7SellerRow other = rows.stream().filter(r -> r.userId().equals(ivorian)).findFirst().orElseThrow();
        assertThat(other.solicitation()).isEqualTo("NE_PAS_SOLLICITER");
        assertThat(other.residencyStatus()).isEqualTo("NON_DECLARABLE");
        assertThat(other.netEur()).isNull();
        assertThatThrownBy(() -> service.sellers(2026, "TOUS")).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("dac7-filter-invalid"));
    }

    @Test
    @DisplayName("remboursements et anomalies : champs du calcul tels quels, plus le nom affiché")
    void remboursementsEtAnomalies() {
        assertThat(service.refunds(2026)).singleElement().satisfies(r -> {
            assertThat(r.displayName()).isEqualTo("Awa D.");
            assertThat(r.travelerId()).isEqualTo(withTax);
            assertThat(r.amountEur()).isEqualByComparingTo("10.00");
        });
        assertThat(service.anomalies(2026)).extracting(a -> a.displayName()).containsExactly("Moussa K.", null);
    }

    @ParameterizedTest
    @CsvSource({"TOO_SOON,dac7-remind-too-soon", "EXHAUSTED,dac7-reminders-exhausted",
            "NOT_SOLICITABLE,dac7-not-solicitable", "NOT_DUE,dac7-not-solicitable", "NOT_FOUND,user-not-found"})
    @DisplayName("relance refusée : codes 409 du contrat (et 404)")
    void relanceRefusee(Dac7ReminderService.Outcome outcome, String code) {
        UUID admin = UUID.randomUUID();
        when(reminders.remindByAdmin(withoutTax, admin, NOW)).thenReturn(outcome);
        assertThatThrownBy(() -> service.remind(withoutTax, admin)).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("relance envoyée : auditée ; collecte éteinte : 409 sans rien tenter")
    void relance() {
        UUID admin = UUID.randomUUID();
        when(reminders.remindByAdmin(withoutTax, admin, NOW)).thenReturn(Dac7ReminderService.Outcome.SENT);
        service.remind(withoutTax, admin);
        verify(audit).log(eq("USER"), eq(withoutTax), eq("DAC7_REMINDER_SENT"), eq(admin), any());

        when(declarations.collectionEnabled()).thenReturn(false);
        Dac7AdminService off = new Dac7AdminService(declarations, reads, mock(Dac7ReminderService.class), diligence,
                Dac7ReminderProperties.defaults(true), audit, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> off.remind(withoutTax, admin)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("dac7-collection-disabled"));
        assertThat(off.overview(2026).remindersEnabled()).isFalse();
    }

    @Test
    @DisplayName("registre : lignes reprises telles quelles")
    void registre() {
        when(reads.diligence(withTax)).thenReturn(List.of(new Dac7AdminReadModel.DiligenceRow(NOW, "REQUEST",
                "IN_APP", null, null)));
        assertThat(service.diligence(withTax)).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo("REQUEST");
            assertThat(e.actorName()).isNull();
        });
        verifyNoInteractions(diligence);
    }
}
