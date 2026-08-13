package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookBidPromotionTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentService service;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        service = new PaymentService(userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
            paymentRepository, auditService, eventPublisher,
            PaymentServiceTestFactory.defaultConnectProperties(),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), PaymentServiceTestFactory.stubbedResolver(), org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(),
            PaymentServiceTestFactory.stubbedContacts(),
            mock(com.yadony.api.payments.currency.ActiveCurrencyResolver.class, inv -> "EUR"),
            new com.yadony.api.payments.currency.CurrencyMatchGuard()
);

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("2.0"));
        bid.setPaymentIntentId("pi_xxx");

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", bid.getAnnouncementId());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");

        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", bid.getSenderId());
        sender.setFirstName("Aliou");
    }

    @Test
    void promote_bid_publishes_event_and_audits() {
        when(bidRepository.findByPaymentIntentId("pi_xxx")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));

        service.promoteBidOnPaymentAuthorized("pi_xxx");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.PAYMENT_ESCROWED);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(bid.getId()), eq("BID_CREATED"), eq(sender.getId()), any());

        ArgumentCaptor<BidCreatedEvent> evt = ArgumentCaptor.forClass(BidCreatedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(evt.getValue().getTravelerId()).isEqualTo(announcement.getTravelerId());
    }

    @Test
    void promote_is_idempotent_when_bid_already_pending() {
        bid.setStatus(BidStatus.PENDING);
        when(bidRepository.findByPaymentIntentId("pi_xxx")).thenReturn(Optional.of(bid));

        service.promoteBidOnPaymentAuthorized("pi_xxx");

        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
    }

    @Test
    void promote_does_nothing_when_bid_not_found() {
        when(bidRepository.findByPaymentIntentId("pi_zzz")).thenReturn(Optional.empty());
        service.promoteBidOnPaymentAuthorized("pi_zzz");
        verifyNoInteractions(eventPublisher);
    }
}
