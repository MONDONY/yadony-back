package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rangement / retrait d'une discussion de prix sur un trajet (FLUTTER-EJ) : seule la vue
 * de l'appelant change, et seule une discussion terminée se range ou se retire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidNegotiationService — archiver / supprimer une discussion de prix")
class BidNegotiationArchiveServiceTest {

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

    private BidNegotiationService service;

    private static final String SENDER_UID = "uid-sender-archive";
    private static final String TRAVELER_UID = "uid-traveler-archive";
    private static final String THIRD_UID = "uid-third-archive";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID THIRD_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        service = new BidNegotiationService(
                bidRepository, announcementRepository, userRepository, messageRepository,
                customItemRepository, bidGridItemRepository, annGridItemRepository,
                bidPhotoService, commissionRateResolver, auditService, eventPublisher,
                new MatchingNegotiationConfig(3, 1, 24, "-"), bidService, mobileMoneyPort);

        announcement = new AnnouncementEntity();
        announcement.setTravelerId(TRAVELER_ID);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now(ZoneOffset.UTC).minusDays(2));
        setId(announcement, ANNOUNCEMENT_ID);

        lenient().when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(user(SENDER_ID, SENDER_UID)));
        lenient().when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(user(TRAVELER_ID, TRAVELER_UID)));
        lenient().when(userRepository.findByFirebaseUid(THIRD_UID)).thenReturn(Optional.of(user(THIRD_ID, THIRD_UID)));
        lenient().when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        lenient().when(announcementRepository.findAllById(any())).thenReturn(List.of(announcement));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UserEntity user(UUID id, String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setUsername(uid);
        setId(u, id);
        return u;
    }

    private BidEntity bid(BidStatus status, boolean negotiated) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(status);
        b.setCurrency("EUR");
        if (negotiated) {
            b.setNegotiatedGrossEur(new BigDecimal("45.00"));
        }
        setId(b, BID_ID);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        return b;
    }

    private BidEntity closedBid() {
        return bid(BidStatus.NEGOTIATION_CLOSED, false);
    }

    private static void assertProblem(Throwable t, HttpStatus status, String code) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }

    // ─── Archiver ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("l'expéditeur range une discussion close : seule SA colonne est posée, audit BID_NEGOTIATION_ARCHIVED")
    void archive_bySender_setsSenderColumnOnly() {
        closedBid();

        service.archive(BID_ID, SENDER_UID);

        verify(bidRepository).updateNegotiationSenderArchivedAt(eq(BID_ID), any(LocalDateTime.class));
        verify(bidRepository, never()).updateNegotiationTravelerArchivedAt(any(), any());
        verify(auditService).log("BID", BID_ID, "BID_NEGOTIATION_ARCHIVED", SENDER_ID,
                Map.of("role", "SENDER", "status", "NEGOTIATION_CLOSED"));
    }

    @Test
    @DisplayName("le voyageur range la discussion de son côté uniquement")
    void archive_byTraveler_setsTravelerColumnOnly() {
        closedBid();

        service.archive(BID_ID, TRAVELER_UID);

        verify(bidRepository).updateNegotiationTravelerArchivedAt(eq(BID_ID), any(LocalDateTime.class));
        verify(bidRepository, never()).updateNegotiationSenderArchivedAt(any(), any());
        verify(auditService).log("BID", BID_ID, "BID_NEGOTIATION_ARCHIVED", TRAVELER_ID,
                Map.of("role", "TRAVELER", "status", "NEGOTIATION_CLOSED"));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "COMPLETED", "CANCELLED", "EXPIRED", "PAYMENT_ESCROWED",
            "HANDED_OVER", "IN_TRANSIT", "ARRIVED", "NEGOTIATION_CLOSED"})
    @DisplayName("un accord négocié réglé puis mené à son terme est terminé : il se range")
    void archive_settledAgreement_isTerminal(BidStatus status) {
        bid(status, true);

        service.archive(BID_ID, SENDER_UID);

        verify(bidRepository).updateNegotiationSenderArchivedAt(eq(BID_ID), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "PAYMENT_ESCROWED", "HANDED_OVER",
            "IN_TRANSIT", "ARRIVED", "COMPLETED"})
    @DisplayName("FLUTTER-HM : un fil de trajet conclu puis réglé, de nouveau listé, se retire aussi")
    void hide_settledAgreement_isTerminal(BidStatus status) {
        bid(status, true);

        service.hide(BID_ID, TRAVELER_UID);

        verify(bidRepository).updateNegotiationTravelerHiddenAt(eq(BID_ID), any(LocalDateTime.class));
        verify(auditService).log("BID", BID_ID, "BID_NEGOTIATION_HIDDEN", TRAVELER_ID,
                Map.of("role", "TRAVELER", "status", status.name()));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"NEGOTIATING", "AWAITING_PAYMENT", "PENDING"})
    @DisplayName("une discussion en cours (ou un accord pas encore réglé) → 409 negotiation-still-open")
    void archive_openNegotiation_returns409(BidStatus status) {
        bid(status, status != BidStatus.NEGOTIATING);

        assertThatThrownBy(() -> service.archive(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.CONFLICT, "negotiation-still-open"));
        verify(bidRepository, never()).updateNegotiationSenderArchivedAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("archiver deux fois est idempotent : ni seconde écriture ni second audit")
    void archive_twice_isIdempotent() {
        closedBid().setNegotiationSenderArchivedAt(LocalDateTime.now(ZoneOffset.UTC));

        service.archive(BID_ID, SENDER_UID);

        verify(bidRepository, never()).updateNegotiationSenderArchivedAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("un tiers ne peut ni ranger ni retirer la discussion → 403")
    void archive_nonParticipant_returns403() {
        closedBid();

        assertThatThrownBy(() -> service.archive(BID_ID, THIRD_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.FORBIDDEN, "forbidden"));
        assertThatThrownBy(() -> service.hide(BID_ID, THIRD_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.FORBIDDEN, "forbidden"));
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("une offre ferme n'est pas une discussion de prix → 404 negotiation-not-found")
    void archive_firmBid_returns404() {
        bid(BidStatus.COMPLETED, false);

        assertThatThrownBy(() -> service.archive(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.NOT_FOUND, "negotiation-not-found"));
    }

    @Test
    @DisplayName("une discussion retirée n'existe plus pour l'appelant : archiver → 404")
    void archive_hiddenBySelf_returns404() {
        closedBid().setNegotiationSenderHiddenAt(LocalDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.archive(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.NOT_FOUND, "negotiation-not-found"));
    }

    @Test
    @DisplayName("le retrait de l'AUTRE partie ne gêne pas l'appelant")
    void archive_hiddenByOtherParty_stillWorks() {
        closedBid().setNegotiationTravelerHiddenAt(LocalDateTime.now(ZoneOffset.UTC));

        service.archive(BID_ID, SENDER_UID);

        verify(bidRepository).updateNegotiationSenderArchivedAt(eq(BID_ID), any(LocalDateTime.class));
    }

    // ─── Désarchiver ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("désarchiver remet la colonne de l'appelant à NULL et audite")
    void unarchive_clearsCallerColumn() {
        closedBid().setNegotiationTravelerArchivedAt(LocalDateTime.now(ZoneOffset.UTC));

        service.unarchive(BID_ID, TRAVELER_UID);

        verify(bidRepository).updateNegotiationTravelerArchivedAt(eq(BID_ID), isNull());
        verify(auditService).log("BID", BID_ID, "BID_NEGOTIATION_UNARCHIVED", TRAVELER_ID,
                Map.of("role", "TRAVELER", "status", "NEGOTIATION_CLOSED"));
    }

    @Test
    @DisplayName("désarchiver côté expéditeur")
    void unarchive_bySender() {
        closedBid().setNegotiationSenderArchivedAt(LocalDateTime.now(ZoneOffset.UTC));

        service.unarchive(BID_ID, SENDER_UID);

        verify(bidRepository).updateNegotiationSenderArchivedAt(eq(BID_ID), isNull());
    }

    @Test
    @DisplayName("désarchiver une discussion non rangée ne fait rien")
    void unarchive_notArchived_isNoop() {
        closedBid();

        service.unarchive(BID_ID, SENDER_UID);

        verify(bidRepository, never()).updateNegotiationSenderArchivedAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("désarchiver une discussion retirée → 404")
    void unarchive_hidden_returns404() {
        BidEntity b = closedBid();
        b.setNegotiationSenderArchivedAt(LocalDateTime.now(ZoneOffset.UTC));
        b.setNegotiationSenderHiddenAt(LocalDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.unarchive(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.NOT_FOUND, "negotiation-not-found"));
    }

    // ─── Supprimer (retirer de sa liste) ────────────────────────────────────────

    @Test
    @DisplayName("supprimer pose la date de retrait de l'appelant, jamais de DELETE ; audit BID_NEGOTIATION_HIDDEN")
    void hide_bySender_setsHiddenColumn() {
        closedBid();

        service.hide(BID_ID, SENDER_UID);

        verify(bidRepository).updateNegotiationSenderHiddenAt(eq(BID_ID), any(LocalDateTime.class));
        verify(bidRepository, never()).delete(any());
        verify(bidRepository, never()).updateNegotiationTravelerHiddenAt(any(), any());
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_HIDDEN"), eq(SENDER_ID), anyMap());
    }

    @Test
    @DisplayName("le voyageur supprime de son côté, une discussion déjà archivée comprise")
    void hide_byTraveler_archivedThread() {
        closedBid().setNegotiationTravelerArchivedAt(LocalDateTime.now(ZoneOffset.UTC));

        service.hide(BID_ID, TRAVELER_UID);

        verify(bidRepository).updateNegotiationTravelerHiddenAt(eq(BID_ID), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("supprimer une discussion en cours → 409")
    void hide_openNegotiation_returns409() {
        bid(BidStatus.NEGOTIATING, false);

        assertThatThrownBy(() -> service.hide(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.CONFLICT, "negotiation-still-open"));
        verify(bidRepository, never()).updateNegotiationSenderHiddenAt(any(), any());
    }

    @Test
    @DisplayName("supprimer deux fois est idempotent")
    void hide_twice_isIdempotent() {
        closedBid().setNegotiationSenderHiddenAt(LocalDateTime.now(ZoneOffset.UTC));

        service.hide(BID_ID, SENDER_UID);

        verify(bidRepository, never()).updateNegotiationSenderHiddenAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("supprimer une offre ferme → 404")
    void hide_firmBid_returns404() {
        bid(BidStatus.CANCELLED, false);

        assertThatThrownBy(() -> service.hide(BID_ID, SENDER_UID))
                .satisfies(t -> assertProblem(t, HttpStatus.NOT_FOUND, "negotiation-not-found"));
    }

    // ─── Listes ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("archived=true lit le filtre « Archivées » et marque la ligne archived pour le demandeur")
    void myNegotiations_archived_readsArchivedQuery() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.NEGOTIATION_CLOSED);
        b.setCurrency("EUR");
        b.setNegotiationSenderArchivedAt(LocalDateTime.now(ZoneOffset.UTC));
        setId(b, BID_ID);
        when(bidRepository.findArchivedNegotiationsForUser(SENDER_ID)).thenReturn(List.of(b));

        List<BidNegotiationSummaryResponse> rows = service.myNegotiations(SENDER_UID, true);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.archived()).isTrue();
            assertThat(r.myTurn()).isFalse();
            assertThat(r.role()).isEqualTo("SENDER");
        });
        verify(bidRepository, never()).findNegotiationsForUser(any());
    }

    @Test
    @DisplayName("le rangement de l'expéditeur ne marque pas la ligne du voyageur")
    void myNegotiations_archivedFlag_isPerParticipant() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.NEGOTIATING);
        b.setCurrency("EUR");
        b.setNegotiationSenderArchivedAt(LocalDateTime.now(ZoneOffset.UTC));
        setId(b, BID_ID);
        when(bidRepository.findNegotiationsForUser(TRAVELER_ID)).thenReturn(List.of(b));

        List<BidNegotiationSummaryResponse> rows = service.myNegotiations(TRAVELER_UID, false);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.archived()).isFalse();
            assertThat(r.role()).isEqualTo("TRAVELER");
        });
        verify(bidRepository, never()).findArchivedNegotiationsForUser(any());
    }
}
