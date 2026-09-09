package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.dto.PaymentVolumeRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Volumes de la vue d'ensemble admin, sur une vraie base : une requête de groupement (devise,
 * statut) dont un mock de dépôt ne prouverait rien. Recette du 2026-09-09 : la vue d'ensemble
 * additionnait EUR, XOF et XAF dans un seul SUM et affichait le tout en euros.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("PaymentRepository — volumes par devise de la vue d'ensemble")
class PaymentRepositoryVolumesByCurrencyTest {

    private static final List<PaymentStatus> OVERVIEW_STATUSES =
            List.of(PaymentStatus.ESCROW, PaymentStatus.RELEASED, PaymentStatus.REFUNDED);

    @Autowired PaymentRepository paymentRepository;
    @Autowired TestEntityManager em;

    private int seq = 0;

    private PaymentEntity payment(PaymentRail rail, String currency, String amount, String commission,
                                  PaymentStatus status, String refunded) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_vol_" + (seq++));
        p.setRail(rail);
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        if (refunded != null) {
            p.setRefundedAmount(new BigDecimal(refunded));
        }
        return em.persistAndFlush(p);
    }

    private static PaymentVolumeRow row(List<PaymentVolumeRow> rows, String currency, PaymentStatus status) {
        return rows.stream().filter(r -> r.currency().equals(currency) && r.status() == status)
                .findFirst().orElseThrow(() -> new AssertionError("ligne absente : " + currency + " " + status));
    }

    @Test
    void groupeParDeviseEtStatut_sansJamaisFondreLesDevises() {
        payment(PaymentRail.STRIPE, "EUR", "100.00", "12.00", PaymentStatus.RELEASED, null);
        payment(PaymentRail.PAWAPAY, "XOF", "6600.00", "600.00", PaymentStatus.RELEASED, null);
        payment(PaymentRail.PAWAPAY, "XOF", "13200.00", "1200.00", PaymentStatus.RELEASED, null);
        payment(PaymentRail.PAWAPAY, "XOF", "9900.00", "900.00", PaymentStatus.ESCROW, null);
        payment(PaymentRail.PAWAPAY, "XAF", "5000.00", "500.00", PaymentStatus.REFUNDED, "5000.00");
        // Un paiement jamais autorisé, ou échoué, n'est pas un volume.
        payment(PaymentRail.STRIPE, "EUR", "40.00", "4.00", PaymentStatus.PENDING, null);
        payment(PaymentRail.PAWAPAY, "XOF", "1000.00", "100.00", PaymentStatus.FAILED, null);

        List<PaymentVolumeRow> rows = paymentRepository.sumVolumesByCurrencyAndStatus(OVERVIEW_STATUSES);

        assertThat(rows).extracting(PaymentVolumeRow::status)
                .doesNotContain(PaymentStatus.PENDING, PaymentStatus.FAILED);
        // Les devises sortent triées et jamais fondues : 100 EUR, 5 000 XAF et 19 800 XOF restent trois lignes.
        assertThat(rows.stream().map(PaymentVolumeRow::currency).distinct().toList())
                .containsExactly("EUR", "XAF", "XOF");

        PaymentVolumeRow xofReleased = row(rows, "XOF", PaymentStatus.RELEASED);
        assertThat(xofReleased.amount()).isEqualByComparingTo("19800.00");
        assertThat(xofReleased.commission()).isEqualByComparingTo("1800.00");
        assertThat(xofReleased.refunded()).isEqualByComparingTo("0");

        assertThat(row(rows, "XOF", PaymentStatus.ESCROW).amount()).isEqualByComparingTo("9900.00");
        assertThat(row(rows, "XAF", PaymentStatus.REFUNDED).refunded()).isEqualByComparingTo("5000.00");
        assertThat(row(rows, "EUR", PaymentStatus.RELEASED).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void sansPaiement_listeVide() {
        assertThat(paymentRepository.sumVolumesByCurrencyAndStatus(OVERVIEW_STATUSES)).isEmpty();
    }
}
