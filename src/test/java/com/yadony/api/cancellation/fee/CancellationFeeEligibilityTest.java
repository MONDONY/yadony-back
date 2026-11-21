package com.yadony.api.cancellation.fee;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Décision unique « ce colis peut-il porter des frais d'annulation » : partagée par l'enregistrement de la retenue
 * ({@link SenderCancellationFeeRecorder}) et l'aperçu montré à l'expéditeur, pour que les deux ne divergent jamais.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CancellationFeeEligibility : interrupteur, nouveaux colis seulement, rail payé en ligne")
class CancellationFeeEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-12-10T10:00:00Z");
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-12-01T00:00:00Z");
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);

    @Test
    void disabled_noFee(CapturedOutput output) {
        CancellationFeeEligibility eligibility = eligibility(false, EFFECTIVE_FROM);

        assertThat(eligibility.active()).isFalse();
        assertThat(eligibility.assess(bid(EFFECTIVE_FROM.plus(Duration.ofDays(1)), PaymentMethod.STRIPE), NOW))
                .isEmpty();
        // Éteint volontairement : aucune alerte de configuration.
        assertThat(output).doesNotContain("yadony.cancellation-fee.effective-from");
    }

    @Test
    void enabledWithoutEffectiveDate_isOff_andWarnsAtStartup(CapturedOutput output) {
        CancellationFeeEligibility eligibility = eligibility(true, null);

        assertThat(eligibility.active()).isFalse();
        assertThat(eligibility.assess(bid(EFFECTIVE_FROM.plus(Duration.ofDays(1)), PaymentMethod.STRIPE), NOW))
                .isEmpty();
        assertThat(output).contains("WARN").contains("yadony.cancellation-fee.effective-from");
    }

    @Test
    void parcelCreatedBeforeActivation_neverPays() {
        CancellationFeeEligibility eligibility = eligibility(true, EFFECTIVE_FROM);

        assertThat(eligibility.active()).isTrue();
        assertThat(eligibility.assess(bid(EFFECTIVE_FROM.minusSeconds(1), PaymentMethod.STRIPE), NOW)).isEmpty();
    }

    @Test
    void parcelCreatedAtActivation_isCovered() {
        Optional<CancellationFeeEligibility.Assessment> assessed =
                eligibility(true, EFFECTIVE_FROM).assess(bid(EFFECTIVE_FROM, PaymentMethod.STRIPE), NOW);

        assertThat(assessed).isPresent();
        assertThat(assessed.get().rate()).isEqualByComparingTo("0.20");
        assertThat(assessed.get().trip().getTravelerId()).isEqualTo(TRAVELER_ID);
        assertThat(assessed.get().nextIncreaseAt()).isEqualTo(departure().minus(Duration.ofHours(24)));
    }

    @Test
    void parcelCreatedAfterActivation_mobileMoney_isCovered() {
        assertThat(eligibility(true, EFFECTIVE_FROM)
                .assess(bid(EFFECTIVE_FROM.plus(Duration.ofDays(3)), PaymentMethod.MOBILE_MONEY), NOW))
                .isPresent();
    }

    @Test
    void parcelWithoutCreationDate_isNotCovered() {
        BidEntity unsaved = bid(null, PaymentMethod.STRIPE);

        assertThat(eligibility(true, EFFECTIVE_FROM).assess(unsaved, NOW)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = PaymentMethod.class, names = {"CASH", "WAVE", "ORANGE_MONEY"})
    void notPaidOnline_noFee(PaymentMethod method) {
        assertThat(eligibility(true, EFFECTIVE_FROM).assess(bid(EFFECTIVE_FROM.plusSeconds(60), method), NOW))
                .isEmpty();
    }

    @Test
    void tripNotFound_noFee() {
        CancellationFeeEligibility eligibility = eligibility(true, EFFECTIVE_FROM);
        when(announcements.findById(ANN_ID)).thenReturn(Optional.empty());

        assertThat(eligibility.assess(bid(EFFECTIVE_FROM.plusSeconds(60), PaymentMethod.STRIPE), NOW)).isEmpty();
    }

    @Test
    void farFromDeparture_coveredAtZero_withTheNextIncrease() {
        CancellationFeeEligibility eligibility = eligibility(true, EFFECTIVE_FROM);
        givenTrip(NOW.plus(Duration.ofDays(9)));

        Optional<CancellationFeeEligibility.Assessment> assessed =
                eligibility.assess(bid(EFFECTIVE_FROM.plusSeconds(60), PaymentMethod.STRIPE), NOW);

        assertThat(assessed).isPresent();
        assertThat(assessed.get().rate()).isEqualByComparingTo("0");
        assertThat(assessed.get().nextIncreaseAt()).isEqualTo(NOW.plus(Duration.ofDays(9)).minus(Duration.ofHours(121)));
    }

    // --- helpers ---

    private CancellationFeeEligibility eligibility(boolean enabled, Instant effectiveFrom) {
        givenTrip(departure());
        return new CancellationFeeEligibility(new CancellationFeeProperties(enabled, effectiveFrom, null, null),
                announcements);
    }

    private static Instant departure() {
        return NOW.plus(Duration.ofHours(36));
    }

    private void givenTrip(Instant departure) {
        AnnouncementEntity trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", ANN_ID);
        trip.setTravelerId(TRAVELER_ID);
        OffsetDateTime at = departure.atOffset(ZoneOffset.UTC);
        trip.setDepartureAt(at);
        trip.setDepartureDate(LocalDate.from(at));
        when(announcements.findById(ANN_ID)).thenReturn(Optional.of(trip));
    }

    private static BidEntity bid(Instant createdAt, PaymentMethod method) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(ANN_ID);
        bid.setPaymentMethod(method);
        bid.setCurrency("EUR");
        if (createdAt != null) {
            ReflectionTestUtils.setField(bid, "createdAt", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        }
        return bid;
    }
}
