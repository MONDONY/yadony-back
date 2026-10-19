package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAcceptedOperatorsResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Opérateurs acceptés par le voyageur d'un colis : gardes d'accès et choix de la devise. */
class MobileMoneyBidPaymentServiceAcceptedOperatorsTest {

    private BidRepository bidRepository;
    private AnnouncementRepository announcementRepository;
    private UserRepository userRepository;
    private PaymentRepository paymentRepository;
    private MobileMoneyAcceptedOperatorsService acceptedOperators;
    private MobileMoneyBidPaymentService service;

    private UserEntity traveler;
    private AnnouncementEntity announcement;
    private BidEntity bid;
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bidRepository = mock(BidRepository.class);
        announcementRepository = mock(AnnouncementRepository.class);
        userRepository = mock(UserRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        acceptedOperators = mock(MobileMoneyAcceptedOperatorsService.class);
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                null, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "acceptedOperators", acceptedOperators);

        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setCurrency("XOF");
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(senderId);

        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
    }

    private static MobileMoneyAcceptedOperatorsResponse response(String currency) {
        return new MobileMoneyAcceptedOperatorsResponse(currency, "Aminata", List.of("ORANGE_SEN"));
    }

    private PaymentEntity payment(PaymentRail rail, PaymentStatus status, String currency) {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setRail(rail);
        p.setStatus(status);
        p.setCurrency(currency);
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void acceptedOperators_noPayment_usesAnnouncementCurrency() {
        when(acceptedOperators.forTraveler(traveler, "XOF")).thenReturn(response("XOF"));

        assertThat(service.acceptedOperators(bid.getId(), senderId).providers()).containsExactly("ORANGE_SEN");
        verify(acceptedOperators).forTraveler(traveler, "XOF");
    }

    @Test
    void acceptedOperators_paymentCurrencyWins() {
        payment(PaymentRail.PAWAPAY, PaymentStatus.PENDING, "XAF");
        when(acceptedOperators.forTraveler(traveler, "XAF")).thenReturn(response("XAF"));

        assertThat(service.acceptedOperators(bid.getId(), senderId).currency()).isEqualTo("XAF");
    }

    @Test
    void acceptedOperators_paidBid_noStatusGuard() {
        // Supplément d'un colis déjà payé : aucune garde sur le statut du paiement.
        payment(PaymentRail.PAWAPAY, PaymentStatus.ESCROW, "XOF");
        when(acceptedOperators.forTraveler(traveler, "XOF")).thenReturn(response("XOF"));

        assertThat(service.acceptedOperators(bid.getId(), senderId).providers()).containsExactly("ORANGE_SEN");
    }

    @Test
    void acceptedOperators_nonPawapayPayment_fallsBackOnAnnouncementCurrency() {
        payment(PaymentRail.STRIPE, PaymentStatus.ESCROW, "EUR");
        when(acceptedOperators.forTraveler(traveler, "XOF")).thenReturn(response("XOF"));

        assertThat(service.acceptedOperators(bid.getId(), senderId).currency()).isEqualTo("XOF");
    }

    @Test
    void acceptedOperators_notTheSender_is403() {
        assertThatThrownBy(() -> service.acceptedOperators(bid.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("forbidden");
                });
        verifyNoInteractions(acceptedOperators);
    }

    @Test
    void acceptedOperators_unknownBid_is404() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptedOperators(unknown, senderId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("bid-not-found"));
    }

    @Test
    void acceptedOperators_unknownAnnouncement_is404() {
        when(announcementRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptedOperators(bid.getId(), senderId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("announcement-not-found"));
    }

    @Test
    void acceptedOperators_unknownTraveler_is404() {
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptedOperators(bid.getId(), senderId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("user-not-found"));
    }
}
