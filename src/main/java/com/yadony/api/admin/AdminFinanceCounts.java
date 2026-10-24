package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminFinanceCountsResponse;
import com.yadony.api.admin.metrics.AdminQueueCounter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Nombres « à traiter » des quatre sections Transactions, calculés par les mêmes fonctions que la page de
 * chaque section (aucune définition recopiée) : paiements en alerte (comme la page, checkouts abandonnés
 * masqués), opérations mobile money à reprendre, remboursements du solde en attente (même nombre que la
 * pastille du menu), commissions cash à traiter et litiges ouverts. Lecture seule.
 */
@Component
public class AdminFinanceCounts {

    private final AdminPaymentInsights paymentInsights;
    private final AdminFinanceListings listings;
    private final AdminQueueCounter queueCounter;
    private final Clock clock;

    public AdminFinanceCounts(AdminPaymentInsights paymentInsights, AdminFinanceListings listings,
                              AdminQueueCounter queueCounter, Clock clock) {
        this.paymentInsights = paymentInsights;
        this.listings = listings;
        this.queueCounter = queueCounter;
        this.clock = clock;
    }

    public AdminFinanceCountsResponse counts() {
        AdminPaymentFilter page = AdminPaymentFilter.of(null, null, null, null, null, false, null, true);
        long payments = paymentInsights.counts(page, LocalDateTime.now(clock)).attention();
        long mobileMoney = listings.mobileMoneyCounts(null, null, null).attention();
        long walletRefunds = queueCounter.countPendingWalletRefunds();
        long cash = listings.cashCommissionCounts(null, null).attention();
        long chargebacks = listings.chargebackCounts(null).open();
        return new AdminFinanceCountsResponse(payments, mobileMoney, walletRefunds + cash, chargebacks,
                new AdminFinanceCountsResponse.Details(payments, mobileMoney, walletRefunds, cash, chargebacks));
    }
}
