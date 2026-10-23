package com.yadony.api.admin.dto;

/**
 * Nombres de paiements par vue et par alerte. {@code attention} = paiements portant au moins une alerte ;
 * les {@link Alerts} sont les prédicats BRUTS (non exclusifs) et peuvent se recouper : leur somme n'égale pas
 * forcément {@code attention}. {@code abandoned} compte les checkouts abandonnés du périmètre même quand
 * {@code hideAbandoned} les écarte des autres nombres.
 */
public record AdminPaymentCountsResponse(long attention, long escrow, long releasedRecent, long refunded,
                                         long failed, long all, long abandoned, Alerts alerts) {

    public record Alerts(long held, long chargebackOpen, long mmFailed, long escrowJ48) {
    }
}
