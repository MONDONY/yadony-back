package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Négociation d'un bid à grille figée (spec §4.3) : les deux sens de conversion (brut → net,
 * net → brut) portent sur toute la chaîne, grille 7/6/5, taux spécial et bon de parrainage
 * figés à l'ouverture du fil, minimum de la devise.
 */
@ExtendWith(MockitoExtension.class)
class BidNegotiationServiceGridTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private BidNegotiationMessageRepository messageRepository;
    @Mock private BidCustomItemRepository customItemRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private BidPhotoService bidPhotoService;
    @Mock private CommissionRateResolver commissionRateResolver;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BidService bidService;
    @Mock private BidNegotiationMobileMoneyPort mobileMoneyPort;
    @Mock private CommissionGridService commissionGridService;
    @Mock private HttpServletRequest httpRequest;

    private final MatchingNegotiationConfig config = new MatchingNegotiationConfig(3, 1, 24, "-");

    private BidNegotiationService service;

    private static final String SENDER_UID = "uid-sender-grid";
    private static final String TRAVELER_UID = "uid-traveler-grid";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BidNegotiationService(
                bidRepository, announcementRepository, userRepository, messageRepository,
                customItemRepository, bidGridItemRepository, annGridItemRepository,
                bidPhotoService, commissionRateResolver, auditService, eventPublisher,
                config, bidService, mobileMoneyPort, commissionGridService);
        lenient().when(commissionGridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        lenient().when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserEntity user(UUID id, String uid, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setUsername(uid);
        u.getRoles().add(role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setCurrency("EUR");
        a.setNegotiable(true);
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        return a;
    }

    /** Fil à grille, taux spécial 4 % et bon ×0,5 figés à l'ouverture ; 5 kg à 5 €/kg au barème. */
    private static BidEntity gridBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.NEGOTIATING);
        b.setNegotiationRound(1);
        b.setCommissionRate(new BigDecimal("0.05"));
        b.setCurrency("EUR");
        b.setWeightKg(new BigDecimal("5.0"));
        b.setCommissionGridId(GRID_7_6_5.id());
        b.setCommissionOverrideRate(new BigDecimal("0.04"));
        b.setCommissionVoucherFactor(new BigDecimal("0.5"));
        ReflectionTestUtils.setField(b, "id", BID_ID);
        ReflectionTestUtils.setField(b, "updatedAt", LocalDateTime.now(ZoneOffset.UTC));
        return b;
    }

    private static BidNegotiationMessageEntity lastMessageFrom(UUID authorId, String gross) {
        BidNegotiationMessageEntity m = BidNegotiationMessageEntity.create(
                BID_ID, authorId, BidNegotiationMessageKind.PROPOSAL, new BigDecimal(gross), null);
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private BidEntity stubThread(String callerUid, UserEntity caller, UUID lastAuthor, String lastGross) {
        BidEntity bid = gridBid();
        when(userRepository.findByFirebaseUid(callerUid)).thenReturn(Optional.of(caller));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement()));
        when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                .thenReturn(Optional.of(lastMessageFrom(lastAuthor, lastGross)));
        return bid;
    }

    private static UserEntity traveler() {
        return user(TRAVELER_ID, TRAVELER_UID, Role.TRAVELER);
    }

    private static UserEntity sender() {
        return user(SENDER_ID, SENDER_UID, Role.SENDER);
    }

    @Test
    @DisplayName("contre-offre du voyageur en net 50 € : brut 51,00 € au contexte figé (4 %, bon ×0,5)")
    void counter_gridBid_grossFromFrozenContext() {
        stubThread(TRAVELER_UID, traveler(), SENDER_ID, "60.00");

        BidNegotiationResponse response = service.counter(BID_ID, TRAVELER_UID,
                new BidNegotiationCounterRequest(null, null, new BigDecimal("50.00")));

        // Grille 3,50 €, taux spécial 4 % → 2,00 €, au-dessus du minimum, bon ×0,5 → 1,00 €.
        ArgumentCaptor<BidNegotiationMessageEntity> msg = ArgumentCaptor.forClass(BidNegotiationMessageEntity.class);
        verify(messageRepository).save(msg.capture());
        assertThat(msg.getValue().getProposedGrossEur()).isEqualByComparingTo("51.00");
        assertThat(response.proposedGrossEur()).isEqualByComparingTo("51.00");
        // Le net retrouvé par l'inverse est exactement celui que le voyageur a tapé.
        assertThat(response.netEur()).isEqualByComparingTo("50.00");
        assertThat(response.commissionGridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(response.commissionOverrideRate()).isEqualByComparingTo("0.04");
        assertThat(response.commissionVoucherFactor()).isEqualByComparingTo("0.5");
        // Barème 5 kg × 5 € = 25 € : grille 1,75 €, taux spécial 1,00 €, bon ×0,5 → 0,50 €.
        assertThat(response.suggestedGrossEur()).isEqualByComparingTo("25.50");
        verify(commissionRateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("accord sur un brut de 51,00 € : net par l'inverse (50,00 €), commission par soustraction")
    void accept_gridBid_splitInverse() {
        BidEntity bid = stubThread(SENDER_UID, sender(), TRAVELER_ID, "51.00");

        BidNegotiationResponse response = service.accept(BID_ID, SENDER_UID);

        assertThat(bid.getNegotiatedGrossEur()).isEqualByComparingTo("51.00");
        assertThat(bid.getNegotiatedNetEur()).isEqualByComparingTo("50.00");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        // Vue expéditeur : sa commission, jamais le net ni les réductions du voyageur.
        assertThat(response.commissionEur()).isEqualByComparingTo("1.00");
        assertThat(response.netEur()).isNull();
        assertThat(response.commissionGridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(response.commissionOverrideRate()).isNull();
        assertThat(response.commissionVoucherFactor()).isNull();
        assertThat(response.commissionRate()).isNull();
    }

    @Test
    @DisplayName("sans réductions, brut 21,40 € : le voyageur est notifié de son net 20,00 €")
    void counter_gridBid_travelerNotifiedWithNetFromInverse() {
        BidEntity bid = stubThread(SENDER_UID, sender(), TRAVELER_ID, "30.00");
        bid.setCommissionOverrideRate(null);
        bid.setCommissionVoucherFactor(null);
        lenient().when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender()));

        service.counter(BID_ID, SENDER_UID, new BidNegotiationCounterRequest(new BigDecimal("21.40"), null));

        ArgumentCaptor<BidNegotiationMessagePostedEvent> event =
                ArgumentCaptor.forClass(BidNegotiationMessagePostedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientId()).isEqualTo(TRAVELER_ID);
        assertThat(event.getValue().amountForRecipient()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("brut qui ne couvre pas la commission minimum : 422, rien n'est écrit")
    void counter_gridBid_grossBelowMinimum_isRefused() {
        stubThread(SENDER_UID, sender(), TRAVELER_ID, "30.00");
        lenient().when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender()));

        // Avec le bon ×0,5, la plus petite commission vaut 0,50 € : 0,50 € ne paie aucun net.
        assertThatThrownBy(() -> service.counter(BID_ID, SENDER_UID,
                new BidNegotiationCounterRequest(new BigDecimal("0.50"), null)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("budget-below-commission-minimum");
                });
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("ouverture sous grille : le brut proposé est validé au contexte figé avant toute écriture")
    void propose_grid_grossBelowMinimum_isRefusedBeforeSave() {
        UserEntity sender = sender();
        AnnouncementEntity announcement = announcement();
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(bidService.assertCanBidOn(sender, announcement, "CLOTHING")).thenReturn("CLOTHING");
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.05"));
        when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));
        when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, null, null, null))
                .thenReturn(CommissionReductions.NONE);

        BidNegotiationStartRequest request = new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING", "Fatou Sarr", "+221701234567", true,
                "CASH", null, null, null, new BigDecimal("1.00"), null, null);

        assertThatThrownBy(() -> service.propose(ANNOUNCEMENT_ID, SENDER_UID, request, httpRequest))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("budget-below-commission-minimum"));
        verify(bidRepository, never()).save(any());
    }

    @Test
    @DisplayName("lignes de grille d'un fil à grille : prix unitaire du voyageur, frais comptés sur le total")
    void thread_gridBid_gridLinesShowTravelerUnitPrice() {
        stubThread(SENDER_UID, sender(), TRAVELER_ID, "60.00");
        BidGridItemEntity item = new BidGridItemEntity();
        item.setBidId(BID_ID);
        item.setLabelSnapshot("Valise");
        item.setUnitPriceNetSnapshot(new BigDecimal("5.00"));
        item.setQuantity(3);
        when(bidGridItemRepository.findByBidId(BID_ID)).thenReturn(List.of(item));

        BidNegotiationResponse response = service.thread(BID_ID, SENDER_UID);

        // Prix au kilo des listes (spec §5.3) : sous grille, la commission n'est pas
        // proportionnelle ; 5 € « frais compris » à l'unité vaudrait 5,50 € (minimum × bon),
        // soit 16,50 € pour 3 au lieu des 15,50 € que coûteraient ces 15 € seuls.
        assertThat(response.gridItems()).singleElement()
                .satisfies(line -> assertThat(line.unitPriceDisplayEur()).isEqualByComparingTo("5.00"));
        // Barème 25 € + 15 € = 40 € : grille 2,80 €, taux spécial 1,60 €, bon ×0,5 → 0,80 €.
        assertThat(response.suggestedGrossEur()).isEqualByComparingTo("40.80");
    }

    @Test
    @DisplayName("liste des fils, vue voyageur : net de la dernière proposition par l'inverse figé")
    void myNegotiations_gridBid_travelerNetFromInverse() {
        BidEntity bid = gridBid();
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler()));
        when(bidRepository.findNegotiationsForUser(TRAVELER_ID)).thenReturn(List.of(bid));
        when(announcementRepository.findAllById(Set.of(ANNOUNCEMENT_ID))).thenReturn(List.of(announcement()));
        when(messageRepository.findLatestByBidIdIn(List.of(BID_ID)))
                .thenReturn(List.of(lastMessageFrom(SENDER_ID, "51.00")));
        when(userRepository.findAllById(Set.of(SENDER_ID))).thenReturn(List.of(sender()));

        BidNegotiationSummaryResponse row = service.myNegotiations(TRAVELER_UID, false).get(0);

        assertThat(row.proposedGrossEur()).isEqualByComparingTo("51.00");
        assertThat(row.proposedNetEur()).isEqualByComparingTo("50.00");
    }
}
