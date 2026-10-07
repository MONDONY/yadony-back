package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.NegotiationThreadResponse;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.server.ResponseStatusException;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rangement / retrait d'une discussion de prix sur une demande d'envoi (FLUTTER-EJ) :
 * seule la vue de l'appelant change, et seul un fil terminé se range ou se retire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NegotiationService — archiver / supprimer une discussion de prix")
class NegotiationArchiveServiceTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private com.yadony.api.requests.NegotiationProperties negotiationProperties;
    @Mock private CommissionProperties commissionProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock private com.yadony.api.requests.NegotiationMobileMoneyPort mobileMoneyPort;

    private NegotiationService service;

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID OUTSIDER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID THREAD_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PackageRequestEntity request = new PackageRequestEntity();
        request.setSenderId(SENDER_ID);
        request.setStatus(PackageRequestStatus.OPEN);
        request.setDepartureCity("Paris");
        request.setArrivalCity("Dakar");
        request.setWeightKg(new BigDecimal("5"));
        setId(request, REQUEST_ID);
        lenient().when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new NegotiationService(requestRepo, threadRepo, messageRepo, userRepository,
                announcementRepo, eventPublisher, auditService, config, negotiationProperties,
                commissionProperties, cashGatePort, escrowPort, storageService, photoService,
                commissionRateResolver, exchangeRateService, mobileMoneyPort, TestMessages.resolver());
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static NegotiationThreadEntity newThread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(REQUEST_ID);
        t.setTravelerId(TRAVELER_ID);
        t.setTravelerTravelDate(LocalDate.now().plusDays(3));
        t.setTravelerAvailableKg(new BigDecimal("5"));
        t.setStatus(status);
        t.setCurrentPriceEur(new BigDecimal("30"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        setId(t, THREAD_ID);
        return t;
    }

    private NegotiationThreadEntity thread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = newThread(status);
        when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(t));
        return t;
    }

    private static void assertStatus(Throwable t, HttpStatus status, String reason) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException e = (ResponseStatusException) t;
        assertThat(e.getStatusCode()).isEqualTo(status);
        assertThat(e.getReason()).isEqualTo(reason);
    }

    private static void assertStillOpen(Throwable t) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("negotiation-still-open");
    }

    // ─── Archiver ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = NegotiationThreadStatus.class,
            names = {"ACCEPTED", "REJECTED", "CANCELLED", "AUTO_REJECTED", "EXPIRED"})
    @DisplayName("l'expéditeur range un fil terminé : seule SA colonne est posée, audit ARCHIVED_BY_USER")
    void archive_terminal_bySender(NegotiationThreadStatus status) {
        thread(status);

        service.archiveForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo).updateSenderArchivedAt(eq(THREAD_ID), any(LocalDateTime.class));
        verify(threadRepo, never()).updateTravelerArchivedAt(any(), any());
        verify(auditService).log("NEGOTIATION_THREAD", THREAD_ID, "ARCHIVED_BY_USER", SENDER_ID,
                Map.of("role", "SENDER", "status", status.name()));
    }

    @Test
    @DisplayName("le voyageur range le fil de son côté uniquement")
    void archive_byTraveler() {
        thread(NegotiationThreadStatus.EXPIRED);

        service.archiveForUser(TRAVELER_ID, THREAD_ID);

        verify(threadRepo).updateTravelerArchivedAt(eq(THREAD_ID), any(LocalDateTime.class));
        verify(threadRepo, never()).updateSenderArchivedAt(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = NegotiationThreadStatus.class,
            names = {"OPEN", "AWAITING_TRIP", "AWAITING_PAYMENT", "AWAITING_COMMISSION", "AWAITING_DEPOSIT"})
    @DisplayName("un fil en cours → 409 negotiation-still-open, rien n'est écrit")
    void archive_active_returns409(NegotiationThreadStatus status) {
        thread(status);

        assertThatThrownBy(() -> service.archiveForUser(SENDER_ID, THREAD_ID))
                .satisfies(NegotiationArchiveServiceTest::assertStillOpen);
        assertThatThrownBy(() -> service.hideForUser(TRAVELER_ID, THREAD_ID))
                .satisfies(NegotiationArchiveServiceTest::assertStillOpen);
        verify(threadRepo, never()).updateSenderArchivedAt(any(), any());
        verify(threadRepo, never()).updateTravelerHiddenAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("archiver deux fois est idempotent")
    void archive_twice_isIdempotent() {
        thread(NegotiationThreadStatus.REJECTED).setSenderArchivedAt(LocalDateTime.now());

        service.archiveForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo, never()).updateSenderArchivedAt(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("un non-participant → 403 negotiation/not-thread-participant")
    void archive_outsider_returns403() {
        thread(NegotiationThreadStatus.REJECTED);

        assertThatThrownBy(() -> service.archiveForUser(OUTSIDER_ID, THREAD_ID))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN, "negotiation/not-thread-participant"));
        assertThatThrownBy(() -> service.unarchiveForUser(OUTSIDER_ID, THREAD_ID))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN, "negotiation/not-thread-participant"));
        assertThatThrownBy(() -> service.hideForUser(OUTSIDER_ID, THREAD_ID))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN, "negotiation/not-thread-participant"));
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("fil inconnu → 404 thread/not-found")
    void archive_unknownThread_returns404() {
        when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archiveForUser(SENDER_ID, THREAD_ID))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND, "thread/not-found"));
    }

    @Test
    @DisplayName("demande liée introuvable → 404 request/not-found")
    void archive_orphanThread_returns404() {
        NegotiationThreadEntity t = newThread(NegotiationThreadStatus.REJECTED);
        t.setPackageRequestId(UUID.randomUUID());
        when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.archiveForUser(SENDER_ID, THREAD_ID))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND, "request/not-found"));
    }

    @Test
    @DisplayName("un fil retiré par l'appelant n'existe plus pour lui : archiver → 404")
    void archive_hiddenBySelf_returns404() {
        thread(NegotiationThreadStatus.REJECTED).setTravelerHiddenAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.archiveForUser(TRAVELER_ID, THREAD_ID))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND, "thread/not-found"));
    }

    @Test
    @DisplayName("le retrait de l'autre partie ne gêne pas l'appelant")
    void archive_hiddenByOther_stillWorks() {
        thread(NegotiationThreadStatus.REJECTED).setTravelerHiddenAt(LocalDateTime.now());

        service.archiveForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo).updateSenderArchivedAt(eq(THREAD_ID), any(LocalDateTime.class));
    }

    // ─── Désarchiver ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("désarchiver remet la colonne de l'appelant à NULL et audite UNARCHIVED_BY_USER")
    void unarchive_clearsColumn() {
        thread(NegotiationThreadStatus.CANCELLED).setTravelerArchivedAt(LocalDateTime.now());

        service.unarchiveForUser(TRAVELER_ID, THREAD_ID);

        verify(threadRepo).updateTravelerArchivedAt(eq(THREAD_ID), isNull());
        verify(auditService).log("NEGOTIATION_THREAD", THREAD_ID, "UNARCHIVED_BY_USER", TRAVELER_ID,
                Map.of("role", "TRAVELER", "status", "CANCELLED"));
    }

    @Test
    @DisplayName("désarchiver côté expéditeur")
    void unarchive_bySender() {
        thread(NegotiationThreadStatus.CANCELLED).setSenderArchivedAt(LocalDateTime.now());

        service.unarchiveForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo).updateSenderArchivedAt(eq(THREAD_ID), isNull());
    }

    @Test
    @DisplayName("désarchiver un fil non rangé ne fait rien")
    void unarchive_notArchived_isNoop() {
        thread(NegotiationThreadStatus.CANCELLED);

        service.unarchiveForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo, never()).updateSenderArchivedAt(any(), any());
        verifyNoInteractions(auditService);
    }

    // ─── Supprimer (retirer de sa liste) ────────────────────────────────────────

    @Test
    @DisplayName("supprimer pose la date de retrait de l'appelant, jamais de DELETE ; audit HIDDEN_BY_USER")
    void hide_terminal_bySender() {
        thread(NegotiationThreadStatus.ACCEPTED);

        service.hideForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo).updateSenderHiddenAt(eq(THREAD_ID), any(LocalDateTime.class));
        verify(threadRepo, never()).updateTravelerHiddenAt(any(), any());
        verify(threadRepo, never()).delete(any());
        verify(threadRepo, never()).deleteById(any());
        verify(auditService).log("NEGOTIATION_THREAD", THREAD_ID, "HIDDEN_BY_USER", SENDER_ID,
                Map.of("role", "SENDER", "status", "ACCEPTED"));
    }

    @Test
    @DisplayName("le voyageur supprime de son côté un fil déjà archivé")
    void hide_byTraveler_archived() {
        thread(NegotiationThreadStatus.AUTO_REJECTED).setTravelerArchivedAt(LocalDateTime.now());

        service.hideForUser(TRAVELER_ID, THREAD_ID);

        verify(threadRepo).updateTravelerHiddenAt(eq(THREAD_ID), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("supprimer deux fois est idempotent")
    void hide_twice_isIdempotent() {
        thread(NegotiationThreadStatus.EXPIRED).setSenderHiddenAt(LocalDateTime.now());

        service.hideForUser(SENDER_ID, THREAD_ID);

        verify(threadRepo, never()).updateSenderHiddenAt(any(), any());
        verifyNoInteractions(auditService);
    }

    // ─── Liste ──────────────────────────────────────────────────────────────────

    private UserEntity traveler() {
        UserEntity u = new UserEntity();
        u.setUsername("traveler");
        setId(u, TRAVELER_ID);
        return u;
    }

    @Test
    @DisplayName("archived=true lit le filtre « Archivées » et marque archived pour le demandeur")
    void listMine_archived_usesArchivedQuery() {
        NegotiationThreadEntity t = newThread(NegotiationThreadStatus.REJECTED);
        t.setSenderArchivedAt(LocalDateTime.now());
        when(threadRepo.findArchivedByParticipant(SENDER_ID)).thenReturn(List.of(t));
        when(announcementRepo.findAllById(any())).thenReturn(List.of());
        when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler()));
        lenient().when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

        List<NegotiationThreadResponse> rows = service.listMine(SENDER_ID, true);

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.archived()).isTrue());
        verify(threadRepo, never()).findVisibleByParticipant(any());
    }

    @Test
    @DisplayName("le rangement de l'expéditeur ne marque pas le fil du voyageur")
    void listMine_archivedFlag_isPerParticipant() {
        NegotiationThreadEntity t = newThread(NegotiationThreadStatus.REJECTED);
        t.setSenderArchivedAt(LocalDateTime.now());
        when(threadRepo.findVisibleByParticipant(TRAVELER_ID)).thenReturn(List.of(t));
        when(announcementRepo.findAllById(any())).thenReturn(List.of());
        when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler()));
        lenient().when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

        List<NegotiationThreadResponse> rows = service.listMine(TRAVELER_ID, false);

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.archived()).isFalse());
    }
}
