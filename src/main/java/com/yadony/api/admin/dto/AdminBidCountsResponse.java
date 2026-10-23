package com.yadony.api.admin.dto;

import com.yadony.api.matching.BidRepository;

/** Nombres de colis par vue rapide et par alerte (une alerte par colis : elles somment à {@code todo}). */
public record AdminBidCountsResponse(long todo, long active, long recent, long completed, long closed,
                                     long all, Alerts alerts) {

    public record Alerts(long disputeOpen, long noShow, long paymentPending, long late) {
    }

    /** Un nombre absent vaut zéro. */
    public static AdminBidCountsResponse of(BidRepository.AdminCountsRow r) {
        return new AdminBidCountsResponse(
                n(r.getTodo()), n(r.getActive()), n(r.getRecent()), n(r.getCompleted()), n(r.getClosed()),
                n(r.getAll()),
                new Alerts(n(r.getDisputeOpen()), n(r.getNoShow()), n(r.getPaymentPending()), n(r.getLate())));
    }

    private static long n(Long value) {
        return value == null ? 0 : value;
    }
}
