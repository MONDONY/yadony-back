package com.yadony.api.requests.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.requests.dto.PackageRequestSearchResponse.MyNegotiation;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationMessageKind;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TravelerNegotiationLookupTest {

    private final NegotiationThreadRepository threadRepository = mock(NegotiationThreadRepository.class);
    private final NegotiationMessageRepository messageRepository = mock(NegotiationMessageRepository.class);
    private final TravelerNegotiationLookup lookup = new TravelerNegotiationLookup(threadRepository, messageRepository);

    private static final UUID TRAVELER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    private static NegotiationThreadEntity thread(UUID requestId, NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        try {
            Field id = t.getClass().getSuperclass().getDeclaredField("id");
            id.setAccessible(true);
            id.set(t, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        t.setPackageRequestId(requestId);
        t.setTravelerId(TRAVELER);
        t.setStatus(status);
        return t;
    }

    private static NegotiationMessageEntity lastMessage(UUID threadId, UUID from) {
        return NegotiationMessageEntity.create(threadId, from, NegotiationMessageKind.COUNTER, BigDecimal.TEN, null);
    }

    @Test
    void visitor_getsNothing_withoutQuery() {
        assertThat(lookup.activeFor(null, List.of(UUID.randomUUID()))).isEmpty();
        verifyNoInteractions(threadRepository, messageRepository);
    }

    @Test
    void emptyPage_getsNothing_withoutQuery() {
        assertThat(lookup.activeFor(TRAVELER, List.of())).isEmpty();
        verifyNoInteractions(threadRepository, messageRepository);
    }

    @Test
    void noActiveThread_skipsMessageQuery() {
        UUID r = UUID.randomUUID();
        when(threadRepository.findActiveByTravelerIdAndPackageRequestIdIn(TRAVELER, List.of(r))).thenReturn(List.of());

        assertThat(lookup.activeFor(TRAVELER, List.of(r))).isEmpty();
        verify(messageRepository, never()).findLastByThreadIdIn(any());
    }

    @Test
    void openThread_turnFollowsTheLastAuthor_inTwoQueries() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        var senderSpokeLast = thread(r1, NegotiationThreadStatus.OPEN);
        var travelerSpokeLast = thread(r2, NegotiationThreadStatus.OPEN);
        var awaitingPayment = thread(r3, NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepository.findActiveByTravelerIdAndPackageRequestIdIn(TRAVELER, List.of(r1, r2, r3)))
                .thenReturn(List.of(senderSpokeLast, travelerSpokeLast, awaitingPayment));
        when(messageRepository.findLastByThreadIdIn(List.of(senderSpokeLast.getId(), travelerSpokeLast.getId())))
                .thenReturn(List.of(lastMessage(senderSpokeLast.getId(), SENDER),
                        lastMessage(travelerSpokeLast.getId(), TRAVELER)));

        var result = lookup.activeFor(TRAVELER, List.of(r1, r2, r3));

        assertThat(result).hasSize(3);
        assertThat(result.get(r1)).isEqualTo(new MyNegotiation(senderSpokeLast.getId(), NegotiationThreadStatus.OPEN, true));
        assertThat(result.get(r2)).isEqualTo(new MyNegotiation(travelerSpokeLast.getId(), NegotiationThreadStatus.OPEN, false));
        assertThat(result.get(r3)).isEqualTo(new MyNegotiation(awaitingPayment.getId(), NegotiationThreadStatus.AWAITING_PAYMENT, false));
    }

    @ParameterizedTest
    @CsvSource({
            "AWAITING_TRIP, true",
            "AWAITING_COMMISSION, true",
            "AWAITING_PAYMENT, false",
            "AWAITING_DEPOSIT, false"
    })
    void waitingStates_turnFollowsTheExpectedAction(NegotiationThreadStatus status, boolean travelerTurn) {
        assertThat(TravelerNegotiationLookup.isTravelerTurn(thread(UUID.randomUUID(), status), TRAVELER, null))
                .isEqualTo(travelerTurn);
    }

    @Test
    void openThreadWithoutMessage_isNotTravelerTurn() {
        assertThat(TravelerNegotiationLookup.isTravelerTurn(
                thread(UUID.randomUUID(), NegotiationThreadStatus.OPEN), TRAVELER, null)).isFalse();
    }
}
