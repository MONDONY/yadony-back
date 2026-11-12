package com.yadony.api.dac7;

import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.RefundTreatment;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import com.yadony.api.dac7.Dac7ReportReadModel.OperationRow;
import com.yadony.api.dac7.Dac7ReportReadModel.RefundRow;
import com.yadony.api.dac7.Dac7ReportReadModel.TravelerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Règles de montants DAC7 (BOI-INT-AEA-30-40) : devise du paiement sans conversion, euros si plusieurs devises, frais
 * retenus nuls, remboursements (§ 260), date de remise des espèces.
 */
@DisplayName("Montants DAC7 : devise, remboursements, frais, date des espèces")
class Dac7MontantsReportTest {

    private static final int YEAR = 2026;
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    private final Dac7ReportReadModel readModel = mock(Dac7ReportReadModel.class);
    private final Dac7ResidencyPolicy policy = new Dac7ResidencyPolicy(new Dac7Properties(false, null));
    private final MapExchangeRateProvider rates = new MapExchangeRateProvider().everyDay("XOF", "655.957");
    private final Dac7ReportService service = new Dac7ReportService(readModel, policy, rates);

    private final List<OperationRow> operations = new ArrayList<>();
    private final List<RefundRow> refunds = new ArrayList<>();
    private final UUID french = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(readModel.operations(FROM, TO)).thenReturn(operations);
        when(readModel.refunds(FROM, TO)).thenReturn(refunds);
        when(readModel.pendingSplits()).thenReturn(List.of());
        List<TravelerRow> travelers = List.of(new TravelerRow(french, "FR", "INDIVIDUAL", null, null, true, true));
        when(readModel.travelers(any())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(0);
            return travelers.stream().filter(t -> ids.contains(t.travelerId())).toList();
        });
    }

    private UUID op(String rail, String currency, String net, String commission, String paidAt, boolean fallback) {
        UUID id = UUID.randomUUID();
        operations.add(new OperationRow("CASH".equals(rail) ? Dac7Source.CASH : Dac7Source.PAYMENT, id, french, rail,
                currency, new BigDecimal(net), commission == null ? null : new BigDecimal(commission),
                Instant.parse(paidAt), true, false, fallback));
        return id;
    }

    private UUID op(String currency, String net, String paidAt) {
        return op("STRIPE", currency, net, "0.00", paidAt, false);
    }

    private RefundRow refund(UUID payment, String currency, String amount, String operationPaidAt, String refundedAt,
                             boolean reimbursed) {
        return new RefundRow(Dac7Source.PAYMENT, payment, french, currency, new BigDecimal(amount),
                Instant.parse(operationPaidAt), Instant.parse(refundedAt), false, true,
                reimbursed ? Instant.parse(refundedAt) : null, null, null);
    }

    private RefundRow refundOfPayment(UUID payment, String amount, String gross, String net, String operationPaidAt,
                                      String refundedAt, String reimbursedAt) {
        return new RefundRow(Dac7Source.PAYMENT, payment, french, "EUR", new BigDecimal(amount),
                Instant.parse(operationPaidAt), Instant.parse(refundedAt), false, true,
                reimbursedAt == null ? null : Instant.parse(reimbursedAt), new BigDecimal(gross), new BigDecimal(net));
    }

    private TravelerReport traveler(Dac7Report report) {
        return report.travelers().stream().filter(t -> t.travelerId().equals(french)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("XOF seul : déclaré en XOF, sans aucune conversion ni anomalie de taux")
    void xofSeul_resteEnXofSansConversion() {
        // Aucun taux XOF connu : la déclaration n'en a pas besoin.
        Dac7ReportService noRates = new Dac7ReportService(readModel, policy, new MapExchangeRateProvider());
        op("XOF", "65000", "2026-05-10T10:00:00Z");
        op("XOF", "13000", "2026-05-20T10:00:00Z");

        Dac7Report report = noRates.annualReport(YEAR);

        TravelerReport t = traveler(report);
        assertThat(t.currency()).isEqualTo("XOF");
        QuarterReport q2 = t.quarters().get(1);
        assertThat(q2.net()).isEqualByComparingTo("78000.00");
        assertThat(q2.declaredComplete()).isTrue();
        assertThat(q2.operations()).isEqualTo(2);
        assertThat(report.anomalies()).extracting(Dac7Report.Anomaly::type)
                .doesNotContain(AnomalyType.EXCHANGE_RATE_MISSING);
    }

    @Test
    @DisplayName("EUR et XOF dans l'année : tout en euros, au taux du jour de chaque opération")
    void devisesMelangees_toutEnEuro() {
        op("EUR", "100.00", "2026-02-10T10:00:00Z");
        op("XOF", "65595.70", "2026-05-10T10:00:00Z");

        TravelerReport t = traveler(service.annualReport(YEAR));

        assertThat(t.currency()).isEqualTo("EUR");
        assertThat(t.quarters().get(0).net()).isEqualByComparingTo("100.00");
        assertThat(t.quarters().get(1).net()).isEqualByComparingTo("100.00");
        assertThat(t.quarters().get(1).declaredComplete()).isTrue();
    }

    @Test
    @DisplayName("frais retenus : le déclaré est le net reçu, la commission de Yadony ne le change pas, même inconnue")
    void declare_egalNetRecu_commissionInconnueSansEffet() {
        op("STRIPE", "EUR", "95.00", "5.00", "2026-02-10T10:00:00Z", false);
        op("CASH", "EUR", "40.00", null, "2026-02-12T10:00:00Z", false);

        QuarterReport q1 = traveler(service.annualReport(YEAR)).quarters().get(0);

        assertThat(q1.net()).isEqualByComparingTo("135.00");
        assertThat(q1.commissionComplete()).isFalse();
        assertThat(q1.declaredComplete()).isTrue();
    }

    @Test
    @DisplayName("remboursement repris au voyageur la même année : déduit du trimestre de l'opération")
    void remboursementMemeAnnee_repris_deduit() {
        UUID payment = op("EUR", "100.00", "2026-04-10T10:00:00Z");
        refunds.add(refund(payment, "EUR", "30.00", "2026-04-10T10:00:00Z", "2026-09-01T08:00:00Z", true));

        Dac7Report report = service.annualReport(YEAR);

        TravelerReport t = traveler(report);
        assertThat(t.quarters().get(1).net()).isEqualByComparingTo("70.00");
        assertThat(t.quarters().get(1).byCurrency().get(0).net()).isEqualByComparingTo("70.00");
        assertThat(t.quarters().get(2).net()).isEqualByComparingTo("0");
        assertThat(report.refundsToReconcile()).singleElement().satisfies(r -> {
            assertThat(r.treatment()).isEqualTo(RefundTreatment.DEDUCTED);
            assertThat(r.deductedAmount()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    @DisplayName("remboursement l'année suivante : année de l'opération à rectifier, rien n'est déduit")
    void remboursementAnneeSuivante_aRectifier() {
        refunds.add(refund(UUID.randomUUID(), "EUR", "30.00", "2025-12-20T10:00:00Z", "2026-01-05T10:00:00Z", true));
        op("EUR", "50.00", "2026-03-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);

        assertThat(traveler(report).quarters().get(0).net()).isEqualByComparingTo("50.00");
        assertThat(report.refundsToReconcile()).singleElement().satisfies(r -> {
            assertThat(r.treatment()).isEqualTo(RefundTreatment.TO_RECTIFY);
            assertThat(r.operationYear()).isEqualTo(2025);
            assertThat(r.refundYear()).isEqualTo(2026);
            assertThat(r.deductedAmount()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("le voyageur garde l'argent (Yadony rembourse l'expéditeur sans reprise) : rien n'est déduit")
    void voyageurGardeArgent_rienDeduit() {
        UUID payment = op("EUR", "100.00", "2026-04-10T10:00:00Z");
        refunds.add(refund(payment, "EUR", "30.00", "2026-04-10T10:00:00Z", "2026-09-01T08:00:00Z", false));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(traveler(report).quarters().get(1).net()).isEqualByComparingTo("100.00");
        assertThat(report.refundsToReconcile()).singleElement().satisfies(r -> {
            assertThat(r.treatment()).isEqualTo(RefundTreatment.KEPT_BY_TRAVELER);
            assertThat(r.deductedAmount()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("remboursement en XOF repris, voyageur déclaré en euros : converti au taux de l'opération")
    void remboursementRepris_convertiEnEuro() {
        op("EUR", "100.00", "2026-04-09T10:00:00Z");
        UUID payment = op("XOF", "65595.70", "2026-04-10T10:00:00Z");
        refunds.add(refund(payment, "XOF", "6559.57", "2026-04-10T10:00:00Z", "2026-04-20T08:00:00Z", true));

        Dac7Report report = service.annualReport(YEAR);

        // 100 + 100 (XOF converti) - 10 (remboursement converti) = 190 euros.
        assertThat(traveler(report).quarters().get(1).net()).isEqualByComparingTo("190.00");
        assertThat(report.refundsToReconcile().get(0).deductedAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("déduction impossible (taux manquant ou opération absente) : rien déduit, anomalie, à rectifier")
    void deductionImpossible_anomalie() {
        op("EUR", "100.00", "2026-04-09T10:00:00Z");
        UUID payment = op("XOF", "6559.57", "2026-04-10T10:00:00Z");
        Dac7ReportService noRates = new Dac7ReportService(readModel, policy, new MapExchangeRateProvider());
        refunds.add(refund(payment, "XOF", "6559.57", "2026-04-10T10:00:00Z", "2026-04-20T08:00:00Z", true));

        Dac7Report report = noRates.annualReport(YEAR);

        assertThat(report.refundsToReconcile()).singleElement()
                .satisfies(r -> assertThat(r.treatment()).isEqualTo(RefundTreatment.TO_RECTIFY));
        assertThat(report.anomalies()).extracting(Dac7Report.Anomaly::type)
                .contains(AnomalyType.REFUND_DEDUCTION_UNAVAILABLE);

        // Remboursement repris dont l'opération n'est pas dans le rapport de l'année : même traitement.
        operations.clear();
        refunds.clear();
        refunds.add(refund(UUID.randomUUID(), "EUR", "5.00", "2026-04-10T10:00:00Z", "2026-04-20T08:00:00Z", true));
        Dac7Report orphan = service.annualReport(YEAR);
        assertThat(orphan.refundsToReconcile()).singleElement()
                .satisfies(r -> assertThat(r.treatment()).isEqualTo(RefundTreatment.TO_RECTIFY));
        assertThat(orphan.anomalies()).extracting(Dac7Report.Anomaly::type)
                .contains(AnomalyType.REFUND_DEDUCTION_UNAVAILABLE);
    }

    @Test
    @DisplayName("espèces datées de la livraison faute de remise confirmée : repli tracé (anomalie non bloquante)")
    void especes_repliLivraison_tracé() {
        UUID cash = op("CASH", "EUR", "40.00", "2.00", "2026-06-30T23:59:59Z", true);

        Dac7Report report = service.annualReport(YEAR);

        assertThat(traveler(report).quarters().get(1).net()).isEqualByComparingTo("40.00");
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.CASH_DATE_FROM_DELIVERY);
            assertThat(a.sourceId()).isEqualTo(cash);
        });
    }

    @Test
    @DisplayName("remboursement total avec commission : seul le net reçu par le voyageur est déduit (88, pas 100)")
    void remboursementTotal_deduitLeNetSeulement() {
        UUID payment = op("STRIPE", "EUR", "88.00", "12.00", "2026-04-10T10:00:00Z", false);
        refunds.add(refundOfPayment(payment, "100.00", "100.00", "88.00", "2026-04-10T10:00:00Z",
                "2026-09-01T08:00:00Z", "2026-09-01T09:00:00Z"));

        Dac7Report report = service.annualReport(YEAR);

        // 88 − 88 = 0, jamais −12.
        assertThat(traveler(report).quarters().get(1).net()).isEqualByComparingTo("0");
        assertThat(report.refundsToReconcile().get(0).deductedAmount()).isEqualByComparingTo("88.00");
        assertThat(report.refundsToReconcile().get(0).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("remboursement partiel avec commission : déduit au prorata du net (25 sur 100 → 22 sur 88)")
    void remboursementPartiel_auProrata() {
        UUID payment = op("STRIPE", "EUR", "88.00", "12.00", "2026-04-10T10:00:00Z", false);
        refunds.add(refundOfPayment(payment, "25.00", "100.00", "88.00", "2026-04-10T10:00:00Z",
                "2026-09-01T08:00:00Z", "2026-09-01T08:00:00Z"));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(traveler(report).quarters().get(1).net()).isEqualByComparingTo("66.00");
        assertThat(report.refundsToReconcile().get(0).deductedAmount()).isEqualByComparingTo("22.00");
    }

    @Test
    @DisplayName("remboursé en décembre mais argent repris en janvier : jugé sur la date de la reprise, à rectifier")
    void repriseAnneeSuivante_aRectifier() {
        UUID payment = op("EUR", "100.00", "2026-11-10T10:00:00Z");
        refunds.add(refundOfPayment(payment, "30.00", "30.00", "30.00", "2026-11-10T10:00:00Z",
                "2026-12-28T08:00:00Z", "2027-01-03T09:00:00Z"));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(traveler(report).quarters().get(3).net()).isEqualByComparingTo("100.00");
        assertThat(report.refundsToReconcile().get(0).treatment()).isEqualTo(RefundTreatment.TO_RECTIFY);
    }

    @Test
    @DisplayName("colis espèces remis dans l'année mais pas encore livré : avertissement, jamais compté")
    void especesRemisesNonTerminees_avertissement() {
        UUID bid = UUID.randomUUID();
        when(readModel.cashInFlight(FROM, TO)).thenReturn(List.of(new Dac7ReportReadModel.CashInFlightRow(bid, french,
                "EUR", Instant.parse("2026-12-28T10:00:00Z"))));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(report.travelers()).isEmpty();
        assertThat(report.anomalies()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.CASH_NOT_COMPLETED_YET);
            assertThat(a.sourceId()).isEqualTo(bid);
            assertThat(a.day()).isEqualTo(java.time.LocalDate.of(2026, 12, 28));
        });
    }

    @Test
    @DisplayName("la devise déclarée : une seule reçue, sinon l'euro ; jamais de devise nulle")
    void devisesRecues() {
        assertThat(Dac7ReportService.reportingCurrency(Set.of("XOF"))).isEqualTo("XOF");
        assertThat(Dac7ReportService.reportingCurrency(Set.of("XOF", "EUR"))).isEqualTo("EUR");
        assertThat(Dac7ReportService.reportingCurrency(Set.of())).isEqualTo("EUR");
        assertThat(Dac7ReportService.reportingCurrency(null)).isEqualTo("EUR");
    }
}
