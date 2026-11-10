package com.yadony.api.dac7;

import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Report.CurrencyAmounts;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import com.yadony.api.dac7.Dac7ReportReadModel.OperationRow;
import com.yadony.api.dac7.Dac7ReportReadModel.PendingSplitRow;
import com.yadony.api.dac7.Dac7ReportReadModel.RefundRow;
import com.yadony.api.dac7.Dac7ReportReadModel.TravelerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Dac7ReportServiceTest {

    private static final int YEAR = 2026;
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    private final Dac7ReportReadModel readModel = mock(Dac7ReportReadModel.class);
    private final Dac7ResidencyPolicy policy = new Dac7ResidencyPolicy(new Dac7Properties(false, null));
    private final MapExchangeRateProvider rates = new MapExchangeRateProvider().everyDay("XOF", "655.957");
    private final Dac7ReportService service = new Dac7ReportService(readModel, policy, rates);

    private final List<OperationRow> operations = new ArrayList<>();
    private final List<RefundRow> refunds = new ArrayList<>();
    private final List<TravelerRow> travelers = new ArrayList<>();
    private final List<PendingSplitRow> pendingSplits = new ArrayList<>();

    private final UUID french = UUID.randomUUID();
    private final UUID ivorian = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(readModel.operations(FROM, TO)).thenReturn(operations);
        when(readModel.refunds(FROM, TO)).thenReturn(refunds);
        when(readModel.pendingSplits()).thenReturn(pendingSplits);
        when(readModel.travelers(any())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(0);
            return travelers.stream().filter(t -> ids.contains(t.travelerId())).toList();
        });
        travelers.add(new TravelerRow(french, "FR", "INDIVIDUAL", null, true, true));
        travelers.add(new TravelerRow(ivorian, "CI", "INDIVIDUAL", null, false, true));
    }

    private UUID op(UUID traveler, Dac7Source source, String currency, String net, String commission, String paidAt) {
        UUID id = UUID.randomUUID();
        operations.add(new OperationRow(source, id, traveler, source == Dac7Source.CASH ? "CASH" : "STRIPE", currency,
                new BigDecimal(net), commission == null ? null : new BigDecimal(commission), Instant.parse(paidAt),
                source == Dac7Source.PAYMENT || source == Dac7Source.CASH, false));
        return id;
    }

    private TravelerReport only(Dac7Report report, UUID traveler) {
        return report.travelers().stream().filter(t -> t.travelerId().equals(traveler)).findFirst().orElseThrow();
    }

    private static CurrencyAmounts currency(QuarterReport quarter, String code) {
        return quarter.byCurrency().stream().filter(c -> c.currency().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void residentHorsPerimetre_exclu_residentFrance_inclus() {
        op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-05-10T10:00:00Z");
        op(ivorian, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-05-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);

        assertThat(report.year()).isEqualTo(YEAR);
        assertThat(report.travelers()).extracting(TravelerReport::travelerId).containsExactly(french);
        assertThat(report.nonDeclarableTravelers()).containsExactly(ivorian);
        assertThat(only(report, french).residenceCountry()).isEqualTo("FR");
        assertThat(only(report, french).taxInfoProvided()).isTrue();
    }

    @Test
    void uneSeuleMission_suffit_quatreTrimestresToujoursPresents() {
        op(french, Dac7Source.PAYMENT, "EUR", "19.00", "1.00", "2026-02-01T08:00:00Z");

        TravelerReport traveler = only(service.annualReport(YEAR), french);

        assertThat(traveler.quarters()).extracting(QuarterReport::quarter).containsExactly(1, 2, 3, 4);
        assertThat(traveler.quarters().get(0).operations()).isEqualTo(1);
        assertThat(traveler.quarters().get(0).netEur()).isEqualByComparingTo("19.00");
        assertThat(traveler.quarters().get(1).operations()).isZero();
        assertThat(traveler.quarters().get(1).netEur()).isEqualByComparingTo("0");
        assertThat(traveler.quarters().get(1).byCurrency()).isEmpty();
    }

    @Test
    void commission_deduiteDuNetEtDeclareeAPart() {
        op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-05-10T10:00:00Z");

        QuarterReport q2 = only(service.annualReport(YEAR), french).quarters().get(1);

        assertThat(q2.netEur()).isEqualByComparingTo("95.00");
        assertThat(q2.commissionEur()).isEqualByComparingTo("5.00");
        assertThat(currency(q2, "EUR").net()).isEqualByComparingTo("95.00");
        assertThat(currency(q2, "EUR").commission()).isEqualByComparingTo("5.00");
    }

    @Test
    void especesIncluses() {
        op(french, Dac7Source.CASH, "EUR", "40.00", "2.00", "2026-07-03T12:00:00Z");

        QuarterReport q3 = only(service.annualReport(YEAR), french).quarters().get(2);

        assertThat(q3.operations()).isEqualTo(1);
        assertThat(q3.netEur()).isEqualByComparingTo("40.00");
        assertThat(q3.commissionEur()).isEqualByComparingTo("2.00");
    }

    @Test
    void deuxDevises_parDeviseDOriginePuisEnEuros() {
        op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-05-10T10:00:00Z");
        op(french, Dac7Source.CASH, "XOF", "65595.70", "3279.79", "2026-05-11T10:00:00Z");

        QuarterReport q2 = only(service.annualReport(YEAR), french).quarters().get(1);

        assertThat(q2.operations()).isEqualTo(2);
        assertThat(q2.byCurrency()).extracting(CurrencyAmounts::currency).containsExactly("EUR", "XOF");
        assertThat(currency(q2, "XOF").net()).isEqualByComparingTo("65595.70");
        // 65 595,70 F CFA = 100,00 € ; 3 279,79 F CFA = 5,00 € (parité fixe 655,957).
        assertThat(q2.netEur()).isEqualByComparingTo("195.00");
        assertThat(q2.commissionEur()).isEqualByComparingTo("10.00");
        assertThat(q2.eurComplete()).isTrue();
    }

    @Test
    void conversionAuTauxDuJourDuVersement() {
        MapExchangeRateProvider dated = new MapExchangeRateProvider()
                .on(LocalDate.of(2026, 5, 10), "USD", "1.25")
                .on(LocalDate.of(2026, 5, 11), "USD", "1.00");
        Dac7ReportService withDatedRates = new Dac7ReportService(readModel, policy, dated);
        op(french, Dac7Source.PAYMENT, "USD", "100.00", "0.00", "2026-05-10T23:59:59Z");
        op(french, Dac7Source.PAYMENT, "USD", "100.00", "0.00", "2026-05-11T00:00:00Z");

        QuarterReport q2 = only(withDatedRates.annualReport(YEAR), french).quarters().get(1);

        assertThat(q2.netEur()).isEqualByComparingTo("180.00");
    }

    @Test
    void tauxInconnu_euroIncompletEtAnomalie_jamaisDevine() {
        UUID id = op(french, Dac7Source.PAYMENT, "GBP", "50.00", "2.00", "2026-05-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);
        QuarterReport q2 = only(report, french).quarters().get(1);

        assertThat(q2.eurComplete()).isFalse();
        assertThat(q2.netEur()).isEqualByComparingTo("0");
        assertThat(currency(q2, "GBP").net()).isEqualByComparingTo("50.00");
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.EXCHANGE_RATE_MISSING);
            assertThat(a.sourceId()).isEqualTo(id);
            assertThat(a.currency()).isEqualTo("GBP");
            assertThat(a.day()).isEqualTo(LocalDate.of(2026, 5, 10));
        });
    }

    @Test
    void trimestresAuxFrontieresUtc() {
        op(french, Dac7Source.PAYMENT, "EUR", "10.00", "1.00", "2026-03-31T23:59:59Z");
        op(french, Dac7Source.PAYMENT, "EUR", "20.00", "1.00", "2026-04-01T00:00:00Z");
        op(french, Dac7Source.PAYMENT, "EUR", "30.00", "1.00", "2026-12-31T23:59:59Z");

        List<QuarterReport> quarters = only(service.annualReport(YEAR), french).quarters();

        assertThat(quarters.get(0).netEur()).isEqualByComparingTo("10.00");
        assertThat(quarters.get(1).netEur()).isEqualByComparingTo("20.00");
        assertThat(quarters.get(2).operations()).isZero();
        assertThat(quarters.get(3).netEur()).isEqualByComparingTo("30.00");
    }

    @Test
    void supplement_ajouteAuMontantSansCompterUneOperation() {
        op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-05-10T10:00:00Z");
        op(french, Dac7Source.SUPPLEMENT, "EUR", "9.50", "0.50", "2026-05-10T11:00:00Z");

        QuarterReport q2 = only(service.annualReport(YEAR), french).quarters().get(1);

        assertThat(q2.operations()).isEqualTo(1);
        assertThat(q2.netEur()).isEqualByComparingTo("104.50");
        assertThat(q2.commissionEur()).isEqualByComparingTo("5.50");
    }

    private RefundRow refund(UUID source, UUID traveler, String amount, String operationNet, String operationPaidAt,
                             String refundedAt, boolean estimated, boolean afterRelease) {
        return new RefundRow(Dac7Source.PAYMENT, source, traveler, "EUR", new BigDecimal(amount),
                new BigDecimal(operationNet), Instant.parse(operationPaidAt), Instant.parse(refundedAt), estimated,
                afterRelease);
    }

    @Test
    void remboursementAvantVersementMemeAnnee_deduitDuTrimestreDuVersement() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-04-10T10:00:00Z");
        // Remboursé en partie le 25/03 (en séquestre), versé le 10/04 : c'est le deuxième trimestre qui baisse.
        refunds.add(refund(payment, french, "30.00", "95.00", "2026-04-10T10:00:00Z", "2026-03-25T10:00:00Z",
                false, false));

        Dac7Report report = service.annualReport(YEAR);
        List<QuarterReport> quarters = only(report, french).quarters();

        assertThat(quarters.get(1).netEur()).isEqualByComparingTo("65.00");
        assertThat(quarters.get(1).refundsDeductedEur()).isEqualByComparingTo("30.00");
        assertThat(quarters.get(1).operations()).isEqualTo(1);
        assertThat(currency(quarters.get(1), "EUR").refundsDeducted()).isEqualByComparingTo("30.00");
        assertThat(quarters.get(1).commissionEur()).isEqualByComparingTo("5.00");
        assertThat(quarters.get(0).netEur()).isEqualByComparingTo("0");
        assertThat(report.outOfYearRefunds()).isEmpty();
        assertThat(report.refundsToReconcile()).isEmpty();
    }

    @Test
    void remboursementAvantVersement_plafonneAuNetDeLOperation() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-02-10T10:00:00Z");
        refunds.add(refund(payment, french, "100.00", "95.00", "2026-02-10T10:00:00Z", "2026-02-01T10:00:00Z",
                false, false));

        QuarterReport q1 = only(service.annualReport(YEAR), french).quarters().get(0);

        assertThat(q1.netEur()).isEqualByComparingTo("0.00");
        assertThat(q1.refundsDeductedEur()).isEqualByComparingTo("95.00");
    }

    @Test
    void remboursementApresVersement_jamaisDeduit_aReconcilier() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "47.50", "2.50", "2026-06-10T12:00:00Z");
        refunds.add(refund(payment, french, "10.00", "47.50", "2026-06-10T12:00:00Z", "2026-09-01T08:00:00Z",
                false, true));

        Dac7Report report = service.annualReport(YEAR);

        // Le voyageur a gardé 47,50 : la contrepartie déclarée ne baisse pas.
        assertThat(only(report, french).quarters().get(1).netEur()).isEqualByComparingTo("47.50");
        assertThat(only(report, french).quarters().get(1).refundsDeductedEur()).isEqualByComparingTo("0");
        assertThat(report.outOfYearRefunds()).isEmpty();
        assertThat(report.refundsToReconcile()).singleElement().satisfies(r -> {
            assertThat(r.sourceId()).isEqualTo(payment);
            assertThat(r.travelerId()).isEqualTo(french);
            assertThat(r.amount()).isEqualByComparingTo("10.00");
            assertThat(r.operationPaidAt()).isEqualTo(Instant.parse("2026-06-10T12:00:00Z"));
            assertThat(r.refundedAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
            assertThat(r.refundDateEstimated()).isFalse();
            assertThat(r.amountEur()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void remboursementsPartielsSurDeuxAnnees_chacunASaDate() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-12-01T10:00:00Z");
        refunds.add(refund(payment, french, "10.00", "95.00", "2026-12-01T10:00:00Z", "2026-12-20T10:00:00Z",
                false, true));
        refunds.add(refund(payment, french, "5.00", "95.00", "2026-12-01T10:00:00Z", "2027-01-05T10:00:00Z",
                false, true));

        assertThat(service.annualReport(YEAR).refundsToReconcile())
                .extracting(r -> r.refundedAt().toString(), r -> r.amount().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-12-20T10:00:00Z", "10.00"),
                        org.assertj.core.groups.Tuple.tuple("2027-01-05T10:00:00Z", "5.00"));
    }

    @Test
    void remboursementAvantVersementDUneAutreAnnee_listeAPart() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-01-05T10:00:00Z");
        refunds.add(refund(payment, french, "30.00", "95.00", "2026-01-05T10:00:00Z", "2025-12-30T10:00:00Z",
                false, false));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(only(report, french).quarters().get(0).netEur()).isEqualByComparingTo("95.00");
        assertThat(report.outOfYearRefunds()).singleElement().satisfies(r -> {
            assertThat(r.sourceId()).isEqualTo(payment);
            assertThat(r.amount()).isEqualByComparingTo("30.00");
            assertThat(r.operationYear()).isEqualTo(2026);
            assertThat(r.refundYear()).isEqualTo(2025);
            assertThat(r.amountEur()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    void remboursementDUnVoyageurNonDeclarable_ignore() {
        UUID payment = op(ivorian, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-12-20T10:00:00Z");
        refunds.add(refund(payment, ivorian, "30.00", "95.00", "2026-12-20T10:00:00Z", "2027-01-05T10:00:00Z",
                false, true));
        refunds.add(refund(payment, ivorian, "5.00", "95.00", "2026-12-20T10:00:00Z", "2026-12-10T10:00:00Z",
                false, false));

        Dac7Report report = service.annualReport(YEAR);
        assertThat(report.outOfYearRefunds()).isEmpty();
        assertThat(report.refundsToReconcile()).isEmpty();
    }

    @Test
    void dateDeRemboursementEstimee_signalee() {
        UUID payment = op(french, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-02-10T10:00:00Z");
        refunds.add(refund(payment, french, "10.00", "95.00", "2026-02-10T10:00:00Z", "2026-02-11T10:00:00Z",
                true, true));

        Dac7Report report = service.annualReport(YEAR);
        assertThat(report.anomalies())
                .anySatisfy(a -> assertThat(a.type()).isEqualTo(AnomalyType.REFUND_DATE_ESTIMATED));
        assertThat(report.refundsToReconcile()).singleElement()
                .satisfies(r -> assertThat(r.refundDateEstimated()).isTrue());
    }

    @Test
    void commissionInconnue_jamaisZeroSilencieux() {
        UUID cash = op(french, Dac7Source.CASH, "EUR", "40.00", null, "2026-07-03T12:00:00Z");

        Dac7Report report = service.annualReport(YEAR);
        QuarterReport q3 = only(report, french).quarters().get(2);

        assertThat(q3.commissionComplete()).isFalse();
        assertThat(q3.commissionEur()).isEqualByComparingTo("0");
        assertThat(only(report, french).quarters().get(0).commissionComplete()).isTrue();
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.COMMISSION_UNKNOWN);
            assertThat(a.sourceId()).isEqualTo(cash);
        });
    }

    @Test
    void dateDeLivraisonEstimee_signalee() {
        UUID cash = UUID.randomUUID();
        operations.add(new OperationRow(Dac7Source.CASH, cash, french, "CASH", "EUR", new BigDecimal("40.00"),
                new BigDecimal("2.00"), Instant.parse("2026-07-03T12:00:00Z"), true, true));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(only(report, french).quarters().get(2).netEur()).isEqualByComparingTo("40.00");
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.DELIVERY_DATE_ESTIMATED);
            assertThat(a.sourceId()).isEqualTo(cash);
        });
    }

    @Test
    void partageAdminEnAttente_listeAPart() {
        UUID payment = UUID.randomUUID();
        pendingSplits.add(new PendingSplitRow(payment, french, "EUR", new BigDecimal("36.00"),
                new BigDecimal("4.00"), "CLAIMED", Instant.parse("2026-10-10T10:00:00Z")));
        pendingSplits.add(new PendingSplitRow(UUID.randomUUID(), ivorian, "EUR", new BigDecimal("10.00"),
                new BigDecimal("1.00"), "CLAIMED", Instant.parse("2026-10-10T10:00:00Z")));

        Dac7Report report = service.annualReport(YEAR);

        assertThat(report.pendingSplits()).singleElement().satisfies(p -> {
            assertThat(p.paymentId()).isEqualTo(payment);
            assertThat(p.travelerPayout()).isEqualByComparingTo("36.00");
            assertThat(p.status()).isEqualTo("CLAIMED");
        });
    }

    @Test
    void identiteIncomplete_compteSupprime_revueHumaineJamaisDeclareEnSilence() {
        UUID deleted = UUID.randomUUID();
        travelers.add(new TravelerRow(deleted, "FR", "INDIVIDUAL", null, true, false));
        op(deleted, Dac7Source.PAYMENT, "EUR", "95.00", "5.00", "2026-03-10T10:00:00Z");
        op(french, Dac7Source.PAYMENT, "EUR", "10.00", "1.00", "2026-03-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);

        assertThat(report.travelers()).extracting(TravelerReport::travelerId).containsExactly(french);
        assertThat(only(report, french).identityStatus()).isEqualTo(Dac7IdentityStatus.COMPLETE);
        assertThat(report.identityReviewTravelers()).singleElement().satisfies(t -> {
            assertThat(t.travelerId()).isEqualTo(deleted);
            assertThat(t.identityStatus()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
            assertThat(t.quarters().get(0).netEur()).isEqualByComparingTo("95.00");
        });
    }

    @Test
    void codesPartenairesInvalides_remontesDansLeRapport() {
        Dac7ReportService withBadConfig = new Dac7ReportService(readModel,
                new Dac7ResidencyPolicy(new Dac7Properties(false, List.of("CIV"))), rates);
        assertThat(withBadConfig.annualReport(YEAR).invalidPartnerJurisdictions()).containsExactly("CIV");
        assertThat(service.annualReport(YEAR).invalidPartnerJurisdictions()).isEmpty();
    }

    @Test
    void residenceInconnue_listeePourDiligence() {
        UUID unknown = UUID.randomUUID();
        travelers.add(new TravelerRow(unknown, null, null, null, false, true));
        op(unknown, Dac7Source.PAYMENT, "EUR", "10.00", "1.00", "2026-05-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);

        assertThat(report.unknownResidenceTravelers()).containsExactly(unknown);
        assertThat(report.travelers()).isEmpty();
    }

    @Test
    void voyageurIntrouvable_signale() {
        operations.add(new OperationRow(Dac7Source.PAYMENT, UUID.randomUUID(), null, "STRIPE", "EUR",
                new BigDecimal("10.00"), new BigDecimal("1.00"), Instant.parse("2026-05-10T10:00:00Z"), true, false));

        assertThat(service.annualReport(YEAR).anomalies())
                .anySatisfy(a -> assertThat(a.type()).isEqualTo(AnomalyType.TRAVELER_UNKNOWN));
    }

    @Test
    void professionnel_paysDeLEntreprise() {
        UUID pro = UUID.randomUUID();
        travelers.add(new TravelerRow(pro, "CI", "PROFESSIONAL", "BE", true, true));
        op(pro, Dac7Source.PAYMENT, "EUR", "10.00", "1.00", "2026-05-10T10:00:00Z");

        Dac7Report report = service.annualReport(YEAR);

        assertThat(only(report, pro).residenceCountry()).isEqualTo("BE");
    }
}
