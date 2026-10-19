package com.yadony.api.common.commission;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.promo.PromoRedemptionEntity;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un taux effectif de commission (ex. 1/15 = 0,066667) ne doit pas être tronqué à 3 décimales :
 * les colonnes de taux sont en NUMERIC(7,6) depuis V332 et le mapping JPA doit suivre.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CommissionRatePrecisionPersistenceTest {

    private static final BigDecimal RATE = new BigDecimal("0.066667");

    @Autowired private TestEntityManager em;

    @Test
    void bid_keepsSixDecimalRatesAndFrozenGrid() {
        UUID gridId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setCommissionRate(RATE);
        bid.setCommissionGridId(gridId);
        bid.setCommissionOverrideRate(new BigDecimal("0.033333"));
        bid.setCommissionVoucherFactor(new BigDecimal("0.500"));
        UUID id = em.persistAndFlush(bid).getId();
        em.clear();

        BidEntity reloaded = em.find(BidEntity.class, id);

        assertThat(reloaded.getCommissionRate()).isEqualByComparingTo("0.066667");
        assertThat(reloaded.getCommissionOverrideRate()).isEqualByComparingTo("0.033333");
        assertThat(reloaded.getCommissionVoucherFactor()).isEqualByComparingTo("0.500");
        assertThat(reloaded.getCommissionGridId()).isEqualTo(gridId);
    }

    @Test
    void negotiationThread_keepsSixDecimalRateAndGrid() {
        UUID gridId = UUID.randomUUID();
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(UUID.randomUUID());
        thread.setTravelerId(UUID.randomUUID());
        thread.setTravelerTravelDate(LocalDate.now().plusDays(5));
        thread.setTravelerAvailableKg(new BigDecimal("10"));
        thread.setStatus(NegotiationThreadStatus.OPEN);
        thread.setCurrentPriceEur(new BigDecimal("30"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now());
        thread.setCommissionRate(RATE);
        thread.setCommissionGridId(gridId);
        UUID id = em.persistAndFlush(thread).getId();
        em.clear();

        NegotiationThreadEntity reloaded = em.find(NegotiationThreadEntity.class, id);

        assertThat(reloaded.getCommissionRate()).isEqualByComparingTo("0.066667");
        assertThat(reloaded.getCommissionGridId()).isEqualTo(gridId);
    }

    @Test
    void promoRedemption_keepsSixDecimalAppliedRate() {
        PromoRedemptionEntity redemption = new PromoRedemptionEntity();
        redemption.setPromoCodeId(UUID.randomUUID());
        redemption.setUserId(UUID.randomUUID());
        redemption.setBidId(UUID.randomUUID());
        redemption.setAppliedRate(RATE);
        redemption.setRedeemedAt(LocalDateTime.now());
        UUID id = em.persistAndFlush(redemption).getId();
        em.clear();

        PromoRedemptionEntity reloaded = em.find(PromoRedemptionEntity.class, id);

        assertThat(reloaded.getAppliedRate()).isEqualByComparingTo("0.066667");
    }
}
