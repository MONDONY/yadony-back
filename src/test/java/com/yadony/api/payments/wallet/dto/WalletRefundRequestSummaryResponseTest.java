package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.payments.wallet.WalletRefundItemStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Frais, net et rail du résumé d'une demande de remboursement (contrat additif, tâche 5). */
class WalletRefundRequestSummaryResponseTest {

    private static WalletRefundRequestEntity request(String amount, WalletRefundChannel channel,
                                                     WalletRefundRequestStatus status) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setCurrency("XOF");
        r.setAmount(new BigDecimal(amount));
        r.setChannel(channel);
        r.setStatus(status);
        r.setRequestedAt(LocalDateTime.now());
        return r;
    }

    private static WalletRefundRequestItemEntity item(String amount, String fee, WalletRefundItemStatus status) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        i.setPaymentIntentId("pi_" + amount + "_" + status);
        i.setAmount(new BigDecimal(amount));
        i.setFeeAmount(new BigDecimal(fee));
        i.setStatus(status);
        return i;
    }

    @Test
    void itemEchoue_exclusDesFraisEtDuNet() {
        // Un item FAILED n'est jamais parti vers l'utilisateur : le compter gonflerait le net
        // affiché par l'app sur une demande partiellement échouée.
        WalletRefundRequestSummaryResponse dto = WalletRefundRequestSummaryResponse.from(
                request("20000", WalletRefundChannel.AUTOMATIC_PAWAPAY, WalletRefundRequestStatus.FAILED),
                List.of(item("10000", "200", WalletRefundItemStatus.REFUNDED),
                        item("10000", "200", WalletRefundItemStatus.FAILED)),
                "+225 •••• 90");

        assertThat(dto.feeAmount()).isEqualByComparingTo("200");
        assertThat(dto.netAmount()).isEqualByComparingTo("9800");
        assertThat(dto.rail()).isEqualTo("PAWAPAY");
        assertThat(dto.destinationMasked()).isEqualTo("+225 •••• 90");
    }

    @Test
    void demandeEnCours_leNetCompteLesItemsNonEncoreTermines() {
        // Filtrer sur REFUNDED seul afficherait 0 sur une demande encore en cours.
        WalletRefundRequestSummaryResponse dto = WalletRefundRequestSummaryResponse.from(
                request("20000", WalletRefundChannel.AUTOMATIC_PAWAPAY, WalletRefundRequestStatus.PROCESSING),
                List.of(item("10000", "200", WalletRefundItemStatus.PROCESSING),
                        item("10000", "200", WalletRefundItemStatus.PENDING)),
                null);

        assertThat(dto.feeAmount()).isEqualByComparingTo("400");
        assertThat(dto.netAmount()).isEqualByComparingTo("19600");
        assertThat(dto.destinationMasked()).isNull();
    }

    @Test
    void tousLesItemsEchoues_fraisNulsEtNetEgalAuMontantDeLaDemande() {
        // Aucun item comptable : on retombe sur le repli « demande sans item » (ticket manuel).
        WalletRefundRequestSummaryResponse dto = WalletRefundRequestSummaryResponse.from(
                request("10000", WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.FAILED),
                List.of(item("10000", "200", WalletRefundItemStatus.FAILED)),
                null);

        assertThat(dto.feeAmount()).isEqualByComparingTo("0");
        assertThat(dto.netAmount()).isEqualByComparingTo("10000");
        assertThat(dto.rail()).isEqualTo("STRIPE");
    }

    @Test
    void ticketManuelSansItem_fraisNulsEtNetEgalAuMontant() {
        WalletRefundRequestSummaryResponse dto = WalletRefundRequestSummaryResponse.from(
                request("10000", WalletRefundChannel.MANUAL_ADMIN, WalletRefundRequestStatus.PENDING),
                List.of(), null);

        assertThat(dto.rail()).isEqualTo("MANUAL");
        assertThat(dto.feeAmount()).isEqualByComparingTo("0");
        assertThat(dto.netAmount()).isEqualByComparingTo("10000");
    }
}
