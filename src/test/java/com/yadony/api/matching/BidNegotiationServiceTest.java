package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidCustomItemRequest;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidNegotiationService — moteur de rounds")
class BidNegotiationServiceTest {

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
    @Mock private HttpServletRequest httpRequest;

    /** Surcharges du profil test : 3 tours, 1 h d'inactivité, 24 h pour payer. */
    private final MatchingNegotiationConfig config = new MatchingNegotiationConfig(3, 1, 24, "-");

    private BidNegotiationService service;

    private static final String SENDER_UID = "uid-sender-nego";
    private static final String TRAVELER_UID = "uid-traveler-nego";
    private static final String THIRD_UID = "uid-third-nego";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID THIRD_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BidNegotiationService(
                bidRepository, announcementRepository, userRepository, messageRepository,
                customItemRepository, bidGridItemRepository, annGridItemRepository,
                bidPhotoService, commissionRateResolver, auditService, eventPublisher,
                config, bidService);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setUpdatedAt(Object entity, LocalDateTime value) {
        try {
            Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity buildSender() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(SENDER_UID);
        u.setUsername("user-sender");
        u.setFirstName("Aminata");
        u.getRoles().add(Role.SENDER);
        setId(u, SENDER_ID);
        return u;
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(TRAVELER_UID);
        u.setUsername("user-traveler");
        u.setFirstName("Moussa");
        u.getRoles().add(Role.TRAVELER);
        setId(u, TRAVELER_ID);
        return u;
    }

    private UserEntity buildThirdParty() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(THIRD_UID);
        u.setUsername("user-third");
        setId(u, THIRD_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement() {
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
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidEntity buildNegotiatingBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.NEGOTIATING);
        b.setNegotiationRound(1);
        b.setCommissionRate(new BigDecimal("0.05"));
        b.setCurrency("EUR");
        b.setWeightKg(new BigDecimal("5.0"));
        b.setDescription("Vêtements");
        b.setContentCategory("CLOTHING");
        setId(b, BID_ID);
        setUpdatedAt(b, LocalDateTime.now(ZoneOffset.UTC));
        return b;
    }

    private BidNegotiationStartRequest buildStartRequest(BigDecimal proposed) {
        return buildStartRequest(proposed, null, null);
    }

    private BidNegotiationStartRequest buildStartRequest(BigDecimal proposed,
                                                        List<BidCustomItemRequest> customItems,
                                                        List<BidGridItemRequest> gridItems) {
        return new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                "Fatou Sarr", "+221701234567", true,
                "CASH", null, null, null,
                proposed, customItems, gridItems);
    }

    private BidNegotiationMessageEntity lastMessageFrom(UUID authorId, String gross) {
        BidNegotiationMessageEntity m = BidNegotiationMessageEntity.create(
                BID_ID, authorId, BidNegotiationMessageKind.PROPOSAL,
                gross == null ? null : new BigDecimal(gross), null);
        setId(m, UUID.randomUUID());
        return m;
    }

