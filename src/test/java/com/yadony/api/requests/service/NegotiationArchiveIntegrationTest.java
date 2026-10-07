package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.dto.NegotiationThreadResponse;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bout en bout sur une vraie base (H2) et le vrai cache {@code negotiations-me} :
 * le rangement et le retrait ne changent que la vue de l'appelant, évincent sa liste
 * en cache, et ne suppriment jamais le fil.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Discussions de prix (demandes) — archiver / supprimer de bout en bout")
class NegotiationArchiveIntegrationTest {

    @Autowired private NegotiationService negotiationService;
    @Autowired private NegotiationThreadRepository threadRepository;
    @Autowired private PackageRequestRepository packageRequestRepository;
    @Autowired private UserRepository userRepository;

    private UserEntity sender;
    private UserEntity traveler;
    private PackageRequestEntity request;

    @BeforeEach
    void setUp() {
        threadRepository.deleteAll();
        packageRequestRepository.deleteAll();
        userRepository.deleteAll();
        sender = persistUser("uid-archive-sender-" + UUID.randomUUID());
        traveler = persistUser("uid-archive-traveler-" + UUID.randomUUID());
        request = persistPackageRequest(sender.getId());
    }

    private List<UUID> ids(List<NegotiationThreadResponse> rows) {
        return rows.stream().map(NegotiationThreadResponse::id).toList();
    }

    @Test
    @DisplayName("archiver range le fil pour l'expéditeur seul ; le voyageur le garde ; désarchiver le ramène")
    void archive_isPerParticipant_andEvictsCache() {
        NegotiationThreadEntity thread = persistThread(NegotiationThreadStatus.REJECTED);

        // Remplit le cache des deux côtés avant l'action.
        assertThat(ids(negotiationService.listMine(sender.getId(), false))).containsExactly(thread.getId());
        assertThat(ids(negotiationService.listMine(sender.getId(), true))).isEmpty();
        assertThat(ids(negotiationService.listMine(traveler.getId(), false))).containsExactly(thread.getId());

        negotiationService.archiveForUser(sender.getId(), thread.getId());

        assertThat(negotiationService.listMine(sender.getId(), false)).isEmpty();
        assertThat(negotiationService.listMine(sender.getId(), true))
                .singleElement().satisfies(r -> assertThat(r.archived()).isTrue());

        // L'autre partie : intact, et non archivé de son côté.
        List<NegotiationThreadResponse> travelerArchived = negotiationService.listMine(traveler.getId(), true);
        assertThat(travelerArchived).isEmpty();
        NegotiationThreadResponse forTraveler = negotiationService.getById(traveler.getId(), thread.getId());
        assertThat(forTraveler.archived()).isFalse();

        negotiationService.unarchiveForUser(sender.getId(), thread.getId());

        assertThat(negotiationService.listMine(sender.getId(), false))
                .singleElement().satisfies(r -> assertThat(r.archived()).isFalse());
        assertThat(negotiationService.listMine(sender.getId(), true)).isEmpty();
    }

    @Test
    @DisplayName("supprimer retire le fil des deux listes de l'appelant, jamais de la base ni de chez l'autre")
    void hide_removesFromCallerListsOnly_neverDeletes() {
        NegotiationThreadEntity thread = persistThread(NegotiationThreadStatus.EXPIRED);
        negotiationService.archiveForUser(traveler.getId(), thread.getId());

        negotiationService.hideForUser(traveler.getId(), thread.getId());

        assertThat(negotiationService.listMine(traveler.getId(), false)).isEmpty();
        assertThat(negotiationService.listMine(traveler.getId(), true)).isEmpty();
        assertThat(ids(negotiationService.listMine(sender.getId(), false))).containsExactly(thread.getId());

        NegotiationThreadEntity stored = threadRepository.findById(thread.getId()).orElseThrow();
        assertThat(stored.getDeletedAt()).isNull();
        assertThat(stored.getTravelerHiddenAt()).isNotNull();
        assertThat(stored.getSenderHiddenAt()).isNull();

        // Idempotent, et le fil retiré ne se range plus.
        negotiationService.hideForUser(traveler.getId(), thread.getId());
        assertThatThrownBy(() -> negotiationService.archiveForUser(traveler.getId(), thread.getId()))
                .hasMessageContaining("thread/not-found");
    }

    @Test
    @DisplayName("un fil en cours ne se range ni ne se retire (409)")
    void openThread_isRefused() {
        NegotiationThreadEntity thread = persistThread(NegotiationThreadStatus.OPEN);

        assertThatThrownBy(() -> negotiationService.archiveForUser(sender.getId(), thread.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("negotiation-still-open");
        assertThatThrownBy(() -> negotiationService.hideForUser(traveler.getId(), thread.getId()))
                .isInstanceOf(YadonyBusinessException.class);

        NegotiationThreadEntity stored = threadRepository.findById(thread.getId()).orElseThrow();
        assertThat(stored.getSenderArchivedAt()).isNull();
        assertThat(stored.getTravelerHiddenAt()).isNull();
    }

    @Test
    @DisplayName("un save() d'un fil chargé avant l'archivage n'écrase pas le rangement")
    void staleSave_doesNotClobberArchive() {
        NegotiationThreadEntity thread = persistThread(NegotiationThreadStatus.CANCELLED);
        NegotiationThreadEntity stale = threadRepository.findById(thread.getId()).orElseThrow();

        negotiationService.archiveForUser(sender.getId(), thread.getId());

        // Flux concurrent (ex. remboursement de commission) qui sauvegarde sa copie périmée.
        stale.setCommissionStatus("REFUNDED");
        threadRepository.save(stale);

        NegotiationThreadEntity reloaded = threadRepository.findById(thread.getId()).orElseThrow();
        assertThat(reloaded.getCommissionStatus()).isEqualTo("REFUNDED");
        assertThat(reloaded.getSenderArchivedAt()).isNotNull();
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────

    private UserEntity persistUser(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private PackageRequestEntity persistPackageRequest(UUID senderId) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(10));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5.00"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setNegotiable(true);
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        e.setStatus(PackageRequestStatus.OPEN);
        return packageRequestRepository.save(e);
    }

    private NegotiationThreadEntity persistThread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(request.getId());
        t.setTravelerId(traveler.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("10.00"));
        t.setStatus(status);
        t.setCurrentPriceEur(new BigDecimal("35.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return threadRepository.save(t);
    }
}
