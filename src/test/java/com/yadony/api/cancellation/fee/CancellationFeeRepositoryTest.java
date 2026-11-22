package com.yadony.api.cancellation.fee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CancellationFeeRepositoryTest {

    @Autowired CancellationFeeRepository repository;

    private CancellationFeeEntity fee(UUID bidId, CancellationFeeStatus status) {
        CancellationFeeEntity f = new CancellationFeeEntity();
        f.setBidId(bidId);
        f.setSenderId(UUID.randomUUID());
        f.setTravelerId(UUID.randomUUID());
        f.setRate(new BigDecimal("0.2000"));
        f.setReason(CancellationFeeReason.SENDER_CANCEL);
        f.setStatus(status);
        f.setGrossFee(new BigDecimal("10.00"));
        f.setRefundFees(new BigDecimal("1.20"));
        f.setCommission(new BigDecimal("1.00"));
        f.setTravelerNet(new BigDecimal("8.80"));
        f.setCurrency("EUR");
        return f;
    }

    @Test
    void findByBidId_retrouveLaLigneAvecSesMontants() {
        UUID bidId = UUID.randomUUID();
        repository.saveAndFlush(fee(bidId, CancellationFeeStatus.DUE));

        CancellationFeeEntity found = repository.findByBidId(bidId).orElseThrow();

        assertThat(found.getGrossFee()).isEqualByComparingTo("10.00");
        assertThat(found.getTravelerNet()).isEqualByComparingTo("8.80");
        assertThat(found.getReason()).isEqualTo(CancellationFeeReason.SENDER_CANCEL);
        assertThat(found.getStatus()).isEqualTo(CancellationFeeStatus.DUE);
        assertThat(found.getPayoutRef()).isNull();
    }

    @Test
    void findByBidId_inconnu_estVide() {
        assertThat(repository.findByBidId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void deuxFraisPourLeMemeBid_violentLUnicite() {
        UUID bidId = UUID.randomUUID();
        repository.saveAndFlush(fee(bidId, CancellationFeeStatus.DUE));

        assertThatThrownBy(() -> repository.saveAndFlush(fee(bidId, CancellationFeeStatus.RETAINED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
