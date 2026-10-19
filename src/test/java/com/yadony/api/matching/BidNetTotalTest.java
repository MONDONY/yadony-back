package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BidNetTotalTest {

    @Test
    void negocie_lAccordFigeEstLeTotal_sansRecomposerGrilleNiKilo() {
        BidEntity bid = bid("2.00");
        bid.setNegotiatedNetEur(new BigDecimal("37.456"));

        assertThat(BidNetTotal.compute(bid, announcement("8.00"), List.of(item("15.00", 2))).toPlainString())
                .isEqualTo("37.46");
    }

    @Test
    void grillePlusKilo_sommeDesArticlesEtDuPoidsAuPrixDuTrajet() {
        assertThat(BidNetTotal.compute(bid("2.50"), announcement("8.00"), List.of(item("15.00", 2), item("4.50", 1))))
                .isEqualByComparingTo("54.50");
    }

    @Test
    void kiloSeul_ouSansTrajet() {
        assertThat(BidNetTotal.compute(bid("3.00"), announcement("8.00"), List.of())).isEqualByComparingTo("24.00");
        assertThat(BidNetTotal.compute(bid("3.00"), null, List.of())).isEqualByComparingTo("0.00");
        assertThat(BidNetTotal.compute(bid(null), announcement("8.00"), List.of(item("15.00", 1))))
                .isEqualByComparingTo("15.00");
    }

    private static BidEntity bid(String weight) {
        BidEntity bid = new BidEntity();
        bid.setWeightKg(weight == null ? null : new BigDecimal(weight));
        return bid;
    }

    private static AnnouncementEntity announcement(String pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setPricePerKg(new BigDecimal(pricePerKg));
        return a;
    }

    private static BidGridItemEntity item(String unitNet, int quantity) {
        BidGridItemEntity i = new BidGridItemEntity();
        i.setUnitPriceNetSnapshot(new BigDecimal(unitNet));
        i.setQuantity(quantity);
        return i;
    }
}
