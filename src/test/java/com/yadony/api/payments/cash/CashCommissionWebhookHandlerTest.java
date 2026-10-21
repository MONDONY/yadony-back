package com.yadony.api.payments.cash;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.event.CommissionMethodDetachedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashCommissionWebhookHandlerTest {

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private CashCommissionService cashCommissionService;

    private CashCommissionWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CashCommissionWebhookHandler(userRepo, bidRepo, events, cashCommissionService);
    }

    // ===================== handleSetupIntentSucceeded =====================

    @Nested
    class HandleSetupIntentSucceeded {

        @Test
        void savesCardDetailsToUser() throws StripeException {
            SetupIntent si = new SetupIntent();
            si.setCustomer("cus_test");
            si.setPaymentMethod("pm_test");

            com.stripe.model.PaymentMethod.Card card = mock(com.stripe.model.PaymentMethod.Card.class);
            when(card.getBrand()).thenReturn("visa");
            when(card.getLast4()).thenReturn("4242");
            when(card.getExpMonth()).thenReturn(12L);
            when(card.getExpYear()).thenReturn(2028L);

            com.stripe.model.PaymentMethod mockPm = mock(com.stripe.model.PaymentMethod.class);
            when(mockPm.getCard()).thenReturn(card);

            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            when(userRepo.findByStripeCustomerId("cus_test")).thenReturn(Optional.of(user));

            Event event = eventFor(si);

            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic = mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_test")).thenReturn(mockPm);
                handler.handleSetupIntentSucceeded(event);
            }

            assertThat(user.getCommissionPaymentMethodId()).isEqualTo("pm_test");
            assertThat(user.getCommissionCardBrand()).isEqualTo("visa");
            assertThat(user.getCommissionCardLast4()).isEqualTo("4242");
            assertThat(user.getCommissionCardExpMonth()).isEqualTo(12);
            assertThat(user.getCommissionCardExpYear()).isEqualTo(2028);
            verify(userRepo).save(user);
        }

        @Test
        void skipsWhenPmIsNotCard() throws StripeException {
            SetupIntent si = new SetupIntent();
            si.setCustomer("cus_test");
            si.setPaymentMethod("pm_non_card");

            com.stripe.model.PaymentMethod mockPm = mock(com.stripe.model.PaymentMethod.class);
            when(mockPm.getCard()).thenReturn(null);

            Event event = eventFor(si);

            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic = mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_non_card")).thenReturn(mockPm);
                handler.handleSetupIntentSucceeded(event);
            }

            verifyNoInteractions(userRepo);
        }

        @Test
        void skipsWhenCustomerNotFound() throws StripeException {
            SetupIntent si = new SetupIntent();
            si.setCustomer("cus_unknown");
            si.setPaymentMethod("pm_test");

            com.stripe.model.PaymentMethod.Card card = mock(com.stripe.model.PaymentMethod.Card.class);
            com.stripe.model.PaymentMethod mockPm = mock(com.stripe.model.PaymentMethod.class);
            when(mockPm.getCard()).thenReturn(card);

            when(userRepo.findByStripeCustomerId("cus_unknown")).thenReturn(Optional.empty());

            Event event = eventFor(si);

            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic = mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_test")).thenReturn(mockPm);
                handler.handleSetupIntentSucceeded(event);
            }

            verify(userRepo, never()).save(any());
        }
    }

    // ===================== handlePaymentIntentSucceeded =====================

    @Nested
    class HandlePaymentIntentSucceeded {

        @Test
        void marksBidAsChargedForCommissionPi() {
            BidEntity bid = new BidEntity();
            UUID bidId = UUID.randomUUID();
            ReflectionTestUtils.setField(bid, "id", bidId);
            bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

            PaymentIntent pi = new PaymentIntent();
            pi.setId("pi_test");
            Map<String, String> meta = new HashMap<>();
            meta.put("commission_purpose", "cash_bid");
            meta.put("bid_id", bidId.toString());
            pi.setMetadata(meta);

            when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
            Event event = eventFor(pi);

            handler.handlePaymentIntentSucceeded(event);

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_test");
            verify(bidRepo).save(bid);
        }

        @Test
        void ignoresNonCommissionPi() {
            PaymentIntent pi = new PaymentIntent();
            pi.setId("pi_escrow");
            pi.setMetadata(new HashMap<>());

            Event event = eventFor(pi);
            handler.handlePaymentIntentSucceeded(event);

            verifyNoInteractions(bidRepo);
        }

        @Test
        void isIdempotentWhenAlreadyCharged() {
            BidEntity bid = new BidEntity();
            UUID bidId = UUID.randomUUID();
            ReflectionTestUtils.setField(bid, "id", bidId);
            bid.setCommissionStatus(CommissionStatus.CHARGED);

            PaymentIntent pi = new PaymentIntent();
            Map<String, String> meta = new HashMap<>();
            meta.put("commission_purpose", "cash_bid");
            meta.put("bid_id", bidId.toString());
            pi.setMetadata(meta);

            when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
            Event event = eventFor(pi);

            handler.handlePaymentIntentSucceeded(event);

            verify(bidRepo, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = CommissionStatus.class, names = {"REFUNDED", "REFUND_FAILED"})
        void neverRewritesAnAlreadyRefundedCommission(CommissionStatus settled) {
            BidEntity bid = commissionBid(BidStatus.CANCELLED, settled);

            handler.handlePaymentIntentSucceeded(eventFor(commissionPi(bid)));

            assertThat(bid.getCommissionStatus()).isEqualTo(settled);
            verify(bidRepo, never()).save(any());
            verifyNoInteractions(cashCommissionService);
        }

        @ParameterizedTest
        @EnumSource(value = BidStatus.class, names = {"CANCELLED", "REJECTED", "EXPIRED", "NEGOTIATION_CLOSED"})
        void refundsCommissionWhenBidDiedDuring3ds(BidStatus dead) {
            BidEntity bid = commissionBid(dead, CommissionStatus.REQUIRES_3DS);

            handler.handlePaymentIntentSucceeded(eventFor(commissionPi(bid)));

            var inOrder = inOrder(bidRepo, cashCommissionService);
            inOrder.verify(bidRepo).save(bid);
            inOrder.verify(cashCommissionService).refundCommission(bid);
            assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_late");
        }

        @ParameterizedTest
        @EnumSource(value = BidStatus.class, names = {"PENDING", "ACCEPTED", "NEGOTIATING"})
        void keepsCommissionWhenBidIsStillAlive(BidStatus alive) {
            BidEntity bid = commissionBid(alive, CommissionStatus.REQUIRES_3DS);

            handler.handlePaymentIntentSucceeded(eventFor(commissionPi(bid)));

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            verifyNoInteractions(cashCommissionService);
        }

        private BidEntity commissionBid(BidStatus status, CommissionStatus commission) {
            BidEntity bid = new BidEntity();
            UUID bidId = UUID.randomUUID();
            ReflectionTestUtils.setField(bid, "id", bidId);
            bid.setStatus(status);
            bid.setCommissionStatus(commission);
            when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
            return bid;
        }

        private PaymentIntent commissionPi(BidEntity bid) {
            PaymentIntent pi = new PaymentIntent();
            pi.setId("pi_late");
            Map<String, String> meta = new HashMap<>();
            meta.put("commission_purpose", "cash_bid");
            meta.put("bid_id", bid.getId().toString());
            pi.setMetadata(meta);
            return pi;
        }
    }

    // ===================== handlePaymentIntentFailed =====================

    @Nested
    class HandlePaymentIntentFailed {

        @Test
        void marksBidAsFailedForCommissionPi() {
            BidEntity bid = new BidEntity();
            UUID bidId = UUID.randomUUID();
            ReflectionTestUtils.setField(bid, "id", bidId);
            bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

            PaymentIntent pi = new PaymentIntent();
            Map<String, String> meta = new HashMap<>();
            meta.put("commission_purpose", "cash_bid");
            meta.put("bid_id", bidId.toString());
            pi.setMetadata(meta);

            when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
            Event event = eventFor(pi);

            handler.handlePaymentIntentFailed(event);

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
            verify(bidRepo).save(bid);
        }

        @Test
        void ignoresNonCommissionPi() {
            PaymentIntent pi = new PaymentIntent();
            pi.setMetadata(new HashMap<>());

            Event event = eventFor(pi);
            handler.handlePaymentIntentFailed(event);

            verifyNoInteractions(bidRepo);
        }
    }

    // ===================== handlePaymentMethodDetached =====================

    @Nested
    class HandlePaymentMethodDetached {

        @Test
        void clearsUserCommissionPmAndPublishesEvent() {
            UUID userId = UUID.randomUUID();
            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            user.setCommissionPaymentMethodId("pm_stolen");
            user.setCommissionCardBrand("visa");
            user.setCommissionCardLast4("4242");

            com.stripe.model.PaymentMethod pm = mock(com.stripe.model.PaymentMethod.class);
            when(pm.getId()).thenReturn("pm_stolen");
            when(userRepo.findByCommissionPaymentMethodId("pm_stolen")).thenReturn(Optional.of(user));

            Event event = eventFor(pm);
            handler.handlePaymentMethodDetached(event);

            assertThat(user.getCommissionPaymentMethodId()).isNull();
            assertThat(user.getCommissionCardBrand()).isNull();
            assertThat(user.getCommissionCardLast4()).isNull();
            verify(userRepo).save(user);

            ArgumentCaptor<CommissionMethodDetachedEvent> captor =
                    ArgumentCaptor.forClass(CommissionMethodDetachedEvent.class);
            verify(events).publishEvent(captor.capture());
            assertThat(captor.getValue().travelerId()).isEqualTo(userId);
        }

        @Test
        void isNoOpWhenUserNotFound() {
            com.stripe.model.PaymentMethod pm = mock(com.stripe.model.PaymentMethod.class);
            when(pm.getId()).thenReturn("pm_unknown");
            when(userRepo.findByCommissionPaymentMethodId("pm_unknown")).thenReturn(Optional.empty());

            Event event = eventFor(pm);
            handler.handlePaymentMethodDetached(event);

            verifyNoInteractions(events);
            verify(userRepo, never()).save(any());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Event eventFor(com.stripe.model.StripeObject stripeObject) {
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(stripeObject));
        Event event = mock(Event.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
