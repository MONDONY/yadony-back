package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Confidentialité v2 — lectures de colis face au masquage mutuel.
 *
 * <p>Deux points d'entrée sont couverts : le détail d'un colis (la contrepartie masquée le
 * rend introuvable) et la liste des demandes reçues sur un trajet (les expéditeurs masqués
 * en disparaissent). Dans les deux cas le masquage est silencieux : jamais de 403, jamais
 * de trace du filtrage.
 *
 * <p>Aucun mock de {@code BlockService} n'est déclaré ici volontairement : seul le contrat
 * {@link BlockVisibility} est mocké, et il est le seul candidat pour le paramètre du même
 * type dans le constructeur de {@link BidService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidService — masquage des comptes bloqués")
class BidBlockVisibilityTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RatingRepository ratingRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private BlockVisibility blockVisibility;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private StorageService storageService;
    @Mock private BidPhotoService bidPhotoService;

    @InjectMocks private BidService bidService;

    private UserEntity traveler;
    private UserEntity sender;
    private UserEntity otherSender;
    private AnnouncementEntity announcement;
    private BidEntity bid;
    private BidEntity otherBid;

    @BeforeEach
    void setUp() {
        traveler = user("uid-traveler");
        sender = user("uid-sender");
        otherSender = user("uid-other-sender");

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(new BigDecimal("10"));
        announcement.setTotalKg(new BigDecimal("10"));
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");

        bid = bid(sender);
        otherBid = bid(otherSender);

        lenient().when(cancellationRepository.findByBidId(any())).thenReturn(Optional.empty());
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bidPhotoService.activePhotos(any())).thenReturn(List.of());
    }

    private UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private BidEntity bid(UserEntity from) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setAnnouncementId(announcement.getId());
        b.setSenderId(from.getId());
        b.setStatus(BidStatus.PENDING);
        b.setWeightKg(new BigDecimal("1"));
        return b;
    }

    // ─── getBidById ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expéditeur appelant, voyageur masqué → 404 identique à un colis inexistant")
    void getBidById_hiddenTraveler_returnsNotFound() {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(blockVisibility.isHidden(sender.getId(), traveler.getId())).thenReturn(true);

        assertThatThrownBy(() -> bidService.getBidById(bid.getId(), "uid-sender"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    // 404 et non 403 : un code distinct trahirait le blocage.
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("bid-not-found");
                });
    }

    @Test
    @DisplayName("voyageur appelant, expéditeur masqué → 404")
    void getBidById_hiddenSender_returnsNotFound() {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(blockVisibility.isHidden(traveler.getId(), sender.getId())).thenReturn(true);

        assertThatThrownBy(() -> bidService.getBidById(bid.getId(), "uid-traveler"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * Relecture juste après une annulation faite par l'appelant lui-même : le colis
     * vient de sortir des statuts « transaction en cours », donc la garde le
     * masquerait et renverrait un 404 pour une opération pourtant réussie.
     */
    @Test
    @DisplayName("relecture après sa propre mutation → le colis est rendu malgré le blocage")
    void getBidAfterOwnMutation_hiddenCounterparty_returnsBidAnyway() {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));

        assertThat(bidService.getBidAfterOwnMutation(bid.getId(), "uid-sender")).isNotNull();
        verifyNoInteractions(blockVisibility);
    }

    @Test
    @DisplayName("aucun blocage → le colis est retourné")
    void getBidById_noBlock_returnsBid() {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(blockVisibility.isHidden(sender.getId(), traveler.getId())).thenReturn(false);

        var result = bidService.getBidById(bid.getId(), "uid-sender");

        assertThat(result.id()).isEqualTo(bid.getId());
        assertThat(result.status()).isEqualTo("PENDING");
    }

    // ─── getBidsForAnnouncement ────────────────────────────────────────────────

    @Test
    @DisplayName("liste des demandes → les colis des expéditeurs masqués disparaissent")
    void getBidsForAnnouncement_hiddenSenderIsFilteredOut() {
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(bidRepository.findByAnnouncementId(announcement.getId()))
                .thenReturn(List.of(bid, otherBid));
        when(blockVisibility.hiddenUserIdsFor(traveler.getId()))
                .thenReturn(Set.of(sender.getId()));
        lenient().when(userRepository.findById(any())).thenAnswer(inv ->
                inv.getArgument(0).equals(sender.getId()) ? Optional.of(sender) : Optional.of(otherSender));

        var result = bidService.getBidsForAnnouncement(announcement.getId(), "uid-traveler");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(otherBid.getId());
    }

    @Test
    @DisplayName("liste des demandes → aucun blocage, tous les colis restent visibles")
    void getBidsForAnnouncement_noBlock_keepsEveryBid() {
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(bidRepository.findByAnnouncementId(announcement.getId()))
                .thenReturn(List.of(bid, otherBid));
        when(blockVisibility.hiddenUserIdsFor(traveler.getId())).thenReturn(Set.of());
        lenient().when(userRepository.findById(any())).thenAnswer(inv ->
                inv.getArgument(0).equals(sender.getId()) ? Optional.of(sender) : Optional.of(otherSender));

        var result = bidService.getBidsForAnnouncement(announcement.getId(), "uid-traveler");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(b -> b.id())
                .containsExactlyInAnyOrder(bid.getId(), otherBid.getId());
    }
}
