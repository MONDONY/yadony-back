package com.yadony.api.requests.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationMessageKind;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Requêtes en lot de la recherche des demandes (FLUTTER-NZ). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NegotiationMyNegotiationQueriesDbTest {

    @Autowired NegotiationThreadRepository threads;
    @Autowired NegotiationMessageRepository messages;

    private NegotiationThreadEntity thread(UUID requestId, UUID travelerId, NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(requestId);
        t.setTravelerId(travelerId);
        t.setTravelerTravelDate(LocalDate.now().plusDays(3));
        t.setTravelerAvailableKg(new BigDecimal("5"));
        t.setStatus(status);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("30"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return threads.saveAndFlush(t);
    }

    @Test
    void findActiveByTravelerIdAndPackageRequestIdIn_keepsOnlyActiveThreadsOfTheTravelerOnTheGivenRequests() {
        UUID traveler = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        var open = thread(r1, traveler, NegotiationThreadStatus.OPEN);
        var awaitingPayment = thread(r2, traveler, NegotiationThreadStatus.AWAITING_PAYMENT);
        thread(r3, traveler, NegotiationThreadStatus.EXPIRED);              // terminé
        thread(r1, UUID.randomUUID(), NegotiationThreadStatus.OPEN);         // autre voyageur
        thread(UUID.randomUUID(), traveler, NegotiationThreadStatus.OPEN);   // hors page

        var found = threads.findActiveByTravelerIdAndPackageRequestIdIn(traveler, List.of(r1, r2, r3));

        assertThat(found).extracting(NegotiationThreadEntity::getId)
                .containsExactlyInAnyOrder(open.getId(), awaitingPayment.getId());
    }

    @Test
    void findLastByThreadIdIn_returnsTheLatestMessageOfEachThread() throws InterruptedException {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        var t1 = thread(UUID.randomUUID(), traveler, NegotiationThreadStatus.OPEN);
        var t2 = thread(UUID.randomUUID(), traveler, NegotiationThreadStatus.OPEN);
        messages.saveAndFlush(NegotiationMessageEntity.create(t1.getId(), traveler,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null));
        Thread.sleep(5);
        messages.saveAndFlush(NegotiationMessageEntity.create(t1.getId(), sender,
                NegotiationMessageKind.COUNTER, new BigDecimal("25"), null));
        messages.saveAndFlush(NegotiationMessageEntity.create(t2.getId(), traveler,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("40"), null));

        var last = messages.findLastByThreadIdIn(List.of(t1.getId(), t2.getId()));

        assertThat(last).hasSize(2);
        assertThat(last).filteredOn(m -> m.getThreadId().equals(t1.getId()))
                .singleElement().extracting(NegotiationMessageEntity::getFromUserId).isEqualTo(sender);
        assertThat(last).filteredOn(m -> m.getThreadId().equals(t2.getId()))
                .singleElement().extracting(NegotiationMessageEntity::getFromUserId).isEqualTo(traveler);
    }
}
