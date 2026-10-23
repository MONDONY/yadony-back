package com.yadony.api.payments.amendment;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Éligibilité d'un colis à une modification : toutes les conditions à la fois, un code par refus. */
@ExtendWith(MockitoExtension.class)
class BidAmendmentEligibilityTest {

    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    @Mock BidAmendmentWindow window;
    @Mock BidAmendmentRepository amendments;
    @Mock BidSupplementRepository supplements;
    @Mock AnnouncementPriceGridItemRepository gridItems;

    private BidAmendmentProperties properties;
    private BidAmendmentEligibility eligibility;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        properties = new BidAmendmentProperties();
        properties.setEnabled(true);
        eligibility = new BidAmendmentEligibility(properties, window, amendments, supplements, gridItems);

        bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, BID_ID);
        bid.setSenderId(SENDER);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setCurrency("EUR");

        announcement = new AnnouncementEntity();
        BidAmendmentTestSupport.setId(announcement, ANNOUNCEMENT_ID);
        announcement.setCurrency("EUR");
        announcement.setPricePerKg(new BigDecimal("4.50"));
        announcement.setAvailableKg(new BigDecimal("5"));
        announcement.setCapacityUnit(CapacityUnit.KG_EXACT);
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));

        traveler = new UserEntity();
        traveler.setStripeAccountId("acct_1");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        lenient().when(window.deadline(any(), any())).thenReturn(Optional.of(LocalDateTime.of(2026, 10, 25, 10, 0)));
        lenient().when(amendments.findFirstByBidIdAndStatusIn(eq(BID_ID), any())).thenReturn(Optional.empty());
        lenient().when(amendments.countByBidIdAndAcceptedAtNotNull(BID_ID)).thenReturn(0L);
        lenient().when(supplements.existsByBidIdAndStatusIn(eq(BID_ID), any())).thenReturn(false);
        lenient().when(gridItems.existsByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(false);
    }

    @Test
    void blockingReason_eligibleBid_isEmpty() {
        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).isEmpty();
        assertThat(eligibility.allowed(bid, announcement, traveler, SENDER)).isTrue();
    }

    @Test
    void blockingReason_flagOff_isDisabled() {
        properties.setEnabled(false);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-disabled");
        assertThat(eligibility.allowed(bid, announcement, traveler, SENDER)).isFalse();
    }

    @Test
    void blockingReason_handedOver_isInvalidStatus() {
        bid.setStatus(BidStatus.HANDED_OVER);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("invalid-bid-status");
    }

    @Test
    void blockingReason_notTheSender_isNotEligible() {
        assertThat(eligibility.blockingReason(bid, announcement, traveler, STRANGER)).contains("amendment-not-eligible");
        assertThat(eligibility.blockingReason(bid, announcement, traveler, null)).contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_negotiatedThreadBid_isAmendmentNotEligible() {
        bid.setLinkedNegotiationThreadId(UUID.randomUUID());

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_dedicatedTrip_isAmendmentNotEligible() {
        announcement.setLinkedPackageRequestId(UUID.randomUUID());

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_openAmendment_isAlreadyOpen() {
        when(amendments.findFirstByBidIdAndStatusIn(BID_ID, BidAmendmentStatus.OPEN))
                .thenReturn(Optional.of(new BidAmendmentEntity()));

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-already-open");
    }

    @Test
    void blockingReason_openSupplement_isSecondTransactionOpen() {
        when(supplements.existsByBidIdAndStatusIn(BID_ID, BidSupplementStatus.OPEN)).thenReturn(true);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-already-open");
    }

    @Test
    void blockingReason_twoAccepted_isLimitReached() {
        when(amendments.countByBidIdAndAcceptedAtNotNull(BID_ID)).thenReturn(2L);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-limit-reached");
    }

    @Test
    void blockingReason_oneAccepted_isStillAllowed() {
        when(amendments.countByBidIdAndAcceptedAtNotNull(BID_ID)).thenReturn(1L);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).isEmpty();
    }

    @Test
    void blockingReason_windowClosed_isNotEligible() {
        when(window.deadline(bid, announcement)).thenReturn(Optional.empty());

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_noRail_isRailUnavailable() {
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-rail-unavailable");
    }

    @Test
    void blockingReason_noFreeKgAndEmptyGrid_isNotEligible() {
        announcement.setAvailableKg(BigDecimal.ZERO);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_ignoringNothingToAdd_letsCapacityAnswer() {
        // Kilos demandés au-delà des kilos libres : la capacité répond (409), pas « rien à ajouter ».
        announcement.setAvailableKg(BigDecimal.ZERO);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER, true)).isEmpty();
        // Les autres refus restent : fenêtre fermée, mauvais appelant.
        assertThat(eligibility.blockingReason(bid, announcement, traveler, UUID.randomUUID(), true))
                .contains("amendment-not-eligible");
        when(window.deadline(bid, announcement)).thenReturn(Optional.empty());
        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER, true))
                .contains("amendment-not-eligible");
    }

    @Test
    void blockingReason_noFreeKgButGrid_isAllowed() {
        announcement.setAvailableKg(BigDecimal.ZERO);
        when(gridItems.existsByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(true);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).isEmpty();
    }

    @Test
    void blockingReason_kgFree_isAllowedWhateverAvailableKg() {
        announcement.setCapacityUnit(CapacityUnit.KG_FREE);
        announcement.setAvailableKg(BigDecimal.ZERO);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).isEmpty();
    }

    @Test
    void blockingReason_freeKgWithoutPricePerKg_needsGrid() {
        announcement.setPricePerKg(null);

        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).contains("amendment-not-eligible");

        when(gridItems.existsByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(true);
        assertThat(eligibility.blockingReason(bid, announcement, traveler, SENDER)).isEmpty();
    }

    @Test
    void offerableRails_xofTrip_excludesCard() {
        bid.setCurrency("XOF");
        announcement.setCurrency("XOF");
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        traveler.setMobileMoneyCurrencyList(List.of("XOF"));

        assertThat(eligibility.offerableRails(bid, announcement, traveler))
                .containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
    }

    @Test
    void offerableRails_xofTrip_mobileMoneyNeedsPayoutAccount() {
        bid.setCurrency("XOF");
        announcement.setCurrency("XOF");
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));

        assertThat(eligibility.offerableRails(bid, announcement, traveler)).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void offerableRails_eurTrip_cardRequiresConnect() {
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        assertThat(eligibility.offerableRails(bid, announcement, traveler))
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);

        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        assertThat(eligibility.offerableRails(bid, announcement, traveler)).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void offerableRails_excludesLegacyWaveAndOrangeMoney() {
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY, PaymentMethod.CASH));

        assertThat(eligibility.offerableRails(bid, announcement, traveler)).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void offerableRails_onlyAcceptedMethods() {
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));

        assertThat(eligibility.offerableRails(bid, announcement, traveler)).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void offerableRails_unknownTraveler_isEmpty() {
        assertThat(eligibility.offerableRails(bid, announcement, null)).isEmpty();
    }

    @Test
    void offerableRails_nullAcceptedMethods_isEmpty() {
        announcement.setAcceptedPaymentMethods(null);

        assertThat(eligibility.offerableRails(bid, announcement, traveler)).isEmpty();
    }

    @Test
    void exceptionFor_mapsCodesToHttpStatus() {
        assertThat(statusOf("amendment-already-open")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf("amendment-disabled")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf("amendment-not-eligible")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf("amendment-limit-reached")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf("amendment-rail-unavailable")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf("invalid-bid-status")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BidAmendmentEligibility.exceptionFor("amendment-limit-reached").getErrorCode())
                .isEqualTo("amendment-limit-reached");
    }

    private static HttpStatus statusOf(String code) {
        YadonyBusinessException e = BidAmendmentEligibility.exceptionFor(code);
        return e.getStatus();
    }
}
