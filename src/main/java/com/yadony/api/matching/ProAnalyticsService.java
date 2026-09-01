package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.matching.dto.ProAnalyticsResponse;
import com.yadony.api.matching.dto.ProAnalyticsResponse.KpiDto;
import com.yadony.api.matching.dto.ProAnalyticsResponse.TransactionRowDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProAnalyticsService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    private final com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;

    public ProAnalyticsService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository,
            com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver,
            com.yadony.api.payments.currency.ExchangeRateService exchangeRateService
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(readOnly = true)
    public ProAnalyticsResponse computeAnalytics(UserEntity traveler, String period) {
        UUID userId = traveler.getId();
        LocalDateTime[] range = periodRange(period);
        LocalDateTime from = range[0];
        LocalDateTime to = range[1];
        LocalDateTime[] prevRange = previousPeriodRange(period, from);
        LocalDateTime prevFrom = prevRange[0];
        LocalDateTime prevTo = prevRange[1];

        // Revenue = carte (escrow libéré) + espèces (net des bids CASH livrés, hors
        // PaymentEntity), par devise puis converti vers la devise active pour le KPI.
        // Les deux périodes passent par les MÊMES taux courants : la tendance en %
        // compare donc des grandeurs cohérentes.
        String activeCurrency = activeCurrencyResolver.resolveDisplay(userId);
        BigDecimal revenue = convertedTotal(TravelerRevenue.cardPlusCashByCurrency(
                paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                        userId, PaymentStatus.RELEASED, from, to),
                bidRepository.sumCashNetRevenueForTravelerByCurrency(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH, from, to)), activeCurrency);
        BigDecimal prevRevenue = convertedTotal(TravelerRevenue.cardPlusCashByCurrency(
                paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                        userId, PaymentStatus.RELEASED, prevFrom, prevTo),
                bidRepository.sumCashNetRevenueForTravelerByCurrency(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH, prevFrom, prevTo)), activeCurrency);

        // Trips
        long trips = announcementRepository.countByTravelerIdAndCreatedAtBetween(userId, from, to);
        long prevTrips = announcementRepository.countByTravelerIdAndCreatedAtBetween(userId, prevFrom, prevTo);

        // Parcels delivered
        long parcels = bidRepository.countDeliveredBidsForTraveler(userId, BidStatus.COMPLETED, from, to);

        // Acceptance rate — un bid accepté puis livré n'est plus en statut ACCEPTED :
        // on compte tout bid ayant dépassé le stade de l'acceptation (ACCEPTED_OR_BEYOND).
        long accepted = bidRepository.countByAnnouncementTravelerIdAndStatusIn(userId, BidStatus.ACCEPTED_OR_BEYOND);
        // Refus explicites seulement — les bids rejetés par suppression d'annonce ne comptent pas.
        long rejected = bidRepository.countExplicitRejectionsForTraveler(userId);
        double acceptRate = (accepted + rejected) == 0 ? 0.0
                : BigDecimal.valueOf((double) accepted / (accepted + rejected))
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        // Rating
        BigDecimal rating = traveler.getAverageRating() != null
                ? traveler.getAverageRating().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // KPIs
        List<KpiDto> kpis = new ArrayList<>();
        kpis.add(revenueKpi(revenue, prevRevenue, activeCurrency));
        kpis.add(new KpiDto("trips", "Trajets", String.valueOf(trips), trend(trips, prevTrips), delta(trips, prevTrips)));
        kpis.add(new KpiDto("parcels", "Colis gérés", String.valueOf(parcels), null, null));
        kpis.add(new KpiDto("acceptance", "Taux acceptation", formatPercent(acceptRate), null, null));
        kpis.add(new KpiDto("rating", "Note moyenne", rating + "/5", null, null));

        // Transactions
        List<TransactionRowDto> transactions = buildTransactions(userId, from, to);

        return new ProAnalyticsResponse(period, kpis, transactions);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private KpiDto revenueKpi(BigDecimal current, BigDecimal prev, String currency) {
        String value = formatAmount(current, currency);
        if (prev.compareTo(BigDecimal.ZERO) == 0) {
            return new KpiDto("revenue", "Revenus nets", value, null, null);
        }
        BigDecimal diff = current.subtract(prev);
        BigDecimal pct = diff.divide(prev, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        String trendDir = diff.compareTo(BigDecimal.ZERO) >= 0 ? "up" : "down";
        String trendVal = (diff.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + pct.setScale(0, RoundingMode.HALF_UP) + "%";
        return new KpiDto("revenue", "Revenus nets", value, trendDir, trendVal);
    }

    private String trend(long current, long prev) {
        if (current > prev) return "up";
        if (current < prev) return "down";
        return "stable";
    }

    private String delta(long current, long prev) {
        long diff = current - prev;
        return (diff >= 0 ? "+" : "") + diff;
    }

    // Format monétaire fr-FR (« 1 234,56 € », « 3 280 F CFA ») dans la devise du
    // montant. Symbole et décimales viennent du catalogue SupportedCurrency (0 en
    // XOF), pas du JDK, pour rester aligné sur le rendu Flutter (CurrencyFormatter).
    private String formatAmount(BigDecimal amount, String currencyCode) {
        com.yadony.api.payments.currency.SupportedCurrency currency =
                com.yadony.api.payments.currency.SupportedCurrency.fromCodeOrDefault(currencyCode);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRANCE);
        nf.setMinimumFractionDigits(currency.minorUnit());
        nf.setMaximumFractionDigits(currency.minorUnit());
        return nf.format(amount.setScale(currency.minorUnit(), RoundingMode.HALF_UP))
                + "\u00A0" + currency.symbol();
    }

    /** Somme « environ » dans la devise cible : chaque devise convertie au taux courant. */
    private BigDecimal convertedTotal(java.util.Map<String, BigDecimal> byCurrency, String target) {
        return byCurrency.entrySet().stream()
                .map(e -> exchangeRateService.convert(e.getValue(), e.getKey(), target))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String formatPercent(double rate) {
        return BigDecimal.valueOf(rate * 100).setScale(0, RoundingMode.HALF_UP) + "%";
    }

    /**
     * Détail des transactions : une ligne par annonce sur la période, fusionnant
     * les revenus carte (paiements RELEASED) et espèces (bids CASH livrés, qui ne
     * créent aucun PaymentEntity). Sans le terme cash, un trajet réglé en espèces
     * n'apparaîtrait pas ici et la somme des colonnes Net ne se réconcilierait plus
     * avec le KPI « Revenus ». Une même annonce peut porter les deux, d'où la fusion.
     */
    private List<TransactionRowDto> buildTransactions(UUID userId, LocalDateTime from, LocalDateTime to) {
        Map<UUID, TxnAccumulator> byAnnouncement = new LinkedHashMap<>();
        paymentRepository.findReleasedRevenueByAnnouncement(userId, PaymentStatus.RELEASED, from, to)
                .forEach(r -> accumulate(byAnnouncement, r));
        bidRepository.findCashRevenueByAnnouncement(userId, BidStatus.COMPLETED, PaymentMethod.CASH, from, to)
                .forEach(r -> accumulate(byAnnouncement, r));

        return byAnnouncement.values().stream()
                .sorted(Comparator.comparing((TxnAccumulator a) -> a.departureDate).reversed())
                .map(a -> new TransactionRowDto(
                        a.announcementId.toString(),
                        a.departureCity + " → " + a.arrivalCity,
                        a.departureDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        (int) a.parcelCount,
                        toMinorUnits(a.gross, a.currency),
                        toMinorUnits(a.commission, a.currency),
                        toMinorUnits(a.gross.subtract(a.commission), a.currency),
                        a.currency))
                .toList();
    }

    private static void accumulate(Map<UUID, TxnAccumulator> map, AnnouncementRevenueRow r) {
        map.computeIfAbsent(
                r.announcementId(),
                id -> new TxnAccumulator(id, r.departureCity(), r.arrivalCity(), r.departureDate(), r.currency()))
           .add(r.parcelCount(), r.gross(), r.commission());
    }

    /** Accumulateur mutable par annonce : additionne carte + cash. */
    private static final class TxnAccumulator {
        final UUID announcementId;
        final String departureCity;
        final String arrivalCity;
        final LocalDate departureDate;
        /** Une annonce = une devise : carte et cash de la même annonce la partagent. */
        final String currency;
        long parcelCount;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;

        TxnAccumulator(UUID announcementId, String departureCity, String arrivalCity,
                       LocalDate departureDate, String currency) {
            this.announcementId = announcementId;
            this.departureCity = departureCity;
            this.arrivalCity = arrivalCity;
            this.departureDate = departureDate;
            this.currency = currency;
        }

        void add(long count, BigDecimal g, BigDecimal c) {
            parcelCount += count;
            gross = gross.add(g);
            commission = commission.add(c);
        }
    }

    /**
     * Unités mineures de la devise de la ligne : centimes quand elle en a, unité
     * pleine en XOF/XAF ({@code minorUnit = 0}). L'ancien {@code toCents} multipliait
     * tout par 100, XOF compris — 5000 F devenaient 500000 « centimes » inexistants.
     */
    private long toMinorUnits(BigDecimal amount, String currencyCode) {
        int minorUnit = com.yadony.api.payments.currency.SupportedCurrency
                .fromCodeOrDefault(currencyCode).minorUnit();
        return amount.movePointRight(minorUnit).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private LocalDateTime[] periodRange(String period) {
        return switch (period) {
            case "quarter" -> {
                YearMonth current = YearMonth.now();
                int month = current.getMonthValue();
                int quarterStart = ((month - 1) / 3) * 3 + 1;
                LocalDateTime from = LocalDate.of(current.getYear(), quarterStart, 1).atStartOfDay();
                LocalDateTime to = LocalDateTime.now();
                yield new LocalDateTime[]{from, to};
            }
            case "year" -> {
                int year = LocalDate.now().getYear();
                yield new LocalDateTime[]{
                        LocalDate.of(year, 1, 1).atStartOfDay(),
                        LocalDateTime.now()
                };
            }
            default -> { // month
                YearMonth current = YearMonth.now();
                yield new LocalDateTime[]{
                        current.atDay(1).atStartOfDay(),
                        current.atEndOfMonth().atTime(23, 59, 59)
                };
            }
        };
    }

    private LocalDateTime[] previousPeriodRange(String period, LocalDateTime currentFrom) {
        return switch (period) {
            case "quarter" -> {
                LocalDateTime prevFrom = currentFrom.minusMonths(3);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
            case "year" -> {
                LocalDateTime prevFrom = currentFrom.minusYears(1);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
            default -> { // month
                LocalDateTime prevFrom = currentFrom.minusMonths(1);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
        };
    }
}
