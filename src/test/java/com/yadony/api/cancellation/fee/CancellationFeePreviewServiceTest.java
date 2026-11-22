package com.yadony.api.cancellation.fee;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationFeePreviewResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Aperçu « que me retiendrait-on si j'annulais maintenant ? » : même décision que l'enregistrement de la retenue
 * ({@link CancellationFeeEligibility}), même calcul du brut que le règlement (port vers {@code payments}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Aperçu des frais d'annulation : expéditeur seul, même décision et même brut que la retenue")
class CancellationFeePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-10T10:00:00Z");
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofHours(48));
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final String SENDER_UID = "uid-sender";
    private static final String TRAVELER_UID = "uid-traveler";

    @Mock UserRepository users;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock CancellationFeeQuotePort quotes;

    private BidEntity bid;

    @BeforeEach
    void setUp() {
        givenUser(SENDER_UID, SENDER_ID);
        givenUser(TRAVELER_UID, TRAVELER_ID);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        ReflectionTestUtils.setField(bid, "createdAt", LocalDateTime.ofInstant(EFFECTIVE_FROM.plusSeconds(3600),
                ZoneOffset.UTC));
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANN_ID);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setCurrency("EUR");
        when(bids.findById(BID_ID)).thenReturn(Optional.of(bid));

        AnnouncementEntity trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", ANN_ID);
        trip.setTravelerId(TRAVELER_ID);
        OffsetDateTime at = DEPARTURE.atOffset(ZoneOffset.UTC);
        trip.setDepartureAt(at);
        trip.setDepartureDate(LocalDate.from(at));
        when(announcements.findById(ANN_ID)).thenReturn(Optional.of(trip));

        // Port : quote du règlement au taux reçu (brut = min(payé × taux, 30) ; payé 100 €).
        when(quotes.quote(eq(BID_ID), any())).thenAnswer(inv -> {
            BigDecimal rate = inv.getArgument(1);
            BigDecimal gross = new BigDecimal("100.00").multiply(rate).min(new BigDecimal("30"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return Optional.of(new CancellationFeeQuotePort.Quote(new BigDecimal("100.00"), "EUR", true, gross,
                    new BigDecimal("100.00").subtract(gross), false));
        });
    }

    @Test
    void senderTwoDaysBeforeDeparture_twentyPercent() {
        CancellationFeePreviewResponse preview = service(enabled()).preview(SENDER_UID, BID_ID);

        assertThat(preview.rate()).isEqualByComparingTo("0.20");
        assertThat(preview.fee()).isEqualByComparingTo("20.00");
        assertThat(preview.currency()).isEqualTo("EUR");
        assertThat(preview.refundAmount()).isEqualByComparingTo("80.00");
        assertThat(preview.nextTierAt()).isEqualTo(DEPARTURE.minus(Duration.ofHours(24)));
        verify(quotes).quote(eq(BID_ID), argThat(r -> r.compareTo(new BigDecimal("0.20")) == 0));
    }

    @Test
    void handedOverBeforeDeparture_isCoveredToo() {
        bid.setStatus(BidStatus.HANDED_OVER);

        assertThat(service(enabled()).preview(SENDER_UID, BID_ID).fee()).isEqualByComparingTo("20.00");
    }

    @Test
    void featureDisabled_noFee_fullRefund_noNextTier() {
        CancellationFeePreviewResponse preview = service(new CancellationFeeProperties(false, EFFECTIVE_FROM, null, null))
                .preview(SENDER_UID, BID_ID);

        assertNoFee(preview, "100.00");
        verify(quotes).quote(eq(BID_ID), argThat(r -> r.signum() == 0));
    }

    @Test
    void enabledWithoutEffectiveDate_noFee() {
        assertNoFee(service(new CancellationFeeProperties(true, null, null, null)).preview(SENDER_UID, BID_ID),
                "100.00");
    }

    @Test
    void parcelCreatedBeforeActivation_noFee() {
        ReflectionTestUtils.setField(bid, "createdAt", LocalDateTime.ofInstant(EFFECTIVE_FROM.minusSeconds(1),
                ZoneOffset.UTC));

        assertNoFee(service(enabled()).preview(SENDER_UID, BID_ID), "100.00");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PAYMENT_ESCROWED", "AWAITING_PAYMENT", "PENDING", "NEGOTIATING",
            "IN_TRANSIT", "CANCELLED"})
    void notAcceptedNorHandedOver_noFee(BidStatus status) {
        bid.setStatus(status);

        assertNoFee(service(enabled()).preview(SENDER_UID, BID_ID), "100.00");
    }

    @Test
    void farFromDeparture_rateZero_withTheInstantFeesStart() {
        AnnouncementEntity far = new AnnouncementEntity();
        ReflectionTestUtils.setField(far, "id", ANN_ID);
        far.setTravelerId(TRAVELER_ID);
        far.setDepartureAt(NOW.plus(Duration.ofDays(9)).atOffset(ZoneOffset.UTC));
        when(announcements.findById(ANN_ID)).thenReturn(Optional.of(far));

        CancellationFeePreviewResponse preview = service(enabled()).preview(SENDER_UID, BID_ID);

        assertThat(preview.rate()).isEqualByComparingTo("0");
        assertThat(preview.fee()).isEqualByComparingTo("0");
        assertThat(preview.refundAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.nextTierAt()).isEqualTo(NOW.plus(Duration.ofDays(9)).minus(Duration.ofDays(5)).minusSeconds(1));
    }

    @Test
    void rescheduleDecisionOpen_noFee_likeTheRecording() {
        bid.setPendingRescheduleId(UUID.randomUUID());
        bid.setHandoverDeadline(LocalDateTime.ofInstant(NOW.plus(Duration.ofHours(6)), ZoneOffset.UTC));

        assertNoFee(service(enabled()).preview(SENDER_UID, BID_ID), "100.00");
        verify(quotes).quote(eq(BID_ID), argThat(r -> r.signum() == 0));
    }

    @Test
    void rescheduleDecisionExpired_feeAsBefore() {
        bid.setPendingRescheduleId(UUID.randomUUID());
        bid.setHandoverDeadline(LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));

        assertThat(service(enabled()).preview(SENDER_UID, BID_ID).fee()).isEqualByComparingTo("20.00");
    }

    @Test
    void paymentThatCannotCarryAFee_noFee_noNextTier() {
        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.of(new CancellationFeeQuotePort.Quote(
                new BigDecimal("100.00"), "EUR", false, BigDecimal.ZERO, new BigDecimal("100.00"), false)));

        assertNoFee(service(enabled()).preview(SENDER_UID, BID_ID), "100.00");
    }

    @Test
    void cash_noFee_nothingPaidOnline() {
        bid.setPaymentMethod(PaymentMethod.CASH);
        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.empty());

        CancellationFeePreviewResponse preview = service(enabled()).preview(SENDER_UID, BID_ID);

        assertNoFee(preview, "0");
        assertThat(preview.currency()).isEqualTo("EUR");
    }

    @Test
    void capped_relayedFromTheQuote_onlyWhenAFeeApplies() {
        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.of(new CancellationFeeQuotePort.Quote(
                new BigDecimal("200.00"), "EUR", true, new BigDecimal("30.00"), new BigDecimal("170.00"), true)));
        assertThat(service(enabled()).preview(SENDER_UID, BID_ID).capped()).isTrue();

        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.of(new CancellationFeeQuotePort.Quote(
                new BigDecimal("100.00"), "EUR", true, new BigDecimal("20.00"), new BigDecimal("80.00"), false)));
        assertThat(service(enabled()).preview(SENDER_UID, BID_ID).capped()).isFalse();

        // Pas retenable : même si le port disait vrai, aucun frais ne s'applique.
        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.of(new CancellationFeeQuotePort.Quote(
                new BigDecimal("200.00"), "EUR", false, BigDecimal.ZERO, new BigDecimal("200.00"), true)));
        assertThat(service(enabled()).preview(SENDER_UID, BID_ID).capped()).isFalse();
    }

    @Test
    void currencyIsThatOfThePayment() {
        when(quotes.quote(eq(BID_ID), any())).thenReturn(Optional.of(new CancellationFeeQuotePort.Quote(
                new BigDecimal("65600"), "XOF", true, new BigDecimal("13120"), new BigDecimal("52480"), false)));

        CancellationFeePreviewResponse preview = service(enabled()).preview(SENDER_UID, BID_ID);

        assertThat(preview.currency()).isEqualTo("XOF");
        assertThat(preview.fee()).isEqualByComparingTo("13120");
        assertThat(preview.refundAmount()).isEqualByComparingTo("52480");
    }

    @Test
    void traveler_403() {
        assertThatThrownBy(() -> service(enabled()).preview(TRAVELER_UID, BID_ID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(quotes);
    }

    @Test
    void unknownBid_404() {
        UUID unknown = UUID.randomUUID();
        when(bids.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(enabled()).preview(SENDER_UID, unknown))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownCaller_401() {
        when(users.findByFirebaseUid("uid-ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(enabled()).preview("uid-ghost", BID_ID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // --- helpers ---

    private static CancellationFeeProperties enabled() {
        return new CancellationFeeProperties(true, EFFECTIVE_FROM, null, null);
    }

    private CancellationFeePreviewService service(CancellationFeeProperties properties) {
        return new CancellationFeePreviewService(users, bids, new CancellationFeeEligibility(properties, announcements),
                quotes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenUser(String uid, UUID id) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        when(users.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
    }

    private static void assertNoFee(CancellationFeePreviewResponse preview, String refund) {
        assertThat(preview.rate()).isEqualByComparingTo("0");
        assertThat(preview.fee()).isEqualByComparingTo("0");
        assertThat(preview.refundAmount()).isEqualByComparingTo(refund);
        assertThat(preview.nextTierAt()).isNull();
    }
}
