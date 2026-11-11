package com.yadony.api.dac7;

import com.yadony.api.dac7.Dac7Report.Anomaly;
import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Report.CurrencyAmounts;
import com.yadony.api.dac7.Dac7Report.PendingSplit;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.RefundToReconcile;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import com.yadony.api.dac7.Dac7ReportReadModel.OperationRow;
import com.yadony.api.dac7.Dac7ReportReadModel.PendingSplitRow;
import com.yadony.api.dac7.Dac7ReportReadModel.RefundRow;
import com.yadony.api.dac7.Dac7ReportReadModel.TravelerRow;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Calcul DAC7 d'une année civile (lot 3a), par voyageur DÉCLARABLE et par trimestre civil (UTC) : contrepartie
 * versée nette de la commission de Yadony, commission séparée, nombre d'opérations, par devise d'origine puis en
 * euros au taux de référence du jour du versement. Tous les rails : carte, mobile money, espèces.
 *
 * <p>Règles (hypothèses H4 à H8 de la conception provisoire, et décisions de la relecture n° 1, à confirmer par
 * l'expert-comptable) :
 * <ul>
 *   <li>aucun seuil : une seule opération rend le voyageur déclarable ;</li>
 *   <li>date retenue : versement pour la carte et le mobile money, fin du partage admin, livraison pour les espèces
 *       (repli sur la dernière mise à jour du colis signalé, jamais silencieux) ;</li>
 *   <li>aucun remboursement n'est jamais déduit, ni avant ni après le versement : la contrepartie reste le montant
 *       versé au voyageur (la livraison et la libération forcée refusent de verser un paiement déjà remboursé, et tout
 *       versement part du montant entier). Chaque remboursement daté de l'année est listé à réconcilier, une seule fois,
 *       avec l'année de l'opération ;</li>
 *   <li>un partage admin dont le transfert n'est pas terminé n'est pas compté : il est listé en attente ; tranché à 0
 *       pour le voyageur, ce n'est pas une opération ;</li>
 *   <li>un supplément ou une modification ajoute au montant sans compter une opération de plus ;</li>
 *   <li>un voyageur à l'identité incomplète (compte supprimé, donnée manquante) n'est jamais déclaré en silence : il
 *       sort dans une liste à revue humaine ;</li>
 *   <li>un taux inconnu ou une commission introuvable ne sont jamais devinés : la ligne est signalée et le trimestre
 *       marqué incomplet.</li>
 * </ul>
 * Aucun endpoint : l'export (lot 3b) et l'écran admin (lot 3c) l'appelleront.
 */
@Service
public class Dac7ReportService {

    private static final int EUR_SCALE = 2;
    private static final int RATE_SCALE = 10;

    private final Dac7ReportReadModel readModel;
    private final Dac7ResidencyPolicy residency;
    private final ExchangeRateProvider rates;

    public Dac7ReportService(Dac7ReportReadModel readModel, Dac7ResidencyPolicy residency, ExchangeRateProvider rates) {
        this.readModel = readModel;
        this.residency = residency;
        this.rates = rates;
    }

    /** Une seule photographie de la base pour les trois lectures (lecture répétable, sans écriture). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dac7Report annualReport(int year) {
        Instant from = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<OperationRow> operations = readModel.operations(from, to);
        List<RefundRow> refunds = readModel.refunds(from, to);

        Set<UUID> ids = new LinkedHashSet<>();
        operations.stream().map(OperationRow::travelerId).filter(java.util.Objects::nonNull).forEach(ids::add);
        refunds.stream().map(RefundRow::travelerId).filter(java.util.Objects::nonNull).forEach(ids::add);
        Map<UUID, TravelerRow> facts = new HashMap<>();
        readModel.travelers(ids).forEach(t -> facts.put(t.travelerId(), t));

        List<Anomaly> anomalies = new ArrayList<>();
        Map<UUID, Dac7ResidencyStatus> statuses = new HashMap<>();
        Map<UUID, String> countries = new HashMap<>();
        Map<UUID, Dac7IdentityStatus> identities = new HashMap<>();
        for (UUID id : ids) {
            TravelerRow row = facts.get(id);
            String country = row == null ? null
                    : Dac7ResidencyPolicy.residenceCountryOf(row.frozenResidenceCountry(), row.country(),
                    travelerStatus(row.travelerStatus()), row.businessCountry());
            countries.put(id, country);
            statuses.put(id, residency.statusOf(country));
            identities.put(id, row != null && row.identityComplete()
                    ? Dac7IdentityStatus.COMPLETE : Dac7IdentityStatus.INCOMPLETE);
        }

        Map<UUID, TravelerAcc> declarable = new TreeMap<>();
        Set<UUID> unknown = new java.util.TreeSet<>();
        Set<UUID> nonDeclarable = new java.util.TreeSet<>();
        for (OperationRow op : operations) {
            if (op.travelerId() == null) {
                anomalies.add(new Anomaly(AnomalyType.TRAVELER_UNKNOWN, null, op.source(), op.sourceId(),
                        op.currency(), day(op.paidAt())));
                continue;
            }
            Dac7ResidencyStatus status = statuses.get(op.travelerId());
            if (status == Dac7ResidencyStatus.RESIDENCE_INCONNUE) {
                unknown.add(op.travelerId());
                continue;
            }
            if (status == Dac7ResidencyStatus.NON_DECLARABLE) {
                nonDeclarable.add(op.travelerId());
                continue;
            }
            TravelerRow row = facts.get(op.travelerId());
            if (op.dateEstimated()) {
                anomalies.add(new Anomaly(AnomalyType.DELIVERY_DATE_ESTIMATED, op.travelerId(), op.source(),
                        op.sourceId(), op.currency(), day(op.paidAt())));
            }
            declarable.computeIfAbsent(op.travelerId(), id -> new TravelerAcc(id, countries.get(id),
                            row != null && row.taxInfoProvided(), identities.get(id)))
                    .quarter(op.paidAt()).add(op, rate(op.currency(), op.paidAt()), anomalies);
        }

        List<RefundToReconcile> toReconcile = new ArrayList<>();
        for (RefundRow refund : refunds) {
            if (refund.travelerId() == null
                    || statuses.get(refund.travelerId()) != Dac7ResidencyStatus.DECLARABLE) {
                continue;
            }
            if (refund.refundDateEstimated()) {
                anomalies.add(new Anomaly(AnomalyType.REFUND_DATE_ESTIMATED, refund.travelerId(), refund.source(),
                        refund.sourceId(), refund.currency(), day(refund.refundedAt())));
            }
            // Jamais déduit : la contrepartie reste le montant versé. À réconcilier à la main.
            Optional<BigDecimal> rate = rate(refund.currency(), refund.operationPaidAt())
                    .map(ExchangeRateProvider.Rate::unitsPerEur);
            toReconcile.add(new RefundToReconcile(refund.travelerId(), refund.source(), refund.sourceId(),
                    refund.currency(), refund.amount(), refund.operationPaidAt(), year(refund.operationPaidAt()),
                    refund.refundedAt(), year(refund.refundedAt()), !refund.afterRelease(),
                    refund.refundDateEstimated(), rate.map(r -> toEur(refund.amount(), r)).orElse(null)));
        }

        List<PendingSplit> pendingSplits = new ArrayList<>();
        List<PendingSplitRow> splitRows = readModel.pendingSplits();
        Set<UUID> splitTravelers = new LinkedHashSet<>();
        splitRows.stream().map(PendingSplitRow::travelerId).filter(java.util.Objects::nonNull)
                .filter(id -> !statuses.containsKey(id)).forEach(splitTravelers::add);
        if (!splitTravelers.isEmpty()) {
            readModel.travelers(splitTravelers).forEach(t -> statuses.put(t.travelerId(), residency.statusOf(
                    Dac7ResidencyPolicy.residenceCountryOf(t.frozenResidenceCountry(), t.country(),
                            travelerStatus(t.travelerStatus()), t.businessCountry()))));
        }
        for (PendingSplitRow split : splitRows) {
            if (split.travelerId() != null && statuses.get(split.travelerId()) == Dac7ResidencyStatus.DECLARABLE) {
                pendingSplits.add(new PendingSplit(split.travelerId(), split.paymentId(), split.currency(),
                        split.travelerPayout(), split.commission(), split.status(), split.decidedAt()));
            }
        }

        List<TravelerReport> complete = new ArrayList<>();
        List<TravelerReport> review = new ArrayList<>();
        for (TravelerAcc acc : declarable.values()) {
            (acc.identity == Dac7IdentityStatus.COMPLETE ? complete : review).add(acc.report());
        }
        return new Dac7Report(year, complete, review, toReconcile, pendingSplits, List.copyOf(unknown),
                List.copyOf(nonDeclarable), residency.invalidPartnerJurisdictions(), anomalies);
    }

    private Optional<ExchangeRateProvider.Rate> rate(String currency, Instant at) {
        return rates.rate(currency, day(at));
    }

    private static BigDecimal toEur(BigDecimal amount, BigDecimal unitsPerEur) {
        return amount.divide(unitsPerEur, RATE_SCALE, RoundingMode.HALF_UP).setScale(EUR_SCALE, RoundingMode.HALF_UP);
    }

    private static LocalDate day(Instant at) {
        return at == null ? null : at.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static int year(Instant at) {
        return at.atZone(ZoneOffset.UTC).getYear();
    }

    private static TravelerStatus travelerStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TravelerStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Accumulateurs ────────────────────────────────────────────────────────

    private static final class TravelerAcc {
        private final UUID travelerId;
        private final String country;
        private final boolean taxInfoProvided;
        private final Dac7IdentityStatus identity;
        private final QuarterAcc[] quarters = {new QuarterAcc(1), new QuarterAcc(2), new QuarterAcc(3),
                new QuarterAcc(4)};

        TravelerAcc(UUID travelerId, String country, boolean taxInfoProvided, Dac7IdentityStatus identity) {
            this.travelerId = travelerId;
            this.country = country;
            this.taxInfoProvided = taxInfoProvided;
            this.identity = identity == null ? Dac7IdentityStatus.INCOMPLETE : identity;
        }

        /** Trimestre civil UTC : 31/03 23:59:59 est au 1er, 01/04 00:00:00 au 2e. */
        QuarterAcc quarter(Instant at) {
            return quarters[(at.atZone(ZoneOffset.UTC).getMonthValue() - 1) / 3];
        }

        TravelerReport report() {
            List<QuarterReport> list = new ArrayList<>();
            for (QuarterAcc q : quarters) {
                list.add(q.report());
            }
            return new TravelerReport(travelerId, country, taxInfoProvided, identity, list);
        }
    }

    private static final class QuarterAcc {
        private final int quarter;
        private final Map<String, CurrencyAcc> byCurrency = new TreeMap<>();
        private int operations;
        // Euros à pleine précision, arrondis une seule fois au rendu.
        private BigDecimal netEur = BigDecimal.ZERO;
        private BigDecimal commissionEur = BigDecimal.ZERO;
        private boolean eurComplete = true;
        private boolean commissionComplete = true;

        QuarterAcc(int quarter) {
            this.quarter = quarter;
        }

        void add(OperationRow op, Optional<ExchangeRateProvider.Rate> rate, List<Anomaly> anomalies) {
            BigDecimal commission = op.commission();
            if (commission == null) {
                anomalies.add(new Anomaly(AnomalyType.COMMISSION_UNKNOWN, op.travelerId(), op.source(), op.sourceId(),
                        op.currency(), day(op.paidAt())));
                commissionComplete = false;
                commission = BigDecimal.ZERO;
            }
            CurrencyAcc acc = byCurrency.computeIfAbsent(op.currency(), c -> new CurrencyAcc());
            acc.net = acc.net.add(op.net());
            acc.commission = acc.commission.add(commission);
            if (op.countsAsOperation()) {
                acc.operations++;
                operations++;
            }
            if (rate.isPresent()) {
                BigDecimal units = rate.get().unitsPerEur();
                netEur = netEur.add(op.net().divide(units, RATE_SCALE, RoundingMode.HALF_UP));
                commissionEur = commissionEur.add(commission.divide(units, RATE_SCALE, RoundingMode.HALF_UP));
                if (!rate.get().rateDate().equals(day(op.paidAt()))) {
                    anomalies.add(new Anomaly(AnomalyType.RATE_DATE_ESTIMATED, op.travelerId(), op.source(),
                            op.sourceId(), op.currency(), day(op.paidAt())));
                }
            } else {
                eurComplete = false;
                anomalies.add(new Anomaly(AnomalyType.EXCHANGE_RATE_MISSING, op.travelerId(), op.source(),
                        op.sourceId(), op.currency(), day(op.paidAt())));
            }
        }

        QuarterReport report() {
            List<CurrencyAmounts> amounts = byCurrency.entrySet().stream()
                    .map(e -> new CurrencyAmounts(e.getKey(), e.getValue().net, e.getValue().commission,
                            e.getValue().operations))
                    .toList();
            return new QuarterReport(quarter, operations, amounts, round(netEur), round(commissionEur),
                    eurComplete, commissionComplete);
        }

        private static BigDecimal round(BigDecimal eur) {
            return eur.setScale(EUR_SCALE, RoundingMode.HALF_UP);
        }
    }

    private static final class CurrencyAcc {
        private BigDecimal net = BigDecimal.ZERO;
        private BigDecimal commission = BigDecimal.ZERO;
        private int operations;
    }
}
