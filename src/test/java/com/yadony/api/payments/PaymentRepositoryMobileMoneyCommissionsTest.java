package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.dto.MobileMoneyCommissionMonthRow;
import com.yadony.api.payments.dto.MobileMoneyCommissionRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Agrégats des commissions mobile money, exécutés sur une vraie base : ce sont des requêtes
 * de groupement (devise, statut, mois) dont un mock de dépôt ne prouverait rien.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("PaymentRepository — commissions mobile money")
class PaymentRepositoryMobileMoneyCommissionsTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired TestEntityManager em;

    private static final LocalDateTime FROM = LocalDateTime.now().minusMonths(6);
    private static final LocalDateTime TO = LocalDateTime.now().plusDays(1);

    private int seq = 0;

    private PaymentEntity payment(PaymentRail rail, String currency, String amount, String commission,
                                  PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_mmc_" + (seq++));
        p.setRail(rail);
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        return em.persistAndFlush(p);
    }

    /** Recule la date de création d'un paiement : c'est elle qui range la ligne dans un mois. */
    private void backdate(PaymentEntity payment, LocalDateTime when) {
        em.getEntityManager()
                .createNativeQuery("UPDATE payments SET created_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", payment.getId())
                .executeUpdate();
        em.clear();
    }

    private static MobileMoneyCommissionRow row(List<MobileMoneyCommissionRow> rows, String currency,
                                                PaymentStatus status) {
        return rows.stream().filter(r -> r.currency().equals(currency) && r.status() == status)
                .findFirst().orElseThrow(() -> new AssertionError("ligne absente : " + currency + " " + status));
    }

    @Test
    void groupeParDeviseEtStatut_etIgnoreLeRailCarte() {
        payment(PaymentRail.PAWAPAY, "XOF", "6600.00", "600.00", PaymentStatus.RELEASED);
        payment(PaymentRail.PAWAPAY, "XOF", "13200.00", "1200.00", PaymentStatus.RELEASED);
        payment(PaymentRail.PAWAPAY, "XOF", "9900.00", "900.00", PaymentStatus.ESCROW);
        payment(PaymentRail.PAWAPAY, "XAF", "5000.00", "500.00", PaymentStatus.RELEASED);
        // Le rail carte a sa propre comptabilité (Stripe) : il n'a rien à faire ici.
        payment(PaymentRail.STRIPE, "EUR", "100.00", "12.00", PaymentStatus.RELEASED);

        List<MobileMoneyCommissionRow> rows =
                paymentRepository.sumMobileMoneyCommissionsByCurrencyAndStatus(FROM, TO);

        assertThat(rows).extracting(MobileMoneyCommissionRow::currency).doesNotContain("EUR");

        MobileMoneyCommissionRow xofReleased = row(rows, "XOF", PaymentStatus.RELEASED);
        assertThat(xofReleased.count()).isEqualTo(2);
        assertThat(xofReleased.gross()).isEqualByComparingTo("19800.00");
        assertThat(xofReleased.commission()).isEqualByComparingTo("1800.00");

        MobileMoneyCommissionRow xofEscrow = row(rows, "XOF", PaymentStatus.ESCROW);
        assertThat(xofEscrow.count()).isEqualTo(1);
        assertThat(xofEscrow.commission()).isEqualByComparingTo("900.00");

        // Les devises ne sont jamais fondues ensemble : 1 800 XOF et 500 XAF restent séparés.
        assertThat(row(rows, "XAF", PaymentStatus.RELEASED).commission()).isEqualByComparingTo("500.00");
    }

    @Test
    void horsPeriode_estExclu() {
        PaymentEntity vieux = payment(PaymentRail.PAWAPAY, "XOF", "6600.00", "600.00", PaymentStatus.RELEASED);
        backdate(vieux, LocalDateTime.now().minusMonths(18));

        assertThat(paymentRepository.sumMobileMoneyCommissionsByCurrencyAndStatus(FROM, TO)).isEmpty();
        assertThat(paymentRepository.sumMobileMoneyCommissionsByMonth(FROM, TO)).isEmpty();
    }

    @Test
    void ventilationMensuelle_neRetientQueLesCommissionsAcquises() {
        PaymentEntity moisDernier = payment(PaymentRail.PAWAPAY, "XOF", "6600.00", "600.00", PaymentStatus.RELEASED);
        LocalDateTime hier = LocalDateTime.now().minusMonths(1);
        backdate(moisDernier, hier);
        payment(PaymentRail.PAWAPAY, "XOF", "13200.00", "1200.00", PaymentStatus.RELEASED);
        // En séquestre : la livraison n'est pas confirmée, la commission n'est pas acquise.
        payment(PaymentRail.PAWAPAY, "XOF", "9900.00", "900.00", PaymentStatus.ESCROW);

        List<MobileMoneyCommissionMonthRow> months = paymentRepository.sumMobileMoneyCommissionsByMonth(FROM, TO);

        assertThat(months).hasSize(2);
        LocalDateTime now = LocalDateTime.now();
        MobileMoneyCommissionMonthRow courant = months.get(0);
        assertThat(courant.year()).isEqualTo(now.getYear());
        assertThat(courant.month()).isEqualTo(now.getMonthValue());
        assertThat(courant.currency()).isEqualTo("XOF");
        assertThat(courant.count()).isEqualTo(1);
        assertThat(courant.commission()).isEqualByComparingTo("1200.00");

        MobileMoneyCommissionMonthRow precedent = months.get(1);
        assertThat(precedent.year()).isEqualTo(hier.getYear());
        assertThat(precedent.month()).isEqualTo(hier.getMonthValue());
        assertThat(precedent.commission()).isEqualByComparingTo("600.00");
    }
}
