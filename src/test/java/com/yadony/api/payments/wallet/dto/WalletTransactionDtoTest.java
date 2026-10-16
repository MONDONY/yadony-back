package com.yadony.api.payments.wallet.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FLUTTER-QP : {@code bidId} ajouté sans toucher aux champs existants. */
class WalletTransactionDtoTest {

    private static WalletTransactionEntity tx(UUID bidId) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setType(WalletTransactionType.COMMISSION_DEDUCTED);
        tx.setAmount(new BigDecimal("-2.40"));
        tx.setCurrency("eur");
        tx.setBalanceAfter(new BigDecimal("17.60"));
        tx.setPaymentRef("ref");
        tx.setBidId(bidId);
        return tx;
    }

    @Test
    void sansColis_bidIdNul() {
        assertThat(WalletTransactionDto.from(tx(null), null, null, null, null).getBidId()).isNull();
    }

    @Test
    void bidIdResolu_prioritaire_etChampsExistantsInchanges() {
        UUID resolved = UUID.randomUUID();
        WalletTransactionDto dto = WalletTransactionDto.from(tx(null), "PENDING",
                new BigDecimal("0.50"), new BigDecimal("1.50"), resolved);

        assertThat(dto.getBidId()).isEqualTo(resolved);
        assertThat(dto.getType()).isEqualTo("COMMISSION_DEDUCTED");
        assertThat(dto.getAmount()).isEqualByComparingTo("-2.40");
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getBalanceAfter()).isEqualByComparingTo("17.60");
        assertThat(dto.getPaymentRef()).isEqualTo("ref");
        assertThat(dto.getRefundStatus()).isEqualTo("PENDING");
        assertThat(dto.getFeeAmount()).isEqualByComparingTo("0.50");
        assertThat(dto.getNetAmount()).isEqualByComparingTo("1.50");
    }

    @Test
    void nomJson_bidId() throws Exception {
        UUID bid = UUID.randomUUID();
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(WalletTransactionDto.from(tx(bid), null, null, null, bid));
        assertThat(json).contains("\"bidId\":\"" + bid + "\"");
    }
}