    private void stubSavedBid() {
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) {
                setId(b, BID_ID);
            }
            return b;
        });
    }

    // ─── propose ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("propose")
    class Propose {

        @Test
        @DisplayName("trajet non négociable → 422 announcement-not-negotiable")
        void propose_onNonNegotiableAnnouncement_throws422() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setNegotiable(false);

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> service.propose(
                    ANNOUNCEMENT_ID, SENDER_UID, buildStartRequest(new BigDecimal("45.00")), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement-not-negotiable");
                    });

            verify(bidRepository, never()).save(any(BidEntity.class));
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("proposition valide → bid NEGOTIATING, round 1, message PROPOSAL, taux snapshoté, audit")
        void propose_valid_createsNegotiatingBid() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));
            when(bidService.assertCanBidOn(sender, announcement, "CLOTHING")).thenReturn("CLOTHING");
            when(bidService.resolvePaymentMethodFor(announcement, "CASH"))
                    .thenReturn(com.yadony.api.payments.cash.PaymentMethod.CASH);
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.05"));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(buildTraveler()));
            stubSavedBid();

            BidNegotiationResponse response = service.propose(
                    ANNOUNCEMENT_ID, SENDER_UID, buildStartRequest(new BigDecimal("45.00")), httpRequest);

            ArgumentCaptor<BidEntity> bidCaptor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(bidCaptor.capture());
            BidEntity saved = bidCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(BidStatus.NEGOTIATING);
            assertThat(saved.getNegotiationRound()).isEqualTo(1);
            assertThat(saved.getCommissionRate()).isEqualByComparingTo("0.05");
            assertThat(saved.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(saved.getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(saved.getCurrency()).isEqualTo("EUR");
            assertThat(saved.getContentCategory()).isEqualTo("CLOTHING");
            assertThat(saved.getRecipientName()).isEqualTo("Fatou Sarr");

            ArgumentCaptor<BidNegotiationMessageEntity> msgCaptor =
                    ArgumentCaptor.forClass(BidNegotiationMessageEntity.class);
            verify(messageRepository).save(msgCaptor.capture());
            assertThat(msgCaptor.getValue().getKind()).isEqualTo(BidNegotiationMessageKind.PROPOSAL);
            assertThat(msgCaptor.getValue().getProposedGrossEur()).isEqualByComparingTo("45.00");
            assertThat(msgCaptor.getValue().getAuthorId()).isEqualTo(SENDER_ID);

            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_PROPOSED"),
                    eq(SENDER_ID), anyMap());
            verify(eventPublisher).publishEvent(any(BidNegotiationMessagePostedEvent.class));

            assertThat(response.bidId()).isEqualTo(BID_ID);
            assertThat(response.status()).isEqualTo("NEGOTIATING");
            assertThat(response.round()).isEqualTo(1);
            assertThat(response.maxRounds()).isEqualTo(3);
            assertThat(response.proposedGrossEur()).isEqualByComparingTo("45.00");
        }

        @Test
        @DisplayName("une garde partagée qui refuse (déjà une demande) remonte telle quelle en 409")
        void propose_delegatesToSharedGuards_alreadyBid() {
            // La logique réelle des gardes vit dans BidService#assertCanBidOn et est
            // couverte par BidServiceTest / BidCreateGuardTest : ici on vérifie
            // uniquement que la négociation les applique et propage leur refus.
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));
            when(bidService.assertCanBidOn(sender, announcement, "CLOTHING"))
                    .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "already-bid",
                            "Demande existante", "Vous avez déjà une demande en cours"));

            assertThatThrownBy(() -> service.propose(
                    ANNOUNCEMENT_ID, SENDER_UID, buildStartRequest(new BigDecimal("45.00")), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("already-bid"));

            verify(bidRepository, never()).save(any(BidEntity.class));
        }

        @Test
        @DisplayName("proposer sur son propre trajet remonte 409 cannot-bid-own-announcement")
        void propose_onOwnAnnouncement_throws409() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));
            when(bidService.assertCanBidOn(sender, announcement, "CLOTHING"))
                    .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                            "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                            "Vous ne pouvez pas faire une demande sur votre propre annonce"));

            assertThatThrownBy(() -> service.propose(
                    ANNOUNCEMENT_ID, SENDER_UID, buildStartRequest(new BigDecimal("45.00")), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("cannot-bid-own-announcement"));
        }

        @Test
        @DisplayName("les articles hors grille sont persistés avec leur montant unitaire")
        void propose_withCustomItems_persistsUnitAmounts() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));
            when(bidService.assertCanBidOn(sender, announcement, "CLOTHING")).thenReturn("CLOTHING");
            when(bidService.resolvePaymentMethodFor(announcement, "CASH"))
                    .thenReturn(com.yadony.api.payments.cash.PaymentMethod.CASH);
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.05"));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(buildTraveler()));
            stubSavedBid();

            service.propose(ANNOUNCEMENT_ID, SENDER_UID,
                    buildStartRequest(new BigDecimal("45.00"),
                            List.of(new BidCustomItemRequest("Tapis", 2, new BigDecimal("12.50"))), null),
                    httpRequest);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BidCustomItemEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(customItemRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            BidCustomItemEntity item = captor.getValue().get(0);
            assertThat(item.getLabel()).isEqualTo("Tapis");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmountEur()).isEqualByComparingTo("12.50");
            assertThat(item.getBidId()).isEqualTo(BID_ID);
        }

        @Test
        @DisplayName("une proposition sans poids, sans article de grille ni hors grille est refusée")
        void propose_emptyBid_throws422() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));
            when(bidService.assertCanBidOn(sender, announcement, "CLOTHING")).thenReturn("CLOTHING");

            BidNegotiationStartRequest empty = new BidNegotiationStartRequest(
                    null, "Vêtements", "CLOTHING", "Fatou Sarr", "+221701234567", true,
                    "CASH", null, null, null, new BigDecimal("45.00"), null, null);

            assertThatThrownBy(() -> service.propose(ANNOUNCEMENT_ID, SENDER_UID, empty, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-empty"));
        }

        @Test
        @DisplayName("proposer sur un trajet déjà parti → 409 negotiation-closed")
        void propose_onDepartedTrip_throws409() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setDepartureDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> service.propose(
                    ANNOUNCEMENT_ID, SENDER_UID, buildStartRequest(new BigDecimal("45.00")), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("negotiation-closed"));
        }
    }

    // ─── counter ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("counter")
    class Counter {

        @Test
        @DisplayName("contre-offrir alors qu'on a parlé en dernier → 409 not-your-turn")
        void counter_bySameAuthor_throws409() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));

            assertThatThrownBy(() -> service.counter(BID_ID, SENDER_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("not-your-turn");
                    });

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("contre-offre de l'autre partie → round incrémenté, message COUNTER")
        void counter_byOtherParty_incrementsRound() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            BidNegotiationResponse response = service.counter(BID_ID, TRAVELER_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), "Je propose 40"));

            assertThat(bid.getNegotiationRound()).isEqualTo(2);

            ArgumentCaptor<BidNegotiationMessageEntity> msgCaptor =
                    ArgumentCaptor.forClass(BidNegotiationMessageEntity.class);
            verify(messageRepository).save(msgCaptor.capture());
            assertThat(msgCaptor.getValue().getKind()).isEqualTo(BidNegotiationMessageKind.COUNTER);
            assertThat(msgCaptor.getValue().getProposedGrossEur()).isEqualByComparingTo("40.00");
            assertThat(msgCaptor.getValue().getAuthorId()).isEqualTo(TRAVELER_ID);

            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_COUNTERED"),
                    eq(TRAVELER_ID), anyMap());
            assertThat(response.round()).isEqualTo(2);
            assertThat(response.proposedGrossEur()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("au plafond de tours → 409 negotiation-round-limit-reached")
        void counter_atRoundLimit_throws409() {
            BidEntity bid = buildNegotiatingBid();
            bid.setNegotiationRound(3);

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));

            assertThatThrownBy(() -> service.counter(BID_ID, TRAVELER_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("negotiation-round-limit-reached");
                    });
        }

        @Test
        @DisplayName("un fil qui n'est plus en négociation → 409 negotiation-closed")
        void counter_onClosedThread_throws409() {
            BidEntity bid = buildNegotiatingBid();
            bid.setStatus(BidStatus.REJECTED);

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));

            assertThatThrownBy(() -> service.counter(BID_ID, TRAVELER_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("negotiation-closed"));
        }

        @Test
        @DisplayName("après la date de départ, plus aucune contre-offre → 409 negotiation-closed")
        void counter_afterDeparture_throws409() {
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setDepartureDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> service.counter(BID_ID, TRAVELER_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("negotiation-closed"));
        }

        @Test
        @DisplayName("un tiers ne peut pas contre-offrir → 403")
        void counter_byThirdParty_throws403() {
            when(userRepository.findByFirebaseUid(THIRD_UID)).thenReturn(Optional.of(buildThirdParty()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));

            assertThatThrownBy(() -> service.counter(BID_ID, THIRD_UID,
                    new BidNegotiationCounterRequest(new BigDecimal("40.00"), null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ─── accept ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("accept")
    class Accept {

        @Test
        @DisplayName("accepter sa propre proposition → 409 not-your-turn")
        void accept_bySameAuthor_throws409() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));

            assertThatThrownBy(() -> service.accept(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("not-your-turn"));
        }

        @Test
        @DisplayName("accord → brut et net figés, statut AWAITING_PAYMENT, audit")
        void accept_valid_freezesPriceAndMovesToAwaitingPayment() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            BidNegotiationResponse response = service.accept(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
            assertThat(bid.getNegotiatedGrossEur()).isEqualByComparingTo("45.00");
            assertThat(bid.getNegotiatedNetEur()).isEqualByComparingTo("42.86");
            assertThat(bid.getAwaitingPaymentExpiresAt()).isNotNull();

            ArgumentCaptor<BidNegotiationMessageEntity> msgCaptor =
                    ArgumentCaptor.forClass(BidNegotiationMessageEntity.class);
            verify(messageRepository).save(msgCaptor.capture());
            assertThat(msgCaptor.getValue().getKind()).isEqualTo(BidNegotiationMessageKind.ACCEPT);

            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_ACCEPTED"),
                    eq(TRAVELER_ID), anyMap());
            assertThat(response.status()).isEqualTo("AWAITING_PAYMENT");
        }

        @Test
        @DisplayName("le taux utilisé est celui figé sur le bid, jamais une nouvelle résolution")
        void accept_usesSnapshottedCommissionRate() {
            BidEntity bid = buildNegotiatingBid();
            bid.setCommissionRate(new BigDecimal("0.05"));

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            service.accept(BID_ID, TRAVELER_UID);

            // 45,00 à 5 % = 42,86 net ; à 50 % ce serait 30,00 : la valeur prouve
            // que le résolveur n'a pas été rappelé après la première proposition.
            assertThat(bid.getNegotiatedNetEur()).isEqualByComparingTo("42.86");
            verifyNoInteractions(commissionRateResolver);
        }

        /**
         * Un accord en ESPÈCES n'a aucun paiement en ligne à attendre : le laisser en
         * {@code AWAITING_PAYMENT} le condamnait à être soft-deleté par
         * {@code AwaitingPaymentCleanupScheduler} sans qu'aucun écran ne puisse le
         * débloquer. Il rejoint donc le chemin d'un bid cash ordinaire : {@code PENDING},
         * puis {@code POST /bids/{id}/accept-with-commission} côté voyageur.
         */
        @Test
        @DisplayName("accord en espèces → PENDING, pas d'attente de paiement en ligne")
        void accept_cashBid_joinsClassicCashPath() {
            BidEntity bid = buildNegotiatingBid();
            bid.setPaymentMethod(PaymentMethod.CASH);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            BidNegotiationResponse response = service.accept(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.PENDING);
            assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
            // Le prix reste figé : c'est lui qui sert de base à la commission voyageur.
            assertThat(bid.getNegotiatedGrossEur()).isEqualByComparingTo("45.00");
            assertThat(bid.getNegotiatedNetEur()).isEqualByComparingTo("42.86");
            assertThat(response.status()).isEqualTo("PENDING");
        }

        /**
         * L'accord entre dans la file d'attente du voyageur à l'instant de
         * l'acceptation, pas à l'ouverture du fil. Sans ce repère, un fil de plus de
         * 24 h — le cas nominal, la fenêtre d'inactivité étant de 72 h — voyait son
         * accord annulé par {@code BidTimeoutScheduler} au tick suivant.
         */
        @Test
        @DisplayName("accord en espèces → l'horloge du timeout voyageur repart de l'accord")
        void accept_cashBid_restartsTravelerResponseClock() {
            BidEntity bid = buildNegotiatingBid();
            bid.setPaymentMethod(PaymentMethod.CASH);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
            service.accept(BID_ID, TRAVELER_UID);

            assertThat(bid.getPendingSince())
                    .describedAs("le compte à rebours « demande sans réponse » repart de l'accord")
                    .isAfter(before);
        }

        @Test
        @DisplayName("accord par carte → aucune entrée dans la file du voyageur")
        void accept_cardBid_doesNotEnterTravelerQueue() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            service.accept(BID_ID, TRAVELER_UID);

            // AWAITING_PAYMENT n'est pas ramassé par BidTimeoutScheduler (qui ne
            // regarde que PENDING) : c'est AwaitingPaymentCleanupScheduler qui s'en
            // charge, sur awaitingPaymentExpiresAt.
            assertThat(bid.getPendingSince()).isNull();
            assertThat(bid.getAwaitingPaymentExpiresAt()).isNotNull();
        }

        @Test
        @DisplayName("accord en espèces → le voyageur est notifié comme pour une demande cash")
        void accept_cashBid_notifiesTravelerLikeAFreshCashBid() {
            BidEntity bid = buildNegotiatingBid();
            bid.setPaymentMethod(PaymentMethod.CASH);
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(TRAVELER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            service.accept(BID_ID, SENDER_UID);

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues())
                    .filteredOn(CashBidCreatedEvent.class::isInstance)
                    .singleElement()
                    .satisfies(e -> {
                        CashBidCreatedEvent cash = (CashBidCreatedEvent) e;
                        assertThat(cash.bidId()).isEqualTo(BID_ID);
                        assertThat(cash.travelerId()).isEqualTo(TRAVELER_ID);
                        assertThat(cash.senderId()).isEqualTo(SENDER_ID);
                    });
        }

        @Test
        @DisplayName("accord par carte → aucun événement cash, on reste en AWAITING_PAYMENT")
        void accept_cardBid_publishesNoCashEvent() {
            BidEntity bid = buildNegotiatingBid();
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            service.accept(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).noneMatch(CashBidCreatedEvent.class::isInstance);
        }
    }

    // ─── reject / cancel ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reject et cancel")
    class RejectAndCancel {

        @Test
        @DisplayName("reject → statut NEGOTIATION_CLOSED et message REJECT")
        void reject_closesThread() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            stubSavedBid();

            BidNegotiationResponse response = service.reject(BID_ID, TRAVELER_UID);

            // NEGOTIATION_CLOSED et pas REJECTED : un fil refusé n'a jamais été une
            // réservation. Sous REJECTED il comptait comme un refus de colis du
            // voyageur dans son taux d'acceptation, même quand c'est l'expéditeur
            // qui refusait le prix.
            assertThat(bid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
            ArgumentCaptor<BidNegotiationMessageEntity> msgCaptor =
                    ArgumentCaptor.forClass(BidNegotiationMessageEntity.class);
            verify(messageRepository).save(msgCaptor.capture());
            assertThat(msgCaptor.getValue().getKind()).isEqualTo(BidNegotiationMessageKind.REJECT);
            // Le geste (refus / retrait) reste distingué par l'action d'audit.
            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_REJECTED"),
                    eq(TRAVELER_ID), anyMap());
            assertThat(response.status()).isEqualTo("NEGOTIATION_CLOSED");
        }

        @Test
        @DisplayName("cancel par l'expéditeur → statut NEGOTIATION_CLOSED et audit")
        void cancel_bySender_cancelsThread() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(buildTraveler()));
            stubSavedBid();

            service.cancel(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_CANCELLED"),
                    eq(SENDER_ID), anyMap());
        }

        @Test
        @DisplayName("cancel par un tiers → 403")
        void cancel_byThirdParty_throws403() {
            when(userRepository.findByFirebaseUid(THIRD_UID)).thenReturn(Optional.of(buildThirdParty()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));

            assertThatThrownBy(() -> service.cancel(BID_ID, THIRD_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));

            verify(bidRepository, never()).save(any(BidEntity.class));
        }
    }

    // ─── lecture ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("thread, markRead et myNegotiations")
    class Reading {

        @Test
        @DisplayName("le voyageur voit son net, jamais l'expéditeur")
        void thread_travelerSeesNet_senderSeesGrossAndCommission() {
            BidEntity bid = buildNegotiatingBid();
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(SENDER_ID, "45.00")));

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
            BidNegotiationResponse travelerView = service.thread(BID_ID, TRAVELER_UID);
            assertThat(travelerView.netEur()).isEqualByComparingTo("42.86");
            assertThat(travelerView.proposedGrossEur()).isEqualByComparingTo("45.00");
            assertThat(travelerView.myTurn()).isTrue();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(buildTraveler()));
            BidNegotiationResponse senderView = service.thread(BID_ID, SENDER_UID);
            assertThat(senderView.netEur()).isNull();
            assertThat(senderView.proposedGrossEur()).isEqualByComparingTo("45.00");
            assertThat(senderView.commissionEur()).isEqualByComparingTo("2.14");
            assertThat(senderView.myTurn()).isFalse();

            assertThat(travelerView.role()).isEqualTo("TRAVELER");
            assertThat(senderView.role()).isEqualTo("SENDER");
        }

        /**
         * Le rôle ne se déduit pas de la présence d'un montant.
         *
         * <p>Le client lisait {@code netEur != null} pour savoir s'il affichait la vue
         * voyageur. Or les deux montants sont tus tant qu'aucun brut n'a été proposé :
         * le même {@code null} disait alors « tu es l'expéditeur ». Un voyageur ouvrant
         * un fil sans montant se voyait servir la vue d'en face.
         */
        @Test
        @DisplayName("le rôle reste juste même sans aucun montant proposé")
        void thread_roleIsExplicit_evenWithoutAnyAmount() {
            BidEntity bid = buildNegotiatingBid();
            bid.setNegotiatedGrossEur(null);
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            // Aucun message : rien ne porte de brut, donc netEur ET commissionEur sont nuls.
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.empty());
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));

            BidNegotiationResponse travelerView = service.thread(BID_ID, TRAVELER_UID);

            assertThat(travelerView.netEur()).isNull();
            assertThat(travelerView.commissionEur()).isNull();
            assertThat(travelerView.role())
                    .describedAs("netEur nul ne veut pas dire « expéditeur »")
                    .isEqualTo("TRAVELER");
        }

        @Test
        @DisplayName("un tiers ne peut pas lire le fil → 403")
        void thread_byThirdParty_throws403() {
            when(userRepository.findByFirebaseUid(THIRD_UID)).thenReturn(Optional.of(buildThirdParty()));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(buildNegotiatingBid()));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));

            assertThatThrownBy(() -> service.thread(BID_ID, THIRD_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("markRead pose la date de lecture du bon côté")
        void markRead_setsTheRightSide() {
            BidEntity bid = buildNegotiatingBid();
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));

            service.markRead(BID_ID, SENDER_UID);
            assertThat(bid.getSenderLastReadAt()).isNotNull();
            assertThat(bid.getTravelerLastReadAt()).isNull();

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(buildTraveler()));
            service.markRead(BID_ID, TRAVELER_UID);
            assertThat(bid.getTravelerLastReadAt()).isNotNull();
        }

        @Test
        @DisplayName("myNegotiations renvoie le rôle du demandeur et le montant courant")
        void myNegotiations_returnsRoleAndAmount() {
            BidEntity bid = buildNegotiatingBid();
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(buildSender()));
            when(bidRepository.findNegotiationsForUser(SENDER_ID)).thenReturn(List.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(buildAnnouncement()));
            when(messageRepository.findFirstByBidIdOrderByCreatedAtDesc(BID_ID))
                    .thenReturn(Optional.of(lastMessageFrom(TRAVELER_ID, "40.00")));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(buildTraveler()));

            List<BidNegotiationSummaryResponse> list = service.myNegotiations(SENDER_UID);

            assertThat(list).hasSize(1);
            BidNegotiationSummaryResponse row = list.get(0);
            assertThat(row.bidId()).isEqualTo(BID_ID);
            assertThat(row.role()).isEqualTo("SENDER");
            assertThat(row.proposedGrossEur()).isEqualByComparingTo("40.00");
            assertThat(row.myTurn()).isTrue();
            assertThat(row.hasUnread()).isTrue();
        }
    }
}
