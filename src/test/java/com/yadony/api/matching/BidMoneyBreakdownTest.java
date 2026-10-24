package com.yadony.api.matching;

import com.yadony.api.matching.dto.BidMoneyBreakdown;
import com.yadony.api.matching.dto.BidMoneyBreakdown.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ventilation de l'argent d'un colis modifié ({@link BidMoneyBreakdown#of}) : une ligne par transaction, le total
 * des lignes acquises, puis les totaux par destination de l'argent. Exemple chiffré de la fiche B7 : principal carte
 * net 54 / brut 61,20 et modification espèces net 23,50 / brut 26,80 / commission 3,30.
 */
class BidMoneyBreakdownTest {

    private final UUID bidId = UUID.randomUUID();
    private final UUID amendmentId = UUID.randomUUID();

    private Entry principalCard() {
        return new Entry(BidMoneyBreakdown.KIND_PRINCIPAL, bidId, "STRIPE", new BigDecimal("54.00"),
                new BigDecimal("61.20"), new BigDecimal("7.20"), true);
    }

    private Entry amendmentCash(boolean secured) {
        return new Entry(BidMoneyBreakdown.KIND_AMENDMENT, amendmentId, "CASH", new BigDecimal("23.50"),
                new BigDecimal("26.80"), new BigDecimal("3.30"), secured);
    }

    @Test
    @DisplayName("voyageur : versé en ligne 54, espèces 26,80 dont 3,30 de commission déjà réglée, total 77,50 (nets)")
    void breakdown_cardPlusCash_traveler() {
        BidMoneyBreakdown b = BidMoneyBreakdown.of(false, 2, List.of(principalCard(), amendmentCash(true)));

        assertThat(b.lines()).extracting(BidMoneyBreakdown.Line::kind).containsExactly("PRINCIPAL", "AMENDMENT");
        assertThat(b.lines().get(0).amount()).isEqualByComparingTo("54.00");
        assertThat(b.lines().get(0).method()).isEqualTo("STRIPE");
        assertThat(b.lines().get(0).refId()).isEqualTo(bidId);
        assertThat(b.lines().get(1).amount()).as("net pour le voyageur").isEqualByComparingTo("23.50");
        assertThat(b.lines().get(1).method()).isEqualTo("CASH");
        assertThat(b.lines().get(1).status()).isEqualTo("SECURED");
        assertThat(b.onlineAmount()).isEqualByComparingTo("54.00");
        assertThat(b.cashAmount()).as("brut encaissé").isEqualByComparingTo("26.80");
        assertThat(b.cashCommissionPaid()).isEqualByComparingTo("3.30");
        assertThat(b.total()).isEqualByComparingTo("77.50");
        assertThat(b.total().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("expéditeur : payé en ligne 61,20, à remettre 26,80, total 88 ; aucun net ni commission")
    void breakdown_cardPlusCash_sender() {
        BidMoneyBreakdown b = BidMoneyBreakdown.of(true, 2, List.of(principalCard(), amendmentCash(true)));

        assertThat(b.lines()).extracting(BidMoneyBreakdown.Line::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("61.20"), new BigDecimal("26.80"));
        assertThat(b.onlineAmount()).isEqualByComparingTo("61.20");
        assertThat(b.cashAmount()).isEqualByComparingTo("26.80");
        assertThat(b.total()).isEqualByComparingTo("88.00");
        assertThat(b.cashCommissionPaid()).as("jamais pour l'expéditeur").isNull();
        assertThat(b.lines()).extracting(BidMoneyBreakdown.Line::amount)
                .noneMatch(amount -> amount.compareTo(new BigDecimal("54.00")) == 0
                        || amount.compareTo(new BigDecimal("23.50")) == 0
                        || amount.compareTo(new BigDecimal("3.30")) == 0);
    }

    @Test
    @DisplayName("ligne en attente : affichée PENDING, hors du total et des destinations")
    void breakdown_pendingLineExcludedFromTotal() {
        BidMoneyBreakdown traveler = BidMoneyBreakdown.of(false, 2, List.of(principalCard(), amendmentCash(false)));
        BidMoneyBreakdown sender = BidMoneyBreakdown.of(true, 2, List.of(principalCard(), amendmentCash(false)));

        assertThat(traveler.lines().get(1).status()).isEqualTo("PENDING");
        assertThat(traveler.lines().get(1).amount()).isEqualByComparingTo("23.50");
        assertThat(traveler.total()).isEqualByComparingTo("54.00");
        assertThat(traveler.cashAmount()).isEqualByComparingTo("0.00");
        assertThat(traveler.cashCommissionPaid()).isEqualByComparingTo("0.00");
        assertThat(sender.total()).isEqualByComparingTo("61.20");
        assertThat(sender.onlineAmount()).isEqualByComparingTo("61.20");
        assertThat(sender.cashAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("sans modification acquise ni ouverte : pas de ventilation, l'app garde ses cartes actuelles")
    void breakdown_nullWithoutAmendment() {
        assertThat(BidMoneyBreakdown.of(false, 2, List.of(principalCard()))).isNull();
        assertThat(BidMoneyBreakdown.of(true, 2, List.of())).isNull();
        Entry paidSupplement = new Entry(BidMoneyBreakdown.KIND_SUPPLEMENT, UUID.randomUUID(), "STRIPE",
                new BigDecimal("10.00"), new BigDecimal("11.10"), new BigDecimal("1.10"), true);
        assertThat(BidMoneyBreakdown.of(false, 2, List.of(principalCard(), paidSupplement)))
                .as("supplément seul : comportement actuel").isNull();
    }

    @Test
    @DisplayName("supplément payé : une ligne SUPPLEMENT acquise ; mobile money compté en ligne ; principal espèces")
    void breakdown_supplementAndMobileMoney() {
        UUID supplementId = UUID.randomUUID();
        Entry principalCash = new Entry(BidMoneyBreakdown.KIND_PRINCIPAL, bidId, "CASH", new BigDecimal("40.00"),
                new BigDecimal("44.80"), new BigDecimal("4.80"), true);
        Entry supplement = new Entry(BidMoneyBreakdown.KIND_SUPPLEMENT, supplementId, "CASH", new BigDecimal("10.00"),
                new BigDecimal("11.10"), new BigDecimal("1.10"), true);
        Entry amendmentMm = new Entry(BidMoneyBreakdown.KIND_AMENDMENT, amendmentId,
                BidMoneyBreakdown.methodOf("PAWAPAY"), new BigDecimal("20.00"), new BigDecimal("22.40"),
                new BigDecimal("2.40"), true);

        BidMoneyBreakdown b = BidMoneyBreakdown.of(false, 2, List.of(principalCash, supplement, amendmentMm));

        assertThat(b.lines()).extracting(BidMoneyBreakdown.Line::kind)
                .containsExactly("PRINCIPAL", "SUPPLEMENT", "AMENDMENT");
        assertThat(b.lines().get(2).method()).isEqualTo("MOBILE_MONEY");
        assertThat(b.onlineAmount()).isEqualByComparingTo("20.00");
        assertThat(b.cashAmount()).isEqualByComparingTo("55.90");
        assertThat(b.cashCommissionPaid()).isEqualByComparingTo("5.90");
        assertThat(b.total()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("moyens : rails et moyens hérités ramenés à STRIPE, MOBILE_MONEY ou CASH")
    void methodOf_mapsRails() {
        assertThat(BidMoneyBreakdown.methodOf("STRIPE")).isEqualTo("STRIPE");
        assertThat(BidMoneyBreakdown.methodOf(null)).isEqualTo("STRIPE");
        assertThat(BidMoneyBreakdown.methodOf("PAWAPAY")).isEqualTo("MOBILE_MONEY");
        assertThat(BidMoneyBreakdown.methodOf("MOBILE_MONEY")).isEqualTo("MOBILE_MONEY");
        assertThat(BidMoneyBreakdown.methodOf("WAVE")).isEqualTo("MOBILE_MONEY");
        assertThat(BidMoneyBreakdown.methodOf("ORANGE_MONEY")).isEqualTo("MOBILE_MONEY");
        assertThat(BidMoneyBreakdown.methodOf("CASH")).isEqualTo("CASH");
    }

    @Test
    @DisplayName("devise sans décimale (XOF) : totaux à l'unité")
    void breakdown_zeroDecimalCurrency() {
        Entry principal = new Entry(BidMoneyBreakdown.KIND_PRINCIPAL, bidId, "MOBILE_MONEY", new BigDecimal("35000"),
                new BigDecimal("39200"), new BigDecimal("4200"), true);
        Entry amendment = new Entry(BidMoneyBreakdown.KIND_AMENDMENT, amendmentId, "CASH", new BigDecimal("15000"),
                new BigDecimal("16800"), new BigDecimal("1800"), true);

        BidMoneyBreakdown b = BidMoneyBreakdown.of(true, 0, List.of(principal, amendment));

        assertThat(b.total()).isEqualByComparingTo("56000");
        assertThat(b.total().scale()).isZero();
        assertThat(b.onlineAmount()).isEqualByComparingTo("39200");
        assertThat(b.cashAmount()).isEqualByComparingTo("16800");
    }
}
