package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.config.PlatformSettingsTestFactory;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.MatchingService;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.requests.PublicPackageRequestPageController;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PackageRequestCreateRequest;
import com.yadony.api.requests.dto.NegotiationQuoteResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Budgets, prix publics et devis des demandes d'envoi sous grille de commission (spec §4.3,
 * §5.4) : grille 7/6/5, minimums par défaut (1,00 €, 650 FCFA). Le budget public se convertit
 * sans réduction personnelle, sur la grille figée de la demande ; le devis d'un nouveau budget
 * lit la grille en vigueur. Sans grille, le calcul hérité au taux global reste inchangé.
 */
@ExtendWith(MockitoExtension.class)
class PackageRequestServiceGridTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    /** Grille entrée en vigueur après la création de la demande : ne doit jamais s'appliquer. */
    private static final CommissionGrid GRID_FLAT_10 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 11, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.10"))),
            CommissionGridService.defaultMinimums());

    private static final UUID SENDER_ID = UUID.randomUUID();

    @Mock PackageRequestRepository repository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;
    @Mock RequestsConfig config;
    @Mock NegotiationThreadRepository threadRepository;
    @Mock com.yadony.api.city.CityRepository cityRepository;
    @Mock StorageService storageService;
    @Mock PackageRequestPhotoService photoService;
    @Mock FavoriteRepository favoriteRepository;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock ExchangeRateService exchangeRateService;
    @Mock MatchingService matchingService;
    @Mock AnnouncementRepository announcementRepository;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock BlockVisibility blockVisibility;
    @Mock PackageRequestInsightService insightService;
    @Mock TravelerNegotiationLookup negotiationLookup;
    @Mock CommissionGridService commissionGridService;

    private PackageRequestService service;
    private PackageRequestSearchMapper mapper;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", SENDER_ID);
        sender.setKycStatus(KycStatus.VERIFIED);
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        lenient().when(commissionRateResolver.globalRate()).thenReturn(new BigDecimal("0.12"));
        lenient().when(photoService.activePhotos(any())).thenReturn(List.of());
        lenient().when(commissionGridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        lenient().when(commissionGridService.require(GRID_FLAT_10.id())).thenReturn(GRID_FLAT_10);
        mapper = new PackageRequestSearchMapper(userRepository, cityRepository, storageService, photoService,
                PlatformSettingsTestFactory.withCommissionRate(new BigDecimal("0.12")), commissionGridService);
        service = new PackageRequestService(repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, storageService, photoService, favoriteRepository,
                activeCurrencyResolver, exchangeRateService, mapper, matchingService,
                new YadonyConfigProperties(null, null, new YadonyConfigProperties.Urgency(3), null),
                announcementRepository, commissionRateResolver, PlatformSettingsTestFactory.withProEnabled(true),
                blockVisibility, TestMessages.resolver(), insightService, negotiationLookup, commissionGridService);
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private static PackageRequestCreateRequest request(String budget, String currency) {
        return new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", "Cadeau", new BigDecimal(budget),
                null, null, null, true, EnumSet.of(PaymentMethod.CASH),
                List.of(), null, null, currency);
    }

    private ArgumentCaptor<PackageRequestEntity> stubCreation() {
        when(config.maxOpenRequestsPerSender()).thenReturn(10);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
        ArgumentCaptor<PackageRequestEntity> captor = ArgumentCaptor.forClass(PackageRequestEntity.class);
        lenient().when(repository.save(captor.capture())).thenAnswer(inv -> {
            PackageRequestEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });
        return captor;
    }

    private static PackageRequestEntity entity(UUID gridId, String currency, String net) {
        PackageRequestEntity e = new PackageRequestEntity();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setSenderId(SENDER_ID);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        e.setCurrency(currency);
        e.setTargetPriceEur(net == null ? null : new BigDecimal(net));
        e.setCommissionGridId(gridId);
        return e;
    }

    private static void assertUnprocessable(Throwable t, String code) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }

    @Nested @DisplayName("create() — budget public converti sur la grille en vigueur")
    class Create {

        @Test @DisplayName("budget 21,40 € → net 20,00 €, grille figée sur la demande")
        void create_grid_budget2140_net20() {
            var captor = stubCreation();
            when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));

            var response = service.create(SENDER_ID, request("21.40", null));

            assertThat(captor.getValue().getTargetPriceEur()).isEqualByComparingTo("20.00");
            assertThat(captor.getValue().getCommissionGridId()).isEqualTo(GRID_7_6_5.id());
            // Prix affiché après création : le budget saisi, au centime.
            assertThat(response.grossPriceEur()).isEqualByComparingTo("21.40");
            verify(commissionRateResolver, never()).globalRate();
        }

        @Test @DisplayName("XOF : budget 21 400 F → net 20 000 F (francs entiers)")
        void create_grid_xof() {
            var captor = stubCreation();
            when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));

            service.create(SENDER_ID, request("21400", "XOF"));

            assertThat(captor.getValue().getTargetPriceEur()).isEqualByComparingTo("20000");
        }

        @Test @DisplayName("budget 1,00 € (≤ commission minimum) → 422 budget-below-commission-minimum")
        void create_budgetBelowMinimum_422() {
            stubCreation();
            when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));

            assertThatThrownBy(() -> service.create(SENDER_ID, request("1.00", null)))
                    .satisfies(t -> assertUnprocessable(t, "budget-below-commission-minimum"));
            verify(repository, never()).save(any());
        }

        @Test @DisplayName("sans grille : net = budget / (1 + taux global), comme avant")
        void noGrid_create_legacyUnchanged() {
            var captor = stubCreation();

            service.create(SENDER_ID, request("28.00", null));

            assertThat(captor.getValue().getTargetPriceEur()).isEqualByComparingTo("25.00");
            assertThat(captor.getValue().getCommissionGridId()).isNull();
        }
    }

    @Nested @DisplayName("update() — nouveau budget sur la grille figée de la demande")
    class Update {

        private PackageRequestEntity stubUpdate(UUID gridId) {
            PackageRequestEntity e = entity(gridId, "EUR", "50.00");
            when(repository.findById(e.getId())).thenReturn(Optional.of(e));
            lenient().when(threadRepository.findByPackageRequestId(e.getId())).thenReturn(List.of());
            lenient().when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            // Une grille plus récente est en vigueur : elle ne doit pas s'appliquer.
            lenient().when(commissionGridService.current()).thenReturn(Optional.of(GRID_FLAT_10));
            return e;
        }

        @Test @DisplayName("budget 107,00 € → net 100,00 € sur la grille 7/6/5 figée, pas sur la grille courante")
        void update_keepsFrozenGrid() {
            PackageRequestEntity e = stubUpdate(GRID_7_6_5.id());

            var response = service.update(SENDER_ID, e.getId(), request("107.00", null));

            assertThat(e.getTargetPriceEur()).isEqualByComparingTo("100.00");
            assertThat(e.getCommissionGridId()).isEqualTo(GRID_7_6_5.id());
            assertThat(response.grossPriceEur()).isEqualByComparingTo("107.00");
            verify(commissionGridService, never()).current();
        }

        @Test @DisplayName("budget sous le minimum → 422, la demande n'est pas modifiée")
        void update_budgetBelowMinimum_422() {
            PackageRequestEntity e = stubUpdate(GRID_7_6_5.id());

            assertThatThrownBy(() -> service.update(SENDER_ID, e.getId(), request("0.90", null)))
                    .satisfies(t -> assertUnprocessable(t, "budget-below-commission-minimum"));
            assertThat(e.getTargetPriceEur()).isEqualByComparingTo("50.00");
            verify(repository, never()).save(any());
        }

        @Test @DisplayName("demande sans grille : calcul hérité, même si une grille est en vigueur")
        void noGrid_update_legacyUnchanged() {
            PackageRequestEntity e = stubUpdate(null);

            service.update(SENDER_ID, e.getId(), request("56.00", null));

            assertThat(e.getTargetPriceEur()).isEqualByComparingTo("50.00"); // 56 / 1,12
            assertThat(e.getCommissionGridId()).isNull();
        }
    }

    @Nested @DisplayName("prix public d'une demande (détail, recherche, page publique)")
    class PublicGross {

        @Test @DisplayName("détail : net 20,00 € → 21,40 € ; XOF 20 000 → 21 400")
        void toResponse_grid_grossFromCalculator() {
            assertThat(service.toResponse(entity(GRID_7_6_5.id(), "EUR", "20.00")).grossPriceEur())
                    .isEqualByComparingTo("21.40");
            assertThat(service.toResponse(entity(GRID_7_6_5.id(), "XOF", "20000")).grossPriceEur())
                    .isEqualByComparingTo("21400");
        }

        @Test @DisplayName("détail sans grille : net × (1 + taux global)")
        void noGrid_toResponse_legacyUnchanged() {
            assertThat(service.toResponse(entity(null, "EUR", "20.00")).grossPriceEur())
                    .isEqualByComparingTo("22.40");
            assertThat(service.toResponse(entity(GRID_7_6_5.id(), "EUR", null)).grossPriceEur()).isNull();
        }

        @Test @DisplayName("recherche : même brut que le détail, sans réduction personnelle")
        void search_grid_grossNoPersonalReductions() {
            var response = mapper.toSearchResponse(entity(GRID_7_6_5.id(), "EUR", "20.00"), false,
                    ViewerPaymentCapabilities.NONE, Map.of(), Map.of(), Map.of());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("21.40");
            // Ni taux spécial, ni bon, ni code : rien de privé ne transparaît dans brut / net.
            verifyNoInteractions(commissionRateResolver);
        }

        @Test @DisplayName("recherche sans grille : taux global")
        void noGrid_search_legacyUnchanged() {
            var response = mapper.toSearchResponse(entity(null, "EUR", "20.00"), false,
                    ViewerPaymentCapabilities.NONE, Map.of(), Map.of(), Map.of());

            assertThat(response.grossPriceEur()).isEqualByComparingTo("22.40");
            verifyNoInteractions(commissionGridService);
        }

        private String publicGross(PackageRequestEntity e) {
            PackageRequestRepository repo = mock(PackageRequestRepository.class);
            when(repo.findById(e.getId())).thenReturn(Optional.of(e));
            var controller = new PublicPackageRequestPageController(repo, photoService, mapper);
            ReflectionTestUtils.setField(controller, "publicBaseUrl", "https://yadony.com");
            var model = new ExtendedModelMap();
            controller.packageRequestPage(e.getId().toString(), new MockHttpServletRequest(), model);
            return (String) model.get("grossPrice");
        }

        @Test @DisplayName("page publique : 21,4 € sous grille, 22,4 € sans")
        void publicPage_grid_gross() {
            assertThat(publicGross(entity(GRID_7_6_5.id(), "EUR", "20.00"))).isEqualTo("21.4");
            assertThat(publicGross(entity(null, "EUR", "20.00"))).isEqualTo("22.4");
        }
    }

    @Nested @DisplayName("quote() — devis d'un nouveau budget sur la grille en vigueur")
    class Quote {

        private void gridInForce() {
            when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));
        }

        private void reductionsWithoutCode(CommissionReductions r) {
            lenient().when(commissionRateResolver.reductions(null, SENDER_ID, null, null, null)).thenReturn(r);
        }

        @Test @DisplayName("sans code : 21,40 € → net 20,00, commission 1,40, total 21,40")
        void quote_grid_noCode() {
            gridInForce();
            reductionsWithoutCode(CommissionReductions.NONE);

            NegotiationQuoteResponse q = service.quote(SENDER_ID, new BigDecimal("21.40"), null, null);

            assertThat(q.netEur()).isEqualByComparingTo("20.00");
            assertThat(q.commissionEur()).isEqualByComparingTo("1.40");
            assertThat(q.totalEur()).isEqualByComparingTo("21.40");
            assertThat(q.rate()).isEqualByComparingTo("0.07");
            assertThat(q.promoApplied()).isFalse();
            assertThat(q.minimumApplied()).isFalse();
            assertThat(q.minimumAmount()).isEqualByComparingTo("1.00");
            assertThat(q.commissionGridId()).isEqualTo(GRID_7_6_5.id());
            assertThat(q.promoIneffectiveReason()).isNull();
        }

        @Test @DisplayName("code 5 points : net inchangé (public), commission 2,00, l'expéditeur paie 102,00 sur 107,00")
        void quote_grid_promoLowersTotal() {
            gridInForce();
            when(commissionRateResolver.reductions(null, SENDER_ID, "WELCOME5", SENDER_ID, null))
                    .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

            NegotiationQuoteResponse q = service.quote(SENDER_ID, new BigDecimal("107.00"), "WELCOME5", null);

            assertThat(q.netEur()).isEqualByComparingTo("100.00");
            assertThat(q.commissionEur()).isEqualByComparingTo("2.00");
            assertThat(q.totalEur()).isEqualByComparingTo("102.00");
            assertThat(q.rate()).isEqualByComparingTo("0.07"); // taux de base, hors code
            assertThat(q.promoApplied()).isTrue();
            assertThat(q.promoLabel()).contains("WELCOME5").contains("5 % de réduction");
            assertThat(q.promoIneffectiveReason()).isNull();
        }

        @Test @DisplayName("code sans effet (minimum) : non appliqué, COMMISSION_MINIMUM")
        void quote_grid_promoIneffective() {
            gridInForce();
            when(commissionRateResolver.reductions(null, SENDER_ID, "WELCOME5", SENDER_ID, null))
                    .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

            NegotiationQuoteResponse q = service.quote(SENDER_ID, new BigDecimal("15.28"), "WELCOME5", null);

            assertThat(q.netEur()).isEqualByComparingTo("14.28");
            assertThat(q.commissionEur()).isEqualByComparingTo("1.00");
            assertThat(q.totalEur()).isEqualByComparingTo("15.28");
            assertThat(q.promoApplied()).isFalse();
            assertThat(q.promoLabel()).isNull();
            assertThat(q.minimumApplied()).isTrue();
            assertThat(q.promoIneffectiveReason()).isEqualTo("COMMISSION_MINIMUM");
        }

        @Test @DisplayName("devise fournie : XOF, minimum 650 F")
        void quote_grid_xofCurrency() {
            gridInForce();
            reductionsWithoutCode(CommissionReductions.NONE);

            NegotiationQuoteResponse q = service.quote(SENDER_ID, new BigDecimal("5650"), null, "xof");

            assertThat(q.netEur()).isEqualByComparingTo("5000");
            assertThat(q.commissionEur()).isEqualByComparingTo("650");
            assertThat(q.minimumApplied()).isTrue();
            assertThat(q.minimumAmount()).isEqualByComparingTo("650");
            verify(activeCurrencyResolver, never()).resolve(any());
        }

        @Test @DisplayName("budget sous le minimum → 422")
        void quote_grid_budgetBelowMinimum_422() {
            gridInForce();
            reductionsWithoutCode(CommissionReductions.NONE);

            assertThatThrownBy(() -> service.quote(SENDER_ID, new BigDecimal("1.00"), null, null))
                    .satisfies(t -> assertUnprocessable(t, "budget-below-commission-minimum"));
        }

        @Test @DisplayName("budget démesuré → 422 budget-out-of-bounds, pas de 500")
        void quote_grid_budgetAboveCeiling_422() {
            gridInForce();
            reductionsWithoutCode(CommissionReductions.NONE);

            assertThatThrownBy(() -> service.quote(SENDER_ID, new BigDecimal("1e20"), null, null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("request/budget-out-of-bounds");
        }

        @Test @DisplayName("code invalide → l'erreur du code remonte (validation stricte)")
        void quote_grid_invalidCode_propagates() {
            gridInForce();
            when(commissionRateResolver.reductions(null, SENDER_ID, "BADCODE", SENDER_ID, null))
                    .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "promo-invalid", "Promo Invalid", "Code invalide"));

            assertThatThrownBy(() -> service.quote(SENDER_ID, new BigDecimal("21.40"), "BADCODE", null))
                    .satisfies(t -> assertUnprocessable(t, "promo-invalid"));
        }

        @Test @DisplayName("sans grille : budget fixe, net = budget / (1 + taux), devise ignorée")
        void noGrid_quote_legacyUnchanged() {
            when(commissionRateResolver.resolve(isNull(), eq(SENDER_ID))).thenReturn(new BigDecimal("0.05"));

            NegotiationQuoteResponse q = service.quote(SENDER_ID, new BigDecimal("40.00"), null, "XOF");

            assertThat(q.totalEur()).isEqualByComparingTo("40.00");
            assertThat(q.netEur()).isEqualByComparingTo("38.10");
            assertThat(q.commissionEur()).isEqualByComparingTo("1.90");
            assertThat(q.minimumApplied()).isFalse();
            assertThat(q.minimumAmount()).isNull();
            assertThat(q.commissionGridId()).isNull();
            assertThat(q.promoIneffectiveReason()).isNull();
        }
    }
}
