package com.yadony.api.requests.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NegotiationThreadRepositoryDepositTest {

    @Autowired NegotiationThreadRepository repo;

    private NegotiationThreadEntity thread(NegotiationThreadStatus status, LocalDateTime expiresAt) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(UUID.randomUUID());
        t.setTravelerTravelDate(LocalDate.now().plusDays(3));
        t.setTravelerAvailableKg(new BigDecimal("5"));
        t.setStatus(status);
        t.setCurrency("XOF");
        t.setCurrentPriceEur(new BigDecimal("30000"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        t.setDepositExpiresAt(expiresAt);
        return repo.save(t);
    }

    @Test
    void findIdsAwaitingDepositExpiredBefore_returnsOnlyDueAwaitingDepositThreads_oldestFirst() {
        LocalDateTime now = LocalDateTime.now();
        var due1 = thread(NegotiationThreadStatus.AWAITING_DEPOSIT, now.minusMinutes(10));
        var due2 = thread(NegotiationThreadStatus.AWAITING_DEPOSIT, now.minusMinutes(1));
        thread(NegotiationThreadStatus.AWAITING_DEPOSIT, now.plusMinutes(5));      // pas encore échue
        thread(NegotiationThreadStatus.AWAITING_PAYMENT, now.minusMinutes(10));    // mauvais statut

        var ids = repo.findIdsAwaitingDepositExpiredBefore(now, PageRequest.of(0, 10));

        assertThat(ids).containsExactly(due1.getId(), due2.getId());
    }
}
