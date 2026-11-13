package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task B1 — unit tests pour {@link RematchService#generateForCancellations}.
 * Le filtrage dur (statut/corridor/date/kg/public/blocked) est fait en SQL via
 * {@link Specification} et n'est donc PAS exercé ici (voir {@code RematchSpecificationDbTest}
 * pour ça) : {@code announcementRepository.findAll(any(Specification.class))} est mocké pour
 * retourner, PAR APPEL (dans l'ordre des cancellations traitées), ce que le SQL réel
 * renverrait pour ce bid — ça permet de vérifier ici uniquement la logique d'agrégation
 * par cancellation, le tri, la limite et le statut rematch.
 */
@ExtendWith(MockitoExtension.class)
class RematchServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RematchService rematchService;

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

    private AnnouncementEntity buildAnnouncement(UUID travelerId, LocalDate departureDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, UUID.randomUUID());
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureDate);
        a.setAvailableKg(BigDecimal.TEN);
        a.setTotalKg(BigDecimal.TEN);
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    private AnnouncementEntity cancelledAnnouncement() {
        AnnouncementEntity a = buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(5));
        a.setStatus(AnnouncementStatus.CANCELLED);
        return a;
    }

    private BidEntity buildBid(UUID senderId, BigDecimal weightKg) {
        BidEntity b = new BidEntity();
        setId(b, UUID.randomUUID());
        b.setSenderId(senderId);
        b.setWeightKg(weightKg);
        return b;
    }

    private CancellationEntity buildCancellation() {
        CancellationEntity c = new CancellationEntity();
        setId(c, UUID.randomUUID());
        return c;
    }

    @Test
    @DisplayName("2 cancellations, alternative valide pour la 1ère seulement → chaque cancellation reçoit ses propres suggestions")
    void multiSenders_eachCancellationGetsOwnSuggestions() {
        AnnouncementEntity cancelled = cancelledAnnouncement();

        UUID sender1 = UUID.randomUUID();
        UUID sender2 = UUID.randomUUID();
        BidEntity bid1 = buildBid(sender1, BigDecimal.valueOf(5));
        BidEntity bid2 = buildBid(sender2, BigDecimal.valueOf(20));

        CancellationEntity cancellation1 = buildCancellation();
        CancellationEntity cancellation2 = buildCancellation();

        AnnouncementEntity alt = buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1));

        // Simule le résultat SQL réel : le 1er appel (bid 5kg, availableKg alt=10) inclut
        // l'alternative ; le 2nd (bid 20kg) l'exclurait (capacité insuffisante) → liste vide.
        when(announcementRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(alt))
                .thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        Map<UUID, RematchService.RematchInfo> result = rematchService.generateForCancellations(
                cancelled, List.of(bid1, bid2), List.of(cancellation1, cancellation2));

        assertThat(result).hasSize(2);
        assertThat(result.get(sender1)).isEqualTo(new RematchService.RematchInfo(cancellation1.getId(), 1));
        assertThat(result.get(sender2)).isEqualTo(new RematchService.RematchInfo(cancellation2.getId(), 0));

        verify(rematchSuggestionRepository, times(1)).save(any(RematchSuggestionEntity.class));
        ArgumentCaptor<RematchSuggestionEntity> captor = ArgumentCaptor.forClass(RematchSuggestionEntity.class);
        verify(rematchSuggestionRepository).save(captor.capture());
        assertThat(captor.getValue().getCancellationId()).isEqualTo(cancellation1.getId());
        assertThat(captor.getValue().getAnnouncementId()).isEqualTo(alt.getId());
    }

    private static AnnouncementEntity asPerM3(AnnouncementEntity a, String availableVolumeM3) {
        a.setTransportMode(com.yadony.api.matching.TransportMode.BOAT);
        a.setAvailableKg(null);
        a.setTotalKg(null);
        a.setPricePerKg(null);
        a.setPricePerM3(BigDecimal.valueOf(600));
        a.setVolumeCapacityM3(BigDecimal.valueOf(3));
        a.setAvailableVolumeM3(new BigDecimal(availableVolumeM3));
        return a;
    }

    @Test
    @DisplayName("trajet bateau au m³ : jamais proposé à un colis sans volume (au kilo ou à la grille)")
    void perM3Trip_notSuggestedToParcelWithoutVolume() {
        AnnouncementEntity cancelled = cancelledAnnouncement();
        BidEntity bid = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        CancellationEntity cancellation = buildCancellation();
        AnnouncementEntity perKg = buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1));
        AnnouncementEntity perM3 = asPerM3(buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(2)), "3");

        when(announcementRepository.findAll(any(Specification.class))).thenReturn(List.of(perKg, perM3));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        Map<UUID, RematchService.RematchInfo> result = rematchService.generateForCancellations(
                cancelled, List.of(bid), List.of(cancellation));

        assertThat(result.get(bid.getSenderId()).suggestionCount()).isEqualTo(1);
        verify(rematchSuggestionRepository, times(1)).save(any(RematchSuggestionEntity.class));
    }

    @Test
    @DisplayName("colis au m³ : un trajet au m³ dont le volume restant suffit est proposé, pas celui qui est trop plein")
    void perM3Parcel_suggestedOnlyWhereVolumeFits() {
        AnnouncementEntity cancelled = cancelledAnnouncement();
        BidEntity bid = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        bid.setVolumeM3(new BigDecimal("0.500"));
        CancellationEntity cancellation = buildCancellation();
        AnnouncementEntity fits = asPerM3(buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1)), "0.5");
        AnnouncementEntity tooFull = asPerM3(buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(2)), "0.499");

        when(announcementRepository.findAll(any(Specification.class))).thenReturn(List.of(fits, tooFull));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        Map<UUID, RematchService.RematchInfo> result = rematchService.generateForCancellations(
                cancelled, List.of(bid), List.of(cancellation));

        assertThat(result.get(bid.getSenderId()).suggestionCount()).isEqualTo(1);
        ArgumentCaptor<RematchSuggestionEntity> captor = ArgumentCaptor.forClass(RematchSuggestionEntity.class);
        verify(rematchSuggestionRepository).save(captor.capture());
        assertThat(captor.getValue().getAnnouncementId()).isEqualTo(fits.getId());
    }

    @Test
    @DisplayName("tri par date de départ croissante puis note voyageur décroissante")
    void sortsByDateThenRating() {
        AnnouncementEntity cancelled = cancelledAnnouncement();
        BidEntity bid = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        CancellationEntity cancellation = buildCancellation();

        UUID traveler1 = UUID.randomUUID(); // J+1, note 3.0
        UUID traveler2 = UUID.randomUUID(); // J+1, note 4.8
        UUID traveler3 = UUID.randomUUID(); // J+2, note 5.0

        AnnouncementEntity altJ1Note3 = buildAnnouncement(traveler1, LocalDate.now().plusDays(1));
        AnnouncementEntity altJ1Note48 = buildAnnouncement(traveler2, LocalDate.now().plusDays(1));
        AnnouncementEntity altJ2Note5 = buildAnnouncement(traveler3, LocalDate.now().plusDays(2));

        when(announcementRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(altJ1Note3, altJ1Note48, altJ2Note5));

        UserEntity u1 = new UserEntity();
        setId(u1, traveler1);
        u1.setAverageRating(BigDecimal.valueOf(3.0));
        UserEntity u2 = new UserEntity();
        setId(u2, traveler2);
        u2.setAverageRating(BigDecimal.valueOf(4.8));
        UserEntity u3 = new UserEntity();
        setId(u3, traveler3);
        u3.setAverageRating(BigDecimal.valueOf(5.0));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(u1, u2, u3));

        rematchService.generateForCancellations(cancelled, List.of(bid), List.of(cancellation));

        ArgumentCaptor<RematchSuggestionEntity> captor = ArgumentCaptor.forClass(RematchSuggestionEntity.class);
        verify(rematchSuggestionRepository, times(3)).save(captor.capture());

        List<UUID> savedOrder = captor.getAllValues().stream()
                .map(RematchSuggestionEntity::getAnnouncementId)
                .toList();

        assertThat(savedOrder).containsExactly(
                altJ1Note48.getId(), altJ1Note3.getId(), altJ2Note5.getId());
    }

    @Test
    @DisplayName("7 alternatives valides → limite à 5 suggestions persistées")
    void limitsToFive() {
        AnnouncementEntity cancelled = cancelledAnnouncement();
        BidEntity bid = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        CancellationEntity cancellation = buildCancellation();

        List<AnnouncementEntity> alternatives = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1 + i)))
                .toList();

        when(announcementRepository.findAll(any(Specification.class))).thenReturn(alternatives);
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        Map<UUID, RematchService.RematchInfo> result = rematchService.generateForCancellations(
                cancelled, List.of(bid), List.of(cancellation));

        assertThat(result.get(bid.getSenderId()).suggestionCount()).isEqualTo(5);
        verify(rematchSuggestionRepository, times(5)).save(any(RematchSuggestionEntity.class));
    }

    @Test
    @DisplayName("rematchStatus passe à SUGGESTED si ≥1 alternative, reste NONE sinon")
    void setsRematchStatusSuggested() {
        AnnouncementEntity cancelled = cancelledAnnouncement();

        BidEntity bidWithAlt = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        BidEntity bidWithoutAlt = buildBid(UUID.randomUUID(), BigDecimal.valueOf(5));
        CancellationEntity cancellationWithAlt = buildCancellation();
        CancellationEntity cancellationWithoutAlt = buildCancellation();

        AnnouncementEntity alt = buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1));

        when(announcementRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(alt))
                .thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        rematchService.generateForCancellations(cancelled,
                List.of(bidWithAlt, bidWithoutAlt),
                List.of(cancellationWithAlt, cancellationWithoutAlt));

        assertThat(cancellationWithAlt.getRematchStatus()).isEqualTo("SUGGESTED");
        assertThat(cancellationWithoutAlt.getRematchStatus()).isEqualTo("NONE");

        verify(cancellationRepository, times(1)).save(cancellationWithAlt);
        verify(cancellationRepository, never()).save(cancellationWithoutAlt);
    }

    @Test
    @DisplayName("bid GRID (weightKg null) annulé → pas d'exception, suggestions générées sans filtre de poids")
    void gridBidWithNullWeightKg_generatesSuggestionsWithoutThrowing() {
        AnnouncementEntity cancelled = cancelledAnnouncement();
        // Bid en mode GRID : weightKg == null (cf. BidService — "peut être null pour GRID mode").
        BidEntity gridBid = buildBid(UUID.randomUUID(), null);
        CancellationEntity cancellation = buildCancellation();

        AnnouncementEntity alt = buildAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(1));

        when(announcementRepository.findAll(any(Specification.class))).thenReturn(List.of(alt));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        Map<UUID, RematchService.RematchInfo> result = assertDoesNotThrow(() ->
                rematchService.generateForCancellations(cancelled, List.of(gridBid), List.of(cancellation)));

        assertThat(result.get(gridBid.getSenderId()).suggestionCount()).isEqualTo(1);
        verify(rematchSuggestionRepository, times(1)).save(any(RematchSuggestionEntity.class));
        assertThat(cancellation.getRematchStatus()).isEqualTo("SUGGESTED");
    }

    @Test
    @DisplayName("affectedBids vide → map vide, aucune interaction avec les repositories")
    void emptyBids_returnsEmptyMap() {
        AnnouncementEntity cancelled = cancelledAnnouncement();

        Map<UUID, RematchService.RematchInfo> result = rematchService.generateForCancellations(
                cancelled, List.of(), List.of());

        assertThat(result).isEmpty();
        verify(announcementRepository, never()).findAll(any(Specification.class));
        verify(rematchSuggestionRepository, never()).save(any());
        verify(cancellationRepository, never()).save(any());
    }
}
