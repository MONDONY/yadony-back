package com.yadony.api.payments.pawapay;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PawapayOperationEntityAmendmentTest {

    @Test
    void forAmendment_setsPurposeAndAmendmentId_neverAPayment() {
        UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID amendment = UUID.randomUUID();

        PawapayOperationEntity op = PawapayOperationEntity.forAmendment(id, PawapayOperationKind.DEPOSIT, sender,
                amendment, null, new BigDecimal("1100"), "XOF", "ORANGE_SEN", "SN", "+221701234567");

        assertThat(op.getId()).isEqualTo(id);
        assertThat(op.getPurpose()).isEqualTo(PawapayOperationPurpose.BID_AMENDMENT);
        assertThat(op.getBidAmendmentId()).isEqualTo(amendment);
        assertThat(op.getUserId()).isEqualTo(sender);
        assertThat(op.getPaymentId()).isNull();
        assertThat(op.getBidSupplementId()).isNull();
        assertThat(op.getKind()).isEqualTo(PawapayOperationKind.DEPOSIT);
        assertThat(op.getAmount()).isEqualByComparingTo("1100");
    }
}
