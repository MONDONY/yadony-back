package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.supplement.BidSupplementEntity;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Export fiscal du voyageur PRO (résumé, transactions, DAC7). Les revenus sont les paiements
 * versés ({@code RELEASED}) et, depuis le supplément de prix à la remise, les suppléments versés
 * ({@code PAID_OUT}, carte ou mobile money) : leur brut entre dans le revenu brut, leur commission
 * dans les commissions retenues. Un supplément n'est pas une transaction de plus (il complète le
 * transport du colis) : il est listé sur sa propre ligne mais ne change pas le nombre de transactions.
 */
@Service
public class FiscalExportService {

    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidSupplementRepository supplementRepository;

    public FiscalExportService(PaymentRepository paymentRepository,
                               BidRepository bidRepository,
                               AnnouncementRepository announcementRepository,
                               BidSupplementRepository supplementRepository) {
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.supplementRepository = supplementRepository;
    }

    /** Une ligne de revenu : un paiement versé, ou un supplément versé. */
    private record Line(LocalDate date, String corridor, BigDecimal gross, BigDecimal commission) {
        BigDecimal net() {
            return gross.subtract(commission);
        }
    }

    /** Revenus de l'année : lignes datées, et nombre de transactions (paiements seuls). */
    private record Revenue(List<Line> lines, int transactions) {
        BigDecimal gross() {
            return lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal commission() {
            return lines.stream().map(Line::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public byte[] generateCsv(UserEntity traveler, int year, String type) {
        Revenue revenue = fetchRevenue(traveler, year);
        return switch (type) {
            case "summary" -> buildSummaryCsv(revenue, traveler, year).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "dac7" -> buildDac7Csv(revenue, traveler, year).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> buildTransactionsCsv(revenue).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    public byte[] generateHtml(UserEntity traveler, int year, String type) {
        Revenue revenue = fetchRevenue(traveler, year);
        String body = switch (type) {
            case "summary" -> buildSummaryHtml(revenue, traveler, year);
            case "dac7" -> buildDac7Html(revenue, traveler, year);
            default -> buildTransactionsHtml(revenue, year);
        };
        return wrapHtml(body, year, type).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- Data fetching ----

    private Revenue fetchRevenue(UserEntity traveler, int year) {
        LocalDateTime from = LocalDateTime.of(year, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(year, 12, 31, 23, 59, 59);
        List<PaymentEntity> payments = paymentRepository.findReleasedByTravelerAndYear(traveler.getId(), from, to);
        List<Line> lines = new ArrayList<>();
        for (PaymentEntity p : payments) {
            lines.add(new Line(p.getCreatedAt().toLocalDate(), resolveCorridor(p.getBidId()), p.getAmount(),
                    zeroIfNull(p.getCommissionAmount())));
        }
        for (BidSupplementEntity s : supplementRepository.findPaidOutByTravelerBetween(traveler.getId(), from, to)) {
            lines.add(new Line(s.getPaidOutAt().toLocalDate(), resolveCorridor(s.getBidId()) + " (supplément)",
                    s.getGrossAmount(), zeroIfNull(s.getCommissionAmount())));
        }
        lines.sort(Comparator.comparing(Line::date));
        return new Revenue(lines, payments.size());
    }

    // ---- CSV builders ----

    private String buildSummaryCsv(Revenue revenue, UserEntity traveler, int year) {
        var sb = new StringBuilder();
        sb.append("Export fiscal Yadony — Résumé annuel\n");
        sb.append("Année,Nom,Revenu brut (€),Commissions (€),Revenu net (€)\n");
        BigDecimal gross = revenue.gross();
        BigDecimal commission = revenue.commission();
        BigDecimal net = gross.subtract(commission);
        String name = traveler.getFirstName() + " " + traveler.getLastName();
        sb.append(year).append(",").append(csv(name)).append(",")
                .append(eur(gross)).append(",").append(eur(commission)).append(",").append(eur(net));
        return sb.toString();
    }

    private String buildTransactionsCsv(Revenue revenue) {
        var sb = new StringBuilder();
        sb.append("Date,Trajet,Montant brut (€),Commission (€),Montant net (€)\n");
        for (Line line : revenue.lines()) {
            sb.append(line.date()).append(",")
                    .append(csv(line.corridor())).append(",")
                    .append(eur(line.gross())).append(",")
                    .append(eur(line.commission())).append(",")
                    .append(eur(line.net())).append("\n");
        }
        return sb.toString();
    }

    private String buildDac7Csv(Revenue revenue, UserEntity traveler, int year) {
        var sb = new StringBuilder();
        sb.append("# Export DAC7 — Yadony — ").append(year).append("\n");
        sb.append("Champ,Valeur\n");
        sb.append("Plateforme,Yadony SAS\n");
        sb.append("Année,").append(year).append("\n");
        sb.append("Prénom,").append(csv(traveler.getFirstName())).append("\n");
        sb.append("Nom,").append(csv(traveler.getLastName())).append("\n");
        sb.append("Pays,").append(csv(traveler.getCountry())).append("\n");
        BigDecimal gross = revenue.gross();
        BigDecimal commission = revenue.commission();
        sb.append("Revenu brut total (€),").append(eur(gross)).append("\n");
        sb.append("Commissions retenues (€),").append(eur(commission)).append("\n");
        sb.append("Revenu net total (€),").append(eur(gross.subtract(commission))).append("\n");
        sb.append("Nombre de transactions,").append(revenue.transactions()).append("\n");
        return sb.toString();
    }

    // ---- HTML builders ----

    private String buildSummaryHtml(Revenue revenue, UserEntity traveler, int year) {
        BigDecimal gross = revenue.gross();
        BigDecimal commission = revenue.commission();
        BigDecimal net = gross.subtract(commission);
        return "<h2>Résumé annuel " + year + "</h2>" +
                "<p><strong>Nom :</strong> " + esc(traveler.getFirstName() + " " + traveler.getLastName()) + "</p>" +
                "<table><tr><th>Revenu brut</th><th>Commissions Yadony</th><th>Revenu net</th></tr>" +
                "<tr><td>" + eur(gross) + " €</td><td>" + eur(commission) + " €</td><td>" + eur(net) + " €</td></tr>" +
                "</table>";
    }

    private String buildTransactionsHtml(Revenue revenue, int year) {
        var sb = new StringBuilder();
        sb.append("<h2>Transactions ").append(year).append("</h2>");
        sb.append("<table><tr><th>Date</th><th>Trajet</th><th>Brut (€)</th><th>Commission (€)</th><th>Net (€)</th></tr>");
        for (Line line : revenue.lines()) {
            sb.append("<tr><td>").append(line.date())
                    .append("</td><td>").append(esc(line.corridor()))
                    .append("</td><td>").append(eur(line.gross()))
                    .append("</td><td>").append(eur(line.commission()))
                    .append("</td><td>").append(eur(line.net()))
                    .append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildDac7Html(Revenue revenue, UserEntity traveler, int year) {
        BigDecimal gross = revenue.gross();
        BigDecimal commission = revenue.commission();
        return "<h2>Déclaration DAC7 — " + year + "</h2>" +
                "<p><strong>Plateforme :</strong> Yadony SAS</p>" +
                "<p><strong>Prestataire :</strong> " + esc(traveler.getFirstName() + " " + traveler.getLastName()) + "</p>" +
                "<p><strong>Pays :</strong> " + esc(traveler.getCountry()) + "</p>" +
                "<table><tr><th>Revenu brut</th><th>Commissions</th><th>Revenu net</th><th>Transactions</th></tr>" +
                "<tr><td>" + eur(gross) + " €</td><td>" + eur(commission) + " €</td>" +
                "<td>" + eur(gross.subtract(commission)) + " €</td><td>" + revenue.transactions() + "</td></tr>" +
                "</table>" +
                "<p style='margin-top:20px;font-size:11px;color:#666;'>Document généré automatiquement par Yadony. " +
                "Ouvrez ce fichier dans un navigateur puis imprimez-le (Ctrl+P) pour obtenir un PDF.</p>";
    }

    private String wrapHtml(String body, int year, String type) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>" +
                "<title>Export fiscal Yadony " + year + "</title>" +
                "<style>body{font-family:Arial,sans-serif;margin:40px;color:#333}" +
                "h1{color:#0B5FFF}h2{color:#333;border-bottom:1px solid #ddd;padding-bottom:8px}" +
                "table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px 12px;text-align:left}" +
                "th{background:#f5f5f5;font-weight:600}@media print{body{margin:20px}}</style>" +
                "</head><body><h1>Export fiscal Yadony</h1>" + body + "</body></html>";
    }

    // ---- Helpers ----

    private String resolveCorridor(UUID bidId) {
        if (bidId == null) return "—";
        return bidRepository.findById(bidId)
                .map(bid -> announcementRepository.findById(bid.getAnnouncementId())
                        .map(a -> a.getDepartureCity() + " → " + a.getArrivalCity())
                        .orElse("—"))
                .orElse("—");
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String eur(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String csv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
