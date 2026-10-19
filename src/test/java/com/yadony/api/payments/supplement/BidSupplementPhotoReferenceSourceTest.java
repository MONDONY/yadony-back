package com.yadony.api.payments.supplement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Photo d'un supplément : clé {@code tracking/<bidId>/…} revendiquée, sinon le rapprochement R2 l'effacerait. */
@DataJpaTest
@ActiveProfiles("test")
@Import(BidSupplementPhotoReferenceSource.class)
class BidSupplementPhotoReferenceSourceTest {

    @Autowired BidSupplementRepository supplements;
    @Autowired BidSupplementPhotoReferenceSource source;

    @Test
    void photosDesSupplements_supprimesLogiquementCompris() {
        UUID bid = UUID.randomUUID();
        supplements.saveAndFlush(supplement(bid, "tracking/" + bid + "/1_supplement.jpg", BidSupplementStatus.PROPOSED));
        BidSupplementEntity deleted = supplement(bid, "tracking/" + bid + "/2_supplement.jpg", BidSupplementStatus.DECLINED);
        deleted.softDelete();
        supplements.saveAndFlush(deleted);

        assertThat(source.dedicated()).isTrue();
        assertThat(source.prefixes()).containsExactly("tracking/");
        assertThat(source.referencedKeys("tracking/")).containsExactlyInAnyOrder(
                "tracking/" + bid + "/1_supplement.jpg", "tracking/" + bid + "/2_supplement.jpg");
        assertThat(source.referencedKeys("bids/")).isEmpty();
    }

    private static BidSupplementEntity supplement(UUID bidId, String photoKey, BidSupplementStatus status) {
        BidSupplementEntity s = new BidSupplementEntity();
        s.setBidId(bidId);
        s.setTravelerId(UUID.randomUUID());
        s.setSenderId(UUID.randomUUID());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(status);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("10.00"));
        s.setCommissionAmount(new BigDecimal("1.00"));
        s.setGrossAmount(new BigDecimal("11.00"));
        s.setReasonCode(BidSupplementReason.OTHER);
        s.setPhotoKey(photoKey);
        s.setExpiresAt(LocalDateTime.now().plusHours(2));
        return s;
    }
}
