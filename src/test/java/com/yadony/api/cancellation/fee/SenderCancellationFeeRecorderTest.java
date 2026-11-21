package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.SenderNoShowBidCancelListener;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Déclencheurs et règles de l'enregistrement de la retenue (ligne {@code DUE}). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SenderCancellationFeeRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID FEE_ID = UUID.randomUUID();
    /** Date d'effet de l'interrupteur : les colis de ce test sont créés après, sauf mention contraire. */
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T08:00:00Z");

    @Mock CancellationFeeRepository fees;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock AuditService auditService;

    private SenderCancellationFeeRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = recorder(new CancellationFeeProperties(true, EFFECTIVE_FROM, null, null));
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(fees.save(any(CancellationFeeEntity.class))).thenAnswer(inv -> {
            CancellationFeeEntity f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", FEE_ID);
            return f;
        });
    }

    // --- annulation par l'expéditeur (BidService.cancelBid) ---

    @Test
    void bidCancelledBySender_accepted_recordsDue() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        CancellationFeeEntity due = savedFee();
        assertThat(due.getBidId()).isEqualTo(BID_ID);
        assertThat(due.getSenderId()).isEqualTo(SENDER_ID);
        assertThat(due.getTravelerId()).isEqualTo(TRAVELER_ID);
        assertThat(due.getStatus()).isEqualTo(CancellationFeeStatus.DUE);
        assertThat(due.getReason()).isEqualTo(CancellationFeeReason.SENDER_CANCEL);
        assertThat(due.getRate()).isEqualByComparingTo("0.20");
        // Montants réglés au remboursement (paiements) : nuls jusque-là, devise du colis.
        assertThat(due.getGrossFee()).isEqualByComparingTo("0");
        assertThat(due.getRefundFees()).isEqualByComparingTo("0");
        assertThat(due.getCommission()).isEqualByComparingTo("0");
        assertThat(due.getTravelerNet()).isEqualByComparingTo("0");
        assertThat(due.getCurrency()).isEqualTo("EUR");
        assertThat(due.getPayoutRef()).isNull();
    }

    @Test
    void bidCancelledBySender_handedOver_recordsDue() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.MOBILE_MONEY, "XOF", Duration.ofHours(12));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.HANDED_OVER, PaymentMethod.MOBILE_MONEY));

        CancellationFeeEntity due = savedFee();
        assertThat(due.getReason()).isEqualTo(CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER);
        assertThat(due.getRate()).isEqualByComparingTo("0.30");
        assertThat(due.getCurrency()).isEqualTo("XOF");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PAYMENT_ESCROWED", "AWAITING_PAYMENT", "PENDING", "NEGOTIATING"})
    void bidCancelledBySender_paymentEscrowed_noFee(BidStatus previousStatus) {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(12));

        recorder.onBidCancelledByParticipant(senderCancel(previousStatus, PaymentMethod.STRIPE));

        verifyNothingRecorded();
    }

    @Test
    void bidCancelledByTraveler_noFee() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(12));

        recorder.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                BID_ID, ANN_ID, TRAVELER_ID, true, BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        verifyNothingRecorded();
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = PaymentMethod.class, names = {"CASH", "WAVE", "ORANGE_MONEY"})
    void cashPayment_noFee(PaymentMethod method) {
        givenBid(BidStatus.CANCELLED, method, "EUR", Duration.ofHours(12));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, method));
        recorder.record(BID_ID, CancellationFeeReason.SENDER_NO_SHOW);

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void moreThanFiveDaysBeforeDeparture_rateZero_noFee() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofDays(10));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // --- report du trajet en attente de la réponse de l'expéditeur ---

    @Test
    void bidCancelledBySender_rescheduleDecisionOpen_noFee() {
        BidEntity bid = givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));
        bid.setPendingRescheduleId(UUID.randomUUID());
        bid.setHandoverDeadline(LocalDateTime.ofInstant(NOW.plus(Duration.ofHours(6)), ZoneOffset.UTC));

        // Annulation ordinaire (PUT /bids/{id}/cancel) au lieu du bouton « Me retirer » du report : même exemption.
        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));
        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER);

        verifyNothingRecorded();
    }

    @Test
    void bidCancelledBySender_rescheduleDecisionExpired_recordsDue() {
        BidEntity bid = givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));
        bid.setPendingRescheduleId(UUID.randomUUID());
        bid.setHandoverDeadline(LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        assertThat(savedFee().getRate()).isEqualByComparingTo("0.20");
    }

    @Test
    void statusAtFault_isTheStatusTheCancellationLeft() {
        assertThat(SenderCancellationFeeRecorder.statusAtFault(CancellationFeeReason.SENDER_CANCEL))
                .isEqualTo(BidStatus.ACCEPTED);
        assertThat(SenderCancellationFeeRecorder.statusAtFault(CancellationFeeReason.SENDER_NO_SHOW))
                .isEqualTo(BidStatus.ACCEPTED);
        assertThat(SenderCancellationFeeRecorder.statusAtFault(CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER))
                .isEqualTo(BidStatus.HANDED_OVER);
    }

    // --- absence de l'expéditeur confirmée (SenderNoShowBidCancelListener) ---

    @Test
    void senderNoShowConfirmed_recordsDue() {
        BidEntity bid = givenBid(BidStatus.ACCEPTED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(30));
        SenderNoShowBidCancelListener noShowListener = new SenderNoShowBidCancelListener(bids, recorder);

        noShowListener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, UUID.randomUUID(), CancellationReason.SENDER_NO_SHOW));

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        CancellationFeeEntity due = savedFee();
        assertThat(due.getReason()).isEqualTo(CancellationFeeReason.SENDER_NO_SHOW);
        assertThat(due.getRate()).isEqualByComparingTo("0.20");
    }

    @Test
    void senderNoShowConfirmed_bidNoLongerAccepted_noFee() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(30));
        SenderNoShowBidCancelListener noShowListener = new SenderNoShowBidCancelListener(bids, recorder);

        noShowListener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, UUID.randomUUID(), CancellationReason.SENDER_NO_SHOW));

        verify(fees, never()).save(any());
    }

    // --- idempotence, audit ---

    @Test
    void recordTwice_singleRow() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));
        AtomicReference<CancellationFeeEntity> stored = new AtomicReference<>();
        when(fees.findByBidId(BID_ID)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(fees.save(any(CancellationFeeEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL);
        recorder.record(BID_ID, CancellationFeeReason.SENDER_NO_SHOW);

        verify(fees, times(1)).save(any());
        assertThat(stored.get().getReason()).isEqualTo(CancellationFeeReason.SENDER_CANCEL);
        verify(auditService, times(1)).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void recordedDue_isAudited() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));

        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq(SenderCancellationFeeRecorder.AUDIT_FEE_DUE), isNull(),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("cancellationFeeId", FEE_ID.toString())
                .containsEntry("reason", "SENDER_CANCEL")
                .containsEntry("senderId", SENDER_ID.toString())
                .containsEntry("travelerId", TRAVELER_ID.toString());
        assertThat(new BigDecimal((String) payload.getValue().get("rate"))).isEqualByComparingTo("0.20");
    }

    @Test
    void unknownBidOrTrip_noFee() {
        when(bids.findById(BID_ID)).thenReturn(Optional.empty());
        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL);

        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(12));
        when(announcements.findById(ANN_ID)).thenReturn(Optional.empty());
        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL);

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // --- interrupteur d'activation (nouveaux colis seulement) ---

    @Test
    void featureDisabled_noRow() {
        recorder = recorder(new CancellationFeeProperties(false, EFFECTIVE_FROM, null, null));
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));
        recorder.record(BID_ID, CancellationFeeReason.SENDER_NO_SHOW);

        verifyNothingRecorded();
    }

    @Test
    void enabledWithoutEffectiveDate_noRow() {
        recorder = recorder(new CancellationFeeProperties(true, null, null, null));
        givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));

        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL);

        verifyNothingRecorded();
    }

    @Test
    void bidCreatedBeforeEffectiveDate_noRow() {
        BidEntity bid = givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));
        ReflectionTestUtils.setField(bid, "createdAt",
                LocalDateTime.ofInstant(EFFECTIVE_FROM.minusSeconds(1), ZoneOffset.UTC));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        verifyNothingRecorded();
    }

    @Test
    void bidCreatedAtEffectiveDate_recordsDue() {
        BidEntity bid = givenBid(BidStatus.CANCELLED, PaymentMethod.STRIPE, "EUR", Duration.ofHours(36));
        ReflectionTestUtils.setField(bid, "createdAt", LocalDateTime.ofInstant(EFFECTIVE_FROM, ZoneOffset.UTC));

        recorder.onBidCancelledByParticipant(senderCancel(BidStatus.ACCEPTED, PaymentMethod.STRIPE));

        assertThat(savedFee().getRate()).isEqualByComparingTo("0.20");
    }

    @Test
    void bidCreatedAfterEffectiveDate_recordsDue() {
        givenBid(BidStatus.CANCELLED, PaymentMethod.MOBILE_MONEY, "XOF", Duration.ofHours(12));

        recorder.record(BID_ID, CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER);

        assertThat(savedFee().getRate()).isEqualByComparingTo("0.30");
    }

    // --- helpers ---

    private SenderCancellationFeeRecorder recorder(CancellationFeeProperties properties) {
        return new SenderCancellationFeeRecorder(fees, bids, new CancellationFeeEligibility(properties, announcements),
                auditService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BidCancelledByParticipantEvent senderCancel(BidStatus previousStatus, PaymentMethod method) {
        return new BidCancelledByParticipantEvent(BID_ID, ANN_ID, SENDER_ID, false, previousStatus, method);
    }

    private BidEntity givenBid(BidStatus status, PaymentMethod method, String currency, Duration beforeDeparture) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANN_ID);
        bid.setStatus(status);
        bid.setPaymentMethod(method);
        bid.setCurrency(currency);
        ReflectionTestUtils.setField(bid, "createdAt", LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        when(bids.findById(BID_ID)).thenReturn(Optional.of(bid));

        AnnouncementEntity trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", ANN_ID);
        trip.setTravelerId(TRAVELER_ID);
        OffsetDateTime departure = NOW.plus(beforeDeparture).atOffset(ZoneOffset.UTC);
        trip.setDepartureAt(departure);
        trip.setDepartureDate(LocalDate.from(departure));
        when(announcements.findById(ANN_ID)).thenReturn(Optional.of(trip));
        return bid;
    }

    private CancellationFeeEntity savedFee() {
        ArgumentCaptor<CancellationFeeEntity> captor = ArgumentCaptor.forClass(CancellationFeeEntity.class);
        verify(fees).save(captor.capture());
        return captor.getValue();
    }

    private void verifyNothingRecorded() {
        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
