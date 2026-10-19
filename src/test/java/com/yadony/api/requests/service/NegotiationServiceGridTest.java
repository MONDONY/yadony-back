package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionResult;
import com.yadony.api.common.commission.ThreadCommissionPricing;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.NegotiationEscrowPort;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import com.yadony.api.requests.NegotiationProperties;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.NegotiationCounterRequest;
import com.yadony.api.requests.dto.NegotiationQuoteResponse;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationMessageKind;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.NegotiationCounterPostedEvent;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Montants d'un fil de demande d'envoi à grille figée (spec §4.4, §5.4) : brut affiché sur le
 * fil, brut annoncé à l'expéditeur dans les notifications, devis de paiement. Tous passent par
 * {@link ThreadCommissionPricing}, la source des rails espèces, mobile money et carte : le
 * montant affiché avant validation est celui prélevé. Sans grille : calcul hérité inchangé.
 */
@ExtendWith(MockitoExtension.class)
class NegotiationServiceGridTest {

    @Mock PackageRequestRepository requestRepo;
    @Mock NegotiationThreadRepository threadRepo;
    @Mock NegotiationMessageRepository messageRepo;
    @Mock UserRepository userRepository;
    @Mock com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;
    @Mock RequestsConfig config;
    @Mock NegotiationProperties negotiationProperties;
    @Mock CashGatePort cashGatePort;
    @Mock NegotiationEscrowPort escrowPort;
    @Mock StorageService storageService;
    @Mock PackageRequestPhotoService photoService;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock NegotiationMobileMoneyPort mobileMoneyPort;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID gridId = UUID.randomUUID();
    private final ThreadCommissionPricing pricing = mock(ThreadCommissionPricing.class);

    private NegotiationService service;
    private PackageRequestEntity request;
    private NegotiationThreadEntity thread;

