package com.yadony.api.payments.wallet;

import com.yadony.api.payments.wallet.fees.WalletRefundFeeCalculator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletRefundAllocatorTest {

    private final List<WalletTransactionEntity> ledger = new ArrayList<>();
    private final List<WalletRefundRequestItemEntity> items = new ArrayList<>();
    private Instant clock = Instant.parse("2026-09-01T10:00:00Z");

    private WalletTransactionEntity tx(WalletTransactionType type, String signedAmount, String paymentRef) {
        WalletTransactionEntity t = new WalletTransactionEntity();
        setField(t, "id", UUID.randomUUID());
        t.setUserId(UUID.randomUUID());
        t.setCurrency("EUR");
        t.setType(type);
        t.setAmount(new BigDecimal(signedAmount));
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setPaymentRef(paymentRef);
        clock = clock.plusSeconds(60);
        setField(t, "createdAt", clock);
        ledger.add(t);
        return t;
    }

    private WalletTransactionEntity topup(String amount) {
        return tx(WalletTransactionType.TOP_UP, amount, "pi_" + UUID.randomUUID());
    }

    private WalletTransactionEntity pawapayTopup(String amount) {
        return tx(WalletTransactionType.TOP_UP, amount, "pawapay:" + UUID.randomUUID());
    }

    /** Frais fixes par rail, pour figer la valeur attendue sans dépendre d'un calcul réel. */
    private static WalletRefundFeeCalculator.FeeSources fixedFees(String stripeFee, String pawapayFee) {
        return new WalletRefundFeeCalculator.FeeSources() {
            @Override
            public BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String currency) {
                return new BigDecimal(stripeFee);
            }

            @Override
            public BigDecimal pawapayFee(String provider, BigDecimal amount, String currency) {
                return new BigDecimal(pawapayFee);
            }
        };
    }

    private WalletRefundRequestItemEntity item(WalletTransactionEntity topup, String amount,
                                               WalletRefundItemStatus status, UUID requestId) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        setField(i, "id", UUID.randomUUID());
        i.setRefundRequestId(requestId);
        i.setWalletTransactionId(topup.getId());
        i.setPaymentIntentId(topup.getPaymentRef());
        i.setAmount(new BigDecimal(amount));
        i.setStatus(status);
        setField(i, "createdAt", clock);
        items.add(i);
        return i;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("champ absent : " + name);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private WalletRefundAllocation allocate(String balance) {
        return WalletRefundAllocator.allocate(ledger, items, new BigDecimal(balance));
    }

    @Test
    void rechargeIntacte_totalementRemboursable() {
        WalletTransactionEntity a = topup("40.00");

        WalletRefundAllocation r = allocate("40.00");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).walletTransactionId()).isEqualTo(a.getId());
        assertThat(r.refundable().get(0).paymentIntentId()).isEqualTo(a.getPaymentRef());
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("40.00");
        assertThat(r.refundableTotal()).isEqualByComparingTo("40.00");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
        assertThat(r.inFlight()).isEqualByComparingTo("0");
    }

    @Test
    void rechargeEntamee_resteRemboursable() {
        topup("40.00");
        tx(WalletTransactionType.BID_PAYMENT, "-5.00", null);

        WalletRefundAllocation r = allocate("35.00");

        assertThat(r.refundableTotal()).isEqualByComparingTo("35.00");
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("35.00");
    }

    @Test
    void deuxRecharges_depenseAttribueeLifo() {
        WalletTransactionEntity ancienne = topup("20.00");
        WalletTransactionEntity recente = topup("30.00");
        tx(WalletTransactionType.BID_PAYMENT, "-35.00", null);

        WalletRefundAllocation r = allocate("15.00");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).walletTransactionId()).isEqualTo(ancienne.getId());
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("15.00");
        assertThat(r.refundable()).noneMatch(t -> t.walletTransactionId().equals(recente.getId()));
    }

    @Test
    void bonusConsommeAvantLeCash() {
        topup("40.00");
        tx(WalletTransactionType.REFERRAL_REWARD, "5.00", null);
        tx(WalletTransactionType.BID_PAYMENT, "-10.00", null);

        WalletRefundAllocation r = allocate("35.00");

        assertThat(r.refundableTotal()).isEqualByComparingTo("35.00");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
    }

    @Test
    void bonusRestant_nonRemboursable() {
        topup("40.00");
        tx(WalletTransactionType.REFUND, "5.00", null);

        WalletRefundAllocation r = allocate("45.00");

        assertThat(r.refundableTotal()).isEqualByComparingTo("40.00");
        assertThat(r.nonRefundable()).isEqualByComparingTo("5.00");
    }

    @Test
    void remboursementAnterieur_appariementParMontant() {
        WalletTransactionEntity a = topup("40.00");
        WalletTransactionEntity b = topup("20.00");
        UUID requestId = UUID.randomUUID();
        item(b, "20.00", WalletRefundItemStatus.REFUNDED, requestId);
        tx(WalletTransactionType.SELF_REFUND_OUT, "-20.00", null);
        tx(WalletTransactionType.BID_PAYMENT, "-5.00", null);

        WalletRefundAllocation r = allocate("35.00");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).walletTransactionId()).isEqualTo(a.getId());
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("35.00");
        assertThat(r.inFlight()).isEqualByComparingTo("0");
    }

    @Test
    void adminRefundOutSansItems_repliLifo() {
        topup("40.00");
        tx(WalletTransactionType.ADMIN_REFUND_OUT, "-40.00", null);
        topup("10.00");

        WalletRefundAllocation r = allocate("10.00");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("10.00");
    }

    @Test
    void itemEnCours_exclusEtCompteEnInFlight() {
        WalletTransactionEntity a = topup("40.00");
        topup("10.00");
        item(a, "40.00", WalletRefundItemStatus.PROCESSING, UUID.randomUUID());

        WalletRefundAllocation r = allocate("50.00");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("10.00");
        assertThat(r.inFlight()).isEqualByComparingTo("40.00");
        assertThat(r.refundableTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void itemFailed_exclusEtCompteEnInFlight() {
        WalletTransactionEntity a = topup("40.00");
        item(a, "40.00", WalletRefundItemStatus.FAILED, UUID.randomUUID());

        WalletRefundAllocation r = allocate("40.00");

        assertThat(r.refundable()).isEmpty();
        assertThat(r.inFlight()).isEqualByComparingTo("40.00");
    }

    @Test
    void topupSansPaymentRef_traiteCommeNonCash() {
        tx(WalletTransactionType.TOP_UP, "15.00", null);

        WalletRefundAllocation r = allocate("15.00");

        assertThat(r.refundable()).isEmpty();
        assertThat(r.nonRefundable()).isEqualByComparingTo("15.00");
    }

    @Test
    void forfeitedOnDeletion_consommeLeNonCash() {
        tx(WalletTransactionType.REFERRAL_REWARD, "5.00", null);
        tx(WalletTransactionType.FORFEITED_ON_DELETION, "-5.00", null);

        WalletRefundAllocation r = allocate("0.00");

        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
        assertThat(r.refundableTotal()).isEqualByComparingTo("0");
    }

    @Test
    void deviseSansDecimales_montantsEntiers() {
        WalletTransactionEntity t = tx(WalletTransactionType.TOP_UP, "13200", "pi_xof");
        t.setCurrency("XOF");
        tx(WalletTransactionType.BID_PAYMENT, "-3200", null);

        WalletRefundAllocation r = allocate("10000");

        assertThat(r.refundable().get(0).remaining()).isEqualByComparingTo("10000");
    }

    @Test
    void invariantCasse_leve() {
        topup("40.00");

        assertThatThrownBy(() -> allocate("41.00"))
                .isInstanceOf(WalletAllocationInvariantException.class)
                .hasMessageContaining("41.00");
    }

    @Test
    void ledgerVide_allocationVide() {
        WalletRefundAllocation r = allocate("0");

        assertThat(r.refundable()).isEmpty();
        assertThat(r.refundableTotal()).isEqualByComparingTo("0");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
        assertThat(r.inFlight()).isEqualByComparingTo("0");
    }

    @Test
    void debitNonCouvert_leveAvecMontantNonAttribue() {
        topup("10.00");
        tx(WalletTransactionType.BID_PAYMENT, "-30.00", null);

        assertThatThrownBy(() -> allocate("-20.00"))
                .isInstanceOf(WalletAllocationInvariantException.class)
                .hasMessageContaining("20.00");
    }

    @Test
    void itemRefundedSeauDejaVide_leveInvariant() {
        WalletTransactionEntity a = topup("20.00");
        tx(WalletTransactionType.BID_PAYMENT, "-20.00", null);
        item(a, "20.00", WalletRefundItemStatus.REFUNDED, UUID.randomUUID());
        tx(WalletTransactionType.SELF_REFUND_OUT, "-20.00", null);

        assertThatThrownBy(() -> allocate("-20.00"))
                .isInstanceOf(WalletAllocationInvariantException.class);
    }

    @Test
    void demandePartiellementEchouee_debitEgalAuxRefundedSeuls_pasDInvariant() {
        // Fige le couplage entre les deux moitiés de la règle 4 : le SELF_REFUND_OUT vaut la
        // somme des items REFUNDED (resolveIfComplete), et le groupe apparié ne contient que
        // ces items-là. Si l'un des deux bougeait sans l'autre, l'appariement raterait et le
        // repli LIFO consommerait la recharge en échec — invariant cassé, remboursement bloqué.
        WalletTransactionEntity a = topup("40.00");
        WalletTransactionEntity b = topup("10.00");
        UUID requestId = UUID.randomUUID();
        item(a, "40.00", WalletRefundItemStatus.FAILED, requestId);
        item(b, "10.00", WalletRefundItemStatus.REFUNDED, requestId);
        tx(WalletTransactionType.SELF_REFUND_OUT, "-10.00", null);

        WalletRefundAllocation r = allocate("40.00");

        assertThat(r.inFlight()).isEqualByComparingTo("40.00");
        assertThat(r.refundableTotal()).isEqualByComparingTo("0");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
        assertThat(r.refundable()).isEmpty();
    }

    @Test
    void deuxDemandesRegleesDansLeDesordre_appariementParFile() {
        WalletTransactionEntity a = topup("50.00");
        WalletTransactionEntity b = topup("30.00");
        UUID requestSurB = UUID.randomUUID();
        UUID requestSurA = UUID.randomUUID();
        item(b, "30.00", WalletRefundItemStatus.REFUNDED, requestSurB);
        item(a, "50.00", WalletRefundItemStatus.REFUNDED, requestSurA);
        tx(WalletTransactionType.SELF_REFUND_OUT, "-50.00", null);
        tx(WalletTransactionType.SELF_REFUND_OUT, "-30.00", null);

        WalletRefundAllocation r = allocate("0");

        assertThat(r.refundable()).isEmpty();
        assertThat(r.refundableTotal()).isEqualByComparingTo("0");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
        assertThat(r.inFlight()).isEqualByComparingTo("0");
    }

    @Test
    void ticketEnfantResolu_consommeLaRechargeEnEchecEtPasLaRechargeFraiche() {
        // Recharge A 30 en échec Stripe, recharge B 20 faite entre-temps, puis résolution admin
        // du ticket enfant sur A. Avant V259, ADMIN_REFUND_OUT retombait en LIFO et consommait B.
        WalletTransactionEntity a = topup("30.00");
        item(a, "30.00", WalletRefundItemStatus.FAILED, UUID.randomUUID());
        clock = clock.plusSeconds(60);
        WalletTransactionEntity b = topup("20.00");
        clock = clock.plusSeconds(60);
        item(a, "30.00", WalletRefundItemStatus.REFUNDED, UUID.randomUUID());
        tx(WalletTransactionType.ADMIN_REFUND_OUT, "-30.00", null);

        WalletRefundAllocation r = allocate("20.00");

        assertThat(r.refundable()).singleElement().satisfies(t -> {
            assertThat(t.walletTransactionId()).isEqualTo(b.getId());
            assertThat(t.remaining()).isEqualByComparingTo("20.00");
        });
        assertThat(r.refundableTotal()).isEqualByComparingTo("20.00");
        assertThat(r.inFlight()).isEqualByComparingTo("0");
        assertThat(r.nonRefundable()).isEqualByComparingTo("0");
    }

    @Test
    void itemFailedSuiviDeLItemPendingDuTicketEnfant_resteEnInFlight() {
        WalletTransactionEntity a = topup("30.00");
        item(a, "30.00", WalletRefundItemStatus.FAILED, UUID.randomUUID());
        clock = clock.plusSeconds(60);
        topup("20.00");
        clock = clock.plusSeconds(60);
        item(a, "30.00", WalletRefundItemStatus.PENDING, UUID.randomUUID());

        WalletRefundAllocation r = allocate("50.00");

        assertThat(r.inFlight()).isEqualByComparingTo("30.00");
        assertThat(r.refundableTotal()).isEqualByComparingTo("20.00");
    }

    @Test
    void seulLItemLePlusRecentCompte_unRefundedPuisUnFailedBloque() {
        WalletTransactionEntity a = topup("40.00");
        item(a, "10.00", WalletRefundItemStatus.REFUNDED, UUID.randomUUID());
        tx(WalletTransactionType.SELF_REFUND_OUT, "-10.00", null);
        clock = clock.plusSeconds(60);
        item(a, "30.00", WalletRefundItemStatus.FAILED, UUID.randomUUID());

        WalletRefundAllocation r = allocate("30.00");

        assertThat(r.refundable()).isEmpty();
        assertThat(r.inFlight()).isEqualByComparingTo("30.00");
    }

    @Test
    void latestItemStatuses_dateNullClasseeLaPlusAncienneEtEgalitesGardees() {
        WalletTransactionEntity a = topup("40.00");
        WalletTransactionEntity b = topup("40.00");
        WalletRefundRequestItemEntity legacy = item(a, "40.00", WalletRefundItemStatus.PENDING, UUID.randomUUID());
        setField(legacy, "createdAt", null);
        item(a, "40.00", WalletRefundItemStatus.REFUNDED, UUID.randomUUID());
        item(b, "40.00", WalletRefundItemStatus.REFUNDED, UUID.randomUUID());
        item(b, "40.00", WalletRefundItemStatus.FAILED, UUID.randomUUID());

        var latest = WalletRefundAllocator.latestItemStatuses(items);

        assertThat(latest.get(a.getId())).containsExactly(WalletRefundItemStatus.REFUNDED);
        assertThat(latest.get(b.getId()))
                .containsExactlyInAnyOrder(WalletRefundItemStatus.REFUNDED, WalletRefundItemStatus.FAILED);
    }

    @Test
    void untouchedTopup_carriesFeeAndNet() {
        topup("40.00");

        WalletRefundAllocation r = WalletRefundAllocator.allocate(ledger, items, new BigDecimal("40.00"),
                Map.of(), fixedFees("1.51", "0"), "EUR");

        assertThat(r.refundable().get(0).fee()).isEqualByComparingTo("1.51");
        assertThat(r.fees()).isEqualByComparingTo("1.51");
        assertThat(r.net()).isEqualByComparingTo("38.49");
        assertThat(r.refundableTotal()).isEqualByComparingTo("40.00");
    }

    @Test
    void spentTopup_noFee() {
        topup("40.00");
        tx(WalletTransactionType.BID_PAYMENT, "-5.00", null);

        WalletRefundAllocation r = WalletRefundAllocator.allocate(ledger, items, new BigDecimal("35.00"),
                Map.of(), fixedFees("1.51", "0"), "EUR");

        assertThat(r.refundable().get(0).fee()).isEqualByComparingTo("0");
        assertThat(r.fees()).isEqualByComparingTo("0");
        assertThat(r.net()).isEqualByComparingTo("35.00");
    }

    @Test
    void pawapayTopup_railAndProvider() {
        WalletTransactionEntity a = pawapayTopup("10000");

        WalletRefundAllocation r = WalletRefundAllocator.allocate(ledger, items, new BigDecimal("10000"),
                Map.of(a.getPaymentRef(), "ORANGE_CIV"), fixedFees("0", "200"), "XOF");

        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).rail()).isEqualTo(WalletRefundRail.PAWAPAY);
        assertThat(r.refundable().get(0).provider()).isEqualTo("ORANGE_CIV");
        assertThat(r.refundable().get(0).fee()).isEqualByComparingTo("200");
    }

    @Test
    void feeAboveRemaining_topupExcludedFromRefundable_butStillCountedInTotalInvariant() {
        // Décision : refundableTotal reste 0.20 (invariant du solde), la recharge figure dans
        // refundable avec fee plafonné à 0.20 (net nul) ; c'est request() qui l'exclut des
        // cibles (remaining - fee <= 0), pas l'allocateur.
        topup("0.20");

        WalletRefundAllocation r = WalletRefundAllocator.allocate(ledger, items, new BigDecimal("0.20"),
                Map.of(), fixedFees("1.51", "0"), "EUR");

        assertThat(r.refundableTotal()).isEqualByComparingTo("0.20");
        assertThat(r.refundable()).hasSize(1);
        assertThat(r.refundable().get(0).fee()).isEqualByComparingTo("0.20");
        assertThat(r.net()).isEqualByComparingTo("0");
    }
}
