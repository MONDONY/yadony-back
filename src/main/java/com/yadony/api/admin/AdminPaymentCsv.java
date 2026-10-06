package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPaymentInsight;
import com.yadony.api.admin.export.CsvWriter;
import com.yadony.api.payments.PaymentEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** CSV de la liste Transactions › Paiements : une ligne par paiement, avec ses parties et son trajet. */
final class AdminPaymentCsv {

    static final String HEADER = "id,type,colis,negociation,statut,rail,devise,montant,commission,netVoyageur,"
            + "rembourse,expediteur,voyageur,trajet,stripePaymentIntentId,creeLe,captureLe,libereLe,checkoutAbandonne";

    private AdminPaymentCsv() {
    }

    static byte[] write(List<PaymentEntity> payments, Map<UUID, AdminPaymentInsight> insights) {
        CsvWriter csv = new CsvWriter(HEADER);
        for (PaymentEntity p : payments) {
            AdminPaymentInsight i = insights.get(p.getId());
            BigDecimal amount = p.getAmount();
            BigDecimal commission = p.getCommissionAmount();
            csv.row(
                    str(p.getId()),
                    i == null ? "" : i.kind(),
                    i == null ? str(p.getBidId()) : str(i.bidId()),
                    str(p.getNegotiationThreadId()),
                    p.getStatus() == null ? "" : p.getStatus().name(),
                    p.getRail() == null ? "" : p.getRail().name(),
                    p.getCurrency() == null ? "" : p.getCurrency().toUpperCase(Locale.ROOT),
                    money(amount),
                    money(commission),
                    amount == null ? "" : amount.subtract(commission == null ? BigDecimal.ZERO : commission).toPlainString(),
                    money(p.getRefundedAmount()),
                    i == null ? "" : party(i.sender()),
                    i == null ? "" : party(i.traveler()),
                    i == null ? "" : route(i),
                    p.getStripePaymentIntentId(),
                    str(p.getCreatedAt()),
                    str(p.getCapturedAt()),
                    str(p.getEscrowReleasedAt()),
                    i == null ? "" : String.valueOf(i.abandoned()));
        }
        return csv.bytes();
    }

    private static String party(AdminPaymentInsight.Party party) {
        if (party == null) return "";
        return party.name() != null ? party.name() : party.id().toString();
    }

    private static String route(AdminPaymentInsight i) {
        if (i.departureCity() == null && i.arrivalCity() == null) return "";
        return str(i.departureCity()) + " → " + str(i.arrivalCity());
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