    @BeforeEach
    void setUp() {
        service = new NegotiationService(requestRepo, threadRepo, messageRepo, userRepository, announcementRepo,
                eventPublisher, auditService, config, negotiationProperties, cashGatePort, escrowPort,
                storageService, photoService, commissionRateResolver, exchangeRateService, mobileMoneyPort,
                TestMessages.resolver());
        ReflectionTestUtils.setField(service, "threadCommissionPricing", pricing);

        request = new PackageRequestEntity();
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.setSenderId(senderId);
        request.setCurrency("EUR");
        request.setNegotiable(true);
        request.setStatus(PackageRequestStatus.NEGOTIATING);
        request.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));

        thread = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(thread, "id", UUID.randomUUID());
        thread.setPackageRequestId(request.getId());
        thread.setTravelerId(travelerId);
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setCurrency("EUR");
        thread.setCurrentPriceEur(new BigDecimal("20.00"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now());
        thread.setCommissionGridId(gridId);
        thread.setPromoCode("WELCOME5");

        lenient().when(threadRepo.findById(thread.getId())).thenReturn(Optional.of(thread));
        lenient().when(threadRepo.findPackageRequestIdById(thread.getId())).thenReturn(Optional.of(request.getId()));
        lenient().when(requestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        lenient().when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        lenient().when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private static CommissionResult result(String net, String commission, boolean minimumApplied,
                                           boolean promoEffective) {
        BigDecimal n = new BigDecimal(net);
        BigDecimal c = new BigDecimal(commission);
        return new CommissionResult(n, c, n.add(c), c.divide(n, 6, java.math.RoundingMode.HALF_UP),
                minimumApplied, promoEffective);
    }

    /** Devis du fil avec son code (affichage, carte, mobile money) et sans code (rail espèces). */
    private void priced(String net, CommissionResult withThreadCode, CommissionResult withoutCode) {
        lenient().when(pricing.price(gridId, new BigDecimal(net), "EUR", travelerId, senderId, thread.getId(),
                thread.getPromoCode())).thenReturn(withThreadCode);
        lenient().when(pricing.displayPrice(gridId, new BigDecimal(net), "EUR", travelerId, senderId, thread.getId(),
                thread.getPromoCode())).thenReturn(withThreadCode);
        lenient().when(pricing.price(gridId, new BigDecimal(net), "EUR", travelerId, senderId, thread.getId()))
                .thenReturn(withoutCode);
    }

    @Nested @DisplayName("brut affiché et annoncé")
    class Gross {

        @Test @DisplayName("fil à grille : brut du fil = celui des rails, code du fil compris")
        void threadToResponse_grid_gross() {
            priced("20.00", result("20.00", "1.00", true, true), result("20.00", "1.40", false, false));

            var response = service.getById(senderId, thread.getId());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("21.00");
        }

        @Test @DisplayName("voyageur : brut public sans réduction personnelle de l'expéditeur")
        void threadToResponse_grid_travelerSeesPublicGross() {
            when(pricing.publicGross(gridId, new BigDecimal("20.00"), "EUR")).thenReturn(new BigDecimal("21.40"));
            lenient().when(pricing.price(gridId, new BigDecimal("20.00"), "EUR", travelerId, senderId, thread.getId()))
                    .thenReturn(result("20.00", "1.40", false, false));

            var response = service.getById(travelerId, thread.getId());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("21.40");
            // Ni le code ni le bon de l'expéditeur ne sont lus pour le brut affiché au voyageur.
            verify(pricing, never()).price(any(), any(), any(), any(), any(), any(), any());
        }

        @Test @DisplayName("voyageur, fil scellé : toujours le brut public, jamais le total de l'expéditeur")
        void threadToResponse_sealed_travelerStillSeesPublicGross() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setSealedGrossEur(new BigDecimal("21.00"));
            when(pricing.publicGross(gridId, new BigDecimal("20.00"), "EUR")).thenReturn(new BigDecimal("21.40"));
            lenient().when(pricing.price(gridId, new BigDecimal("20.00"), "EUR", travelerId, senderId, thread.getId()))
                    .thenReturn(result("20.00", "1.40", false, false));

            assertThat(service.getById(travelerId, thread.getId()).grossPriceEur()).isEqualByComparingTo("21.40");
        }

        @Test @DisplayName("expéditeur, fil scellé dont le code a atteint sa limite : brut scellé, code jamais revalidé")
        void threadToResponse_sealed_senderSeesSealedGross_codeNeverRevalidated() {
            // Chaîne réelle (ThreadCommissionPricing + CommissionRateResolver) : seul le service des
            // codes est un mock, qui échouerait désormais (code racheté, limite atteinte).
            var gridService = mock(com.yadony.api.common.commission.CommissionGridService.class);
            lenient().when(gridService.require(gridId)).thenReturn(new com.yadony.api.common.commission.CommissionGrid(
                    gridId, LocalDateTime.of(2026, 10, 1, 0, 0),
                    List.of(new com.yadony.api.common.commission.CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07"))),
                    com.yadony.api.common.commission.CommissionGridService.defaultMinimums()));
            var promoService = mock(com.yadony.api.promo.PromoService.class);
            lenient().when(promoService.validateAndGetDiscount(any(), any(), any()))
                    .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "promo-limit-reached",
                            "Promo Limit Reached", "Limite atteinte"));
            var resolver = new CommissionRateResolver(userRepository,
                    com.yadony.api.config.PlatformSettingsTestFactory.defaults(), promoService,
                    mock(com.yadony.api.voucher.CommissionVoucherService.class));
            ReflectionTestUtils.setField(service, "threadCommissionPricing", new ThreadCommissionPricing(gridService, resolver));
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setPaymentMethod(PaymentMethod.STRIPE);
            thread.setSealedGrossEur(new BigDecimal("21.00"));

            var response = service.getById(senderId, thread.getId());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("21.00");
            verify(promoService, never()).validateAndGetDiscount(any(), any(), any());
        }

        @Test @DisplayName("sans grille : net × (1 + taux du fil), comme avant")
        void noGrid_threadToResponse_legacyUnchanged() {
            thread.setCommissionGridId(null);
            thread.setCommissionRate(new BigDecimal("0.10"));

            var response = service.getById(senderId, thread.getId());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("22.00");
            verifyNoInteractions(pricing);
        }

        @Test @DisplayName("contre-offre du voyageur : l'expéditeur est notifié du brut des rails")
        void counter_grid_senderNotifiedWithRailsGross() {
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(thread.getId())).thenReturn(List.of(
                    NegotiationMessageEntity.create(thread.getId(), senderId, NegotiationMessageKind.COUNTER,
                            new BigDecimal("20.00"), null)));
            when(pricing.displayPrice(gridId, new BigDecimal("30.00"), "EUR", travelerId, senderId, thread.getId(),
                    "WELCOME5")).thenReturn(result("30.00", "1.00", true, true));

            service.counter(travelerId, thread.getId(), new NegotiationCounterRequest(new BigDecimal("30.00"), null));

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
            var counter = events.getAllValues().stream().filter(NegotiationCounterPostedEvent.class::isInstance)
                    .map(NegotiationCounterPostedEvent.class::cast).findFirst().orElseThrow();
            assertThat(counter.amountForRecipient()).isEqualByComparingTo("31.00");
        }

        private PackageRequestAcceptedEvent sealByDeposit(PaymentMethod method) {
            thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
            thread.setPaymentMethod(method);
            when(threadRepo.findByPackageRequestId(request.getId())).thenReturn(List.of(thread));

            service.finalizeAfterMobileMoneyDeposit(thread.getId());

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
            return events.getAllValues().stream().filter(PackageRequestAcceptedEvent.class::isInstance)
                    .map(PackageRequestAcceptedEvent.class::cast).findFirst().orElseThrow();
        }

        @Test @DisplayName("accord scellé, fil à grille payé par l'expéditeur : l'événement porte son total")
        void seal_grid_eventCarriesSenderGross() {
            priced("20.00", result("20.00", "1.00", true, true), result("20.00", "1.40", false, false));
            when(escrowPort.paidAmount(thread.getId())).thenReturn(Optional.of(new BigDecimal("21.00")));

            PackageRequestAcceptedEvent accepted = sealByDeposit(PaymentMethod.MOBILE_MONEY);

            assertThat(accepted.senderGross()).isEqualByComparingTo("21.00");
            assertThat(accepted.commissionGridId()).isEqualTo(gridId);
            assertThat(thread.getSealedGrossEur()).isEqualByComparingTo("21.00");
        }

        @Test @DisplayName("réductions changées depuis l'initiation : brut scellé = montant du paiement, jamais recalculé")
        void seal_grid_reductionsChangedSincePayment_sealsPaidAmount() {
            // Payé 20,70 € (bon ×0,5 lu à l'initiation) ; au scellement le bon n'est plus lu et le
            // calcul du jour donnerait 21,00 €. Seul le montant débité fait foi.
            priced("20.00", result("20.00", "1.00", true, true), result("20.00", "1.40", false, false));
            when(escrowPort.paidAmount(thread.getId())).thenReturn(Optional.of(new BigDecimal("20.70")));

            PackageRequestAcceptedEvent accepted = sealByDeposit(PaymentMethod.MOBILE_MONEY);

            assertThat(thread.getSealedGrossEur()).isEqualByComparingTo("20.70");
            assertThat(accepted.senderGross()).isEqualByComparingTo("20.70");
            verify(pricing, never()).price(any(), any(), any(), any(), any(), any(), any());
        }

        @Test @DisplayName("paiement du fil introuvable au scellement : dernier recours, brut des rails recalculé")
        void seal_grid_paymentUnreadable_recomputesRailsGross() {
            priced("20.00", result("20.00", "1.00", true, true), result("20.00", "1.40", false, false));
            when(escrowPort.paidAmount(thread.getId())).thenReturn(Optional.empty());

            sealByDeposit(PaymentMethod.MOBILE_MONEY);

            assertThat(thread.getSealedGrossEur()).isEqualByComparingTo("21.00");
            verify(pricing).price(gridId, new BigDecimal("20.00"), "EUR", travelerId, senderId, thread.getId(),
                    "WELCOME5");
        }

        @Test @DisplayName("espèces : brut scellé = net + commission du voyageur, aucun total expéditeur sur l'événement")
        void seal_grid_cash_sealsNetPlusCommission() {
            priced("20.00", result("20.00", "1.00", true, true), result("20.00", "1.40", false, false));

            assertThat(sealByDeposit(PaymentMethod.CASH).senderGross()).isNull();
            assertThat(thread.getSealedGrossEur()).isEqualByComparingTo("21.40");
            verify(pricing, never()).price(any(), any(), any(), any(), any(), any(), any());
        }

        @Test @DisplayName("sans grille : rien de scellé, le listener garde le calcul hérité")
        void noGrid_seal_legacyUnchanged() {
            thread.setCommissionGridId(null);

            assertThat(sealByDeposit(PaymentMethod.MOBILE_MONEY).senderGross()).isNull();
            assertThat(thread.getSealedGrossEur()).isNull();
            verifyNoInteractions(pricing);
        }
    }

    @Nested @DisplayName("quote() — devis du fil avant paiement")
    class Quote {

        @Test @DisplayName("code sans effet (net 10 €, minimum) : non appliqué, COMMISSION_MINIMUM")
        void negotiationQuote_grid_promoIneffective() {
            thread.setCurrentPriceEur(new BigDecimal("10.00"));
            when(commissionRateResolver.reductions(travelerId, senderId, "WELCOME5", senderId, thread.getId()))
                    .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));
            priced("10.00", result("10.00", "1.00", true, false), result("10.00", "1.00", true, false));
            when(pricing.minimum(gridId, "EUR")).thenReturn(new BigDecimal("1.00"));

            NegotiationQuoteResponse q = service.quote(senderId, thread.getId(), null);

            assertThat(q.netEur()).isEqualByComparingTo("10.00");
            assertThat(q.commissionEur()).isEqualByComparingTo("1.00");
            assertThat(q.totalEur()).isEqualByComparingTo("11.00");
            assertThat(q.rate()).isEqualByComparingTo("0.10");
            assertThat(q.promoApplied()).isFalse();
            assertThat(q.promoLabel()).isNull();
            assertThat(q.minimumApplied()).isTrue();
            assertThat(q.minimumAmount()).isEqualByComparingTo("1.00");
            assertThat(q.commissionGridId()).isEqualTo(gridId);
            assertThat(q.promoIneffectiveReason()).isEqualTo("COMMISSION_MINIMUM");
        }

        @Test @DisplayName("code fourni et efficace : commission de base, total code déduit")
        void negotiationQuote_grid_promoEffective() {
            thread.setCurrentPriceEur(new BigDecimal("100.00"));
            when(commissionRateResolver.reductions(travelerId, senderId, "OTHER5", senderId, thread.getId()))
                    .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));
            lenient().when(pricing.price(gridId, new BigDecimal("100.00"), "EUR", travelerId, senderId,
                    thread.getId())).thenReturn(result("100.00", "7.00", false, false));
            when(pricing.price(gridId, new BigDecimal("100.00"), "EUR", travelerId, senderId, thread.getId(),
                    "OTHER5")).thenReturn(result("100.00", "2.00", false, true));
            when(pricing.minimum(gridId, "EUR")).thenReturn(new BigDecimal("1.00"));

            NegotiationQuoteResponse q = service.quote(senderId, thread.getId(), " OTHER5 ");

            assertThat(q.commissionEur()).isEqualByComparingTo("7.00");
            assertThat(q.rate()).isEqualByComparingTo("0.07");
            assertThat(q.totalEur()).isEqualByComparingTo("102.00");
            assertThat(q.promoApplied()).isTrue();
            assertThat(q.promoLabel()).contains("OTHER5").contains("5 % de réduction");
            assertThat(q.minimumApplied()).isFalse();
            assertThat(q.promoIneffectiveReason()).isNull();
        }

        @Test @DisplayName("sans code : commission et total du fil")
        void negotiationQuote_grid_noCode() {
            thread.setPromoCode(null);
            priced("20.00", result("20.00", "1.40", false, false), result("20.00", "1.40", false, false));
            when(pricing.minimum(gridId, "EUR")).thenReturn(new BigDecimal("1.00"));

            NegotiationQuoteResponse q = service.quote(senderId, thread.getId(), null);

            assertThat(q.commissionEur()).isEqualByComparingTo("1.40");
            assertThat(q.totalEur()).isEqualByComparingTo("21.40");
            assertThat(q.promoApplied()).isFalse();
            assertThat(q.promoIneffectiveReason()).isNull();
        }

        @Test @DisplayName("fil scellé : 409, rien n'est re-tarifé")
        void negotiationQuote_sealed_conflict() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setSealedGrossEur(new BigDecimal("21.00"));

            assertThatThrownBy(() -> service.quote(senderId, thread.getId(), null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("negotiation/thread-sealed");
            verifyNoInteractions(pricing);
            verify(commissionRateResolver, never()).reductions(any(), any(), any(), any(), any());
        }

        @Test @DisplayName("code invalide : l'erreur remonte (validation stricte, comme le devis hérité)")
        void negotiationQuote_grid_invalidCode_propagates() {
            when(commissionRateResolver.reductions(travelerId, senderId, "BADCODE", senderId, thread.getId()))
                    .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "promo-invalid",
                            "Promo Invalid", "Code invalide"));

            assertThatThrownBy(() -> service.quote(senderId, thread.getId(), "BADCODE"))
                    .isInstanceOf(YadonyBusinessException.class);
        }

        @Test @DisplayName("sans grille : calcul hérité, champs de grille vides")
        void noGrid_quote_legacyUnchanged() {
            thread.setCommissionGridId(null);
            thread.setPromoCode(null);
            when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));

            NegotiationQuoteResponse q = service.quote(senderId, thread.getId(), null);

            assertThat(q.totalEur()).isEqualByComparingTo("21.00");
            assertThat(q.minimumApplied()).isFalse();
            assertThat(q.minimumAmount()).isNull();
            assertThat(q.commissionGridId()).isNull();
            assertThat(q.promoIneffectiveReason()).isNull();
            verifyNoInteractions(pricing);
        }
    }
}
