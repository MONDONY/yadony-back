package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'escrow d'un bid issu d'une négociation de prix.
 *
 * <p>Le montant net est normalement recalculé côté serveur depuis le barème du trajet
 * (grille snapshotée + poids × prix/kg). Pour un bid négocié ce barème n'a plus aucun
 * rapport avec l'accord : la source de vérité devient {@code negotiatedNetEur} /
 * {@code negotiatedGrossEur}, figés à l'acceptation. Sans cela, l'expéditeur serait
 * débité du tarif catalogue et le recoupement {@code amount-mismatch} rejetterait
 * systématiquement le montant que l'app affiche.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService.createEscrow — bid issu d'une négociation de prix")
class PaymentServiceNegotiatedEscrowTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository,
                mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.yadony.api.common.stripe.AdminAlertService.class),
                PaymentServiceTestFactory.stubbedResolver(),
                mock(com.yadony.api.promo.PromoService.class),
                new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.payments.currency.ActiveCurrencyResolver.class, inv -> "EUR"),
                new com.yadony.api.payments.currency.CurrencyMatchGuard());
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Barème du trajet : 5 kg × 5 €/kg = 25,00 € net, soit 28,00 € TTC au taux global
     * de 12 %. L'accord négocié est tout autre : 45,00 € brut, 42,86 € net, taux figé
     * à 5 %. Les deux jeux de chiffres sont volontairement disjoints.
     */
    private BidEntity buildNegotiatedBid() {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setNegotiatedGrossEur(new BigDecimal("45.00"));
        b.setNegotiatedNetEur(new BigDecimal("42.86"));
        b.setCommissionRate(new BigDecimal("0.05"));
        return b;
    }

    private void stubRepositories(BidEntity bid) {
        UserEntity sender = new UserEntity();
        setId(sender, senderId);
        sender.setFirebaseUid("uid-sender");
        sender.setStripeCustomerId("cus_sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        AnnouncementEntity ann = new AnnouncementEntity();
        setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(BigDecimal.valueOf(5.0));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        traveler.setFirebaseUid("uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        // lenient : le test du recoupement s'arrête avant toute persistance.
        org.mockito.Mockito.lenient().when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });
    }

    private ArgumentCaptor<PaymentIntentCreateParams> stubStripe(
            MockedStatic<com.stripe.model.Account> acctStatic, MockedStatic<PaymentIntent> piStatic) {
        com.stripe.model.Account acct = mock(com.stripe.model.Account.class);
        com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
        when(caps.getCardPayments()).thenReturn("active");
        when(acct.getCapabilities()).thenReturn(caps);
        acctStatic.when(() -> com.stripe.model.Account.retrieve(any(String.class))).thenReturn(acct);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_nego");
        when(pi.getClientSecret()).thenReturn("pi_nego_secret");
        piStatic.when(() -> PaymentIntent.create(captor.capture())).thenReturn(pi);
        return captor;
    }

    private CreatePaymentRequest request(BigDecimal clientNet) {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setBidId(bidId);
        req.setTotalNetEur(clientNet);
        return req;
    }

    @Test
    @DisplayName("l'expéditeur est débité du brut négocié, jamais du barème du trajet")
    void chargesTheAgreedGross_notTheTariff() {
        stubRepositories(buildNegotiatedBid());

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            var captor = stubStripe(acctStatic, piStatic);

            service.createEscrow(request(null), "uid-sender");

            // 45,00 € = l'accord. 28,00 € aurait été le barème (25,00 × 1,12).
            assertThat(captor.getValue().getAmount()).isEqualTo(4500L);
        }
    }

    @Test
    @DisplayName("net + commission = brut au centime, avec le taux figé à l'accord")
    void commissionIsTheAgreedSpread() {
        stubRepositories(buildNegotiatedBid());

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            stubStripe(acctStatic, piStatic);

            service.createEscrow(request(null), "uid-sender");

            ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
            verify(paymentRepository).save(saved.capture());
            assertThat(saved.getValue().getAmount()).isEqualByComparingTo("45.00");
            assertThat(saved.getValue().getCommissionAmount()).isEqualByComparingTo("2.14");
            assertThat(saved.getValue().getAmount().subtract(saved.getValue().getCommissionAmount()))
                    .describedAs("le voyageur touche exactement le net négocié")
                    .isEqualByComparingTo("42.86");
        }
    }

    @Test
    @DisplayName("le recoupement accepte le net négocié envoyé par le client")
    void amountCrossCheckAcceptsTheAgreedNet() {
        stubRepositories(buildNegotiatedBid());

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            stubStripe(acctStatic, piStatic);

            // Ne doit PAS lever amount-mismatch : c'est exactement ce que l'app affiche.
            service.createEscrow(request(new BigDecimal("42.86")), "uid-sender");

            verify(paymentRepository).save(any(PaymentEntity.class));
        }
    }

    @Test
    @DisplayName("le recoupement rejette encore un montant qui n'est pas celui de l'accord")
    void amountCrossCheckStillRejectsAForgedAmount() {
        stubRepositories(buildNegotiatedBid());

        // 25,00 € = le net du barème : un bid négocié ne doit pas pouvoir être payé
        // au tarif catalogue s'il est plus bas que l'accord.
        assertThatThrownBy(() -> service.createEscrow(request(new BigDecimal("25.00")), "uid-sender"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("amount-mismatch"));
    }
}
