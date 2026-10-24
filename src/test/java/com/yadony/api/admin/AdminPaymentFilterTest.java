package com.yadony.api.admin;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPaymentFilterTest {

    @Test
    void ancienneFabrique_prendLesValeursParDefaut() {
        AdminPaymentFilter f = AdminPaymentFilter.of("escrow", null, null, "stripe", "eur", true, " pi_x ", false);

        assertThat(f.status()).isEqualTo("ESCROW");
        assertThat(f.rail()).isEqualTo("STRIPE");
        assertThat(f.currency()).isEqualTo("EUR");
        assertThat(f.held()).isTrue();
        assertThat(f.query()).isEqualTo("pi_x");
        assertThat(f.view()).isEqualTo(AdminPaymentView.ALL);
        assertThat(f.alert()).isNull();
        assertThat(f.partyId()).isNull();
        assertThat(f.announcementId()).isNull();
        assertThat(f.bidId()).isNull();
        assertThat(f.amountMin()).isNull();
        assertThat(f.amountMax()).isNull();
    }

    @Test
    void nouvelleFabrique_normaliseVueEtAlerte() {
        UUID party = UUID.randomUUID();
        UUID announcement = UUID.randomUUID();
        UUID bid = UUID.randomUUID();

        AdminPaymentFilter f = AdminPaymentFilter.of(null, null, null, null, null, null, null, null,
                " attention ", "mm_failed", party, announcement, bid, 100L, 500L);

        assertThat(f.view()).isEqualTo(AdminPaymentView.ATTENTION);
        assertThat(f.alert()).isEqualTo(AdminPaymentAlert.MM_FAILED);
        assertThat(f.partyId()).isEqualTo(party);
        assertThat(f.announcementId()).isEqualTo(announcement);
        assertThat(f.bidId()).isEqualTo(bid);
        assertThat(f.amountMin()).isEqualTo(100L);
        assertThat(f.amountMax()).isEqualTo(500L);
    }

    @Test
    void nouvelleFabrique_videDonneLeDefaut() {
        AdminPaymentFilter f = AdminPaymentFilter.of(null, null, null, null, null, null, null, null,
                "  ", "", null, null, null, null, null);

        assertThat(f.view()).isEqualTo(AdminPaymentView.ALL);
        assertThat(f.alert()).isNull();
    }

    @Test
    void alert_ofRank() {
        assertThat(AdminPaymentAlert.ofRank(1)).contains(AdminPaymentAlert.HELD);
        assertThat(AdminPaymentAlert.ofRank(3)).contains(AdminPaymentAlert.MM_FAILED);
        assertThat(AdminPaymentAlert.ofRank(0)).isEmpty();
        assertThat(AdminPaymentAlert.ofRank(9)).isEmpty();
        assertThat(AdminPaymentAlert.ESCROW_J48.rank()).isEqualTo(4);
    }

    @Test
    void sql_alerteEtVuesCouvrentLesQuatreRangsEtLesParametresDocumentes() {
        for (int rank = 1; rank <= 4; rank++) {
            assertThat(AdminPaymentSql.ALERT_RANK).contains("THEN " + rank);
        }
        assertThat(AdminPaymentSql.ALERT_RANK).contains(":now");
        assertThat(AdminPaymentSql.VIEW_PREDICATE).contains(":view", "ATTENTION", "ESCROW", "RELEASED_RECENT",
                "REFUNDED", "FAILED");
        assertThat(AdminPaymentSql.COUNTS_SELECT).contains("\"attention\"", "\"escrow\"", "\"releasedRecent\"",
                "\"refunded\"", "\"failed\"", "\"all\"", "\"held\"", "\"chargebackOpen\"", "\"mmFailed\"",
                "\"escrowJ48\"");
        assertThat(AdminPaymentSql.ORDER_BY).contains(":view");
        assertThat(AdminPaymentSql.FROM_JOIN).contains(" p", " b ", " lb ", " a ", " la ", " t ", " r ", " s ",
                " tr ");
    }

    @Test
    void sql_alertesBrutesDefiniesUneSeuleFois() {
        assertThat(AdminPaymentSql.ALERT_RANK)
                .contains(AdminPaymentSql.HELD_PREDICATE, AdminPaymentSql.CHARGEBACK_OPEN_PREDICATE,
                        AdminPaymentSql.MM_FAILED_PREDICATE, AdminPaymentSql.ESCROW_J48_PREDICATE);
        assertThat(AdminPaymentSql.ALERT_FILTER)
                .contains(AdminPaymentSql.HELD_PREDICATE, AdminPaymentSql.CHARGEBACK_OPEN_PREDICATE,
                        AdminPaymentSql.MM_FAILED_PREDICATE, AdminPaymentSql.ESCROW_J48_PREDICATE)
                .doesNotContain("ALERT_RANK");
        assertThat(AdminPaymentSql.COUNTS_SELECT)
                .contains(" AND " + AdminPaymentSql.ESCROW_J48_PREDICATE + " THEN 1")
                .contains(" AND " + AdminPaymentSql.HELD_PREDICATE + " THEN 1")
                .contains(" AND " + AdminPaymentSql.CHARGEBACK_OPEN_PREDICATE + " THEN 1")
                .contains(" AND " + AdminPaymentSql.MM_FAILED_PREDICATE + " THEN 1")
                .doesNotContain(AdminPaymentSql.ALERT_RANK + " = ")
                .contains(AdminPaymentSql.ABANDONED_PREDICATE);
    }
}
