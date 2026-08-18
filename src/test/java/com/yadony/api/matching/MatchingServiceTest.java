package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private PackageRequestRepository packageRequestRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private MatchingService matchingService;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private AnnouncementEntity activeAnnouncement;
    private UserEntity sender;

    @BeforeEach
    void setup() throws Exception {
        activeAnnouncement = new AnnouncementEntity();
        setField(activeAnnouncement, "id", ANNOUNCEMENT_ID);
        activeAnnouncement.setTravelerId(TRAVELER_ID);
        activeAnnouncement.setDepartureCity("Paris");
        activeAnnouncement.setArrivalCity("Dakar");
        activeAnnouncement.setDepartureDate(LocalDate.now().plusDays(10));
        activeAnnouncement.setAvailableKg(BigDecimal.valueOf(20));
        activeAnnouncement.setPricePerKg(BigDecimal.valueOf(5));
        activeAnnouncement.setStatus(AnnouncementStatus.ACTIVE);

        sender = new UserEntity();
        setField(sender, "id", SENDER_ID);
        sender.setFirstName("Marie");
        sender.setLastName("Dupont");
        setField(sender, "averageRating", BigDecimal.valueOf(4.5));
        setField(sender, "totalShipments", 8);
    }

    @Nested
    @DisplayName("findTravelersMatchingPackage")
    class FindTravelersMatchingPackage {

        @Test
        void returnsTravelerId_whenCorridorWeightDateMatch() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepository.findActiveByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(activeAnnouncement));

            List<UUID> travelers = matchingService.findTravelersMatchingPackage(REQUEST_ID);

            assertThat(travelers).containsExactly(TRAVELER_ID);
        }

        @Test
        void returnsEmpty_whenRequestNotFound() {
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

            assertThat(matchingService.findTravelersMatchingPackage(REQUEST_ID)).isEmpty();
        }

        @Test
        void returnsEmpty_whenRequestNotOpen() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            setField(request, "status", PackageRequestStatus.NEGOTIATING);
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThat(matchingService.findTravelersMatchingPackage(REQUEST_ID)).isEmpty();
        }

        @Test
        void excludesTrip_whenWeightExceedsAvailableKg() throws Exception {
            PackageRequestEntity request = buildRequest(25, LocalDate.now().plusDays(10), 3);
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepository.findActiveByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(activeAnnouncement));

            assertThat(matchingService.findTravelersMatchingPackage(REQUEST_ID)).isEmpty();
        }

        @Test
        void excludesTrip_whenDateOutsideTolerance() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(30), 3);
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepository.findActiveByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(activeAnnouncement));

            assertThat(matchingService.findTravelersMatchingPackage(REQUEST_ID)).isEmpty();
        }

        @Test
        void deduplicatesTraveler_whenMultipleTripsMatch() throws Exception {
            AnnouncementEntity secondTrip = new AnnouncementEntity();
            setField(secondTrip, "id", UUID.randomUUID());
            secondTrip.setTravelerId(TRAVELER_ID); // même voyageur, 2e trajet
            secondTrip.setDepartureCity("Paris");
            secondTrip.setArrivalCity("Dakar");
            secondTrip.setDepartureDate(LocalDate.now().plusDays(11));
            secondTrip.setAvailableKg(BigDecimal.valueOf(20));
            secondTrip.setPricePerKg(BigDecimal.valueOf(5));
            secondTrip.setStatus(AnnouncementStatus.FULL);

            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            when(packageRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepository.findActiveByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(activeAnnouncement, secondTrip));

            assertThat(matchingService.findTravelersMatchingPackage(REQUEST_ID))
                    .containsExactly(TRAVELER_ID);
        }
    }

    @Nested
    @DisplayName("findBestMatchByRequestId")
    class FindBestMatchByRequestId {

        @Test
        void findBestMatchByRequestId_dedupliqueUneDemandeCompatibleAvecDeuxTrajets() throws Exception {
            UUID travelerId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();

            // Deux trajets du même voyageur sur le même corridor, à des dates proches.
            AnnouncementEntity trajetFaible = new AnnouncementEntity();
            setField(trajetFaible, "id", UUID.randomUUID());
            trajetFaible.setTravelerId(travelerId);
            trajetFaible.setDepartureCity("Paris");
            trajetFaible.setArrivalCity("Dakar");
            trajetFaible.setDepartureDate(LocalDate.of(2026, 8, 20));
            trajetFaible.setAvailableKg(new BigDecimal("8"));
            trajetFaible.setPricePerKg(new BigDecimal("20"));

            AnnouncementEntity trajetFort = new AnnouncementEntity();
            setField(trajetFort, "id", UUID.randomUUID());
            trajetFort.setTravelerId(travelerId);
            trajetFort.setDepartureCity("Paris");
            trajetFort.setArrivalCity("Dakar");
            trajetFort.setDepartureDate(LocalDate.of(2026, 8, 10));
            trajetFort.setAvailableKg(new BigDecimal("30"));
            trajetFort.setPricePerKg(new BigDecimal("5"));

            PackageRequestEntity demande = new PackageRequestEntity();
            setField(demande, "id", requestId);
            demande.setSenderId(UUID.randomUUID());
            demande.setStatus(PackageRequestStatus.OPEN);
            demande.setDepartureCity("Paris");
            demande.setArrivalCity("Dakar");
            demande.setDesiredDate(LocalDate.of(2026, 8, 10));
            demande.setDateToleranceDays((short) 15);
            demande.setWeightKg(new BigDecimal("2"));
            demande.setTargetPriceEur(new BigDecimal("40"));
            setField(demande, "createdAt", LocalDateTime.of(2026, 7, 1, 10, 0));

            UserEntity expediteur = new UserEntity();
            setField(expediteur, "id", demande.getSenderId());

            when(announcementRepository.findActiveByTravelerId(travelerId))
                    .thenReturn(List.of(trajetFaible, trajetFort));
            when(packageRequestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(demande));
            when(userRepository.findAllById(any())).thenReturn(List.of(expediteur));

            Map<UUID, MatchingService.MatchInfo> result = matchingService.findBestMatchByRequestId(travelerId);

            assertThat(result).hasSize(1);
            MatchingService.MatchInfo info = result.get(requestId);
            assertThat(info.tripId()).isEqualTo(trajetFort.getId());
            assertThat(info.tripDepartureDate()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(info.matchScore()).isGreaterThan(0);

            // Les expéditeurs sont chargés en un seul appel, jamais un findById par
            // candidat : cette méthode est rappelée à chaque page de recherche.
            verify(userRepository, times(1)).findAllById(any());
            verify(userRepository, never()).findById(any());
        }

        @Test
        void findBestMatchByRequestId_ordonneParScoreDecroissant() throws Exception {
            UUID travelerId = UUID.randomUUID();

            AnnouncementEntity trajet = new AnnouncementEntity();
            setField(trajet, "id", UUID.randomUUID());
            trajet.setTravelerId(travelerId);
            trajet.setDepartureCity("Paris");
            trajet.setArrivalCity("Dakar");
            trajet.setDepartureDate(LocalDate.of(2026, 8, 10));
            trajet.setAvailableKg(new BigDecimal("30"));
            trajet.setPricePerKg(new BigDecimal("10"));

            // Budget généreux + colis léger + date exacte → score élevé.
            PackageRequestEntity forte = new PackageRequestEntity();
            setField(forte, "id", UUID.randomUUID());
            forte.setSenderId(UUID.randomUUID());
            forte.setStatus(PackageRequestStatus.OPEN);
            forte.setDepartureCity("Paris");
            forte.setArrivalCity("Dakar");
            forte.setDesiredDate(LocalDate.of(2026, 8, 10));
            forte.setDateToleranceDays((short) 5);
            forte.setWeightKg(new BigDecimal("1"));
            forte.setTargetPriceEur(new BigDecimal("50"));
            setField(forte, "createdAt", LocalDateTime.of(2026, 7, 1, 10, 0));

            // Budget serré + colis lourd → score bas.
            PackageRequestEntity faible = new PackageRequestEntity();
            setField(faible, "id", UUID.randomUUID());
            faible.setSenderId(UUID.randomUUID());
            faible.setStatus(PackageRequestStatus.OPEN);
            faible.setDepartureCity("Paris");
            faible.setArrivalCity("Dakar");
            faible.setDesiredDate(LocalDate.of(2026, 8, 10));
            faible.setDateToleranceDays((short) 5);
            faible.setWeightKg(new BigDecimal("28"));
            faible.setTargetPriceEur(new BigDecimal("30"));
            setField(faible, "createdAt", LocalDateTime.of(2026, 7, 1, 10, 0));

            UserEntity u1 = new UserEntity();
            setField(u1, "id", forte.getSenderId());
            UserEntity u2 = new UserEntity();
            setField(u2, "id", faible.getSenderId());

            when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of(trajet));
            when(packageRequestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(faible, forte));
            when(userRepository.findAllById(any())).thenReturn(List.of(u1, u2));

            Map<UUID, MatchingService.MatchInfo> result = matchingService.findBestMatchByRequestId(travelerId);

            assertThat(result.keySet()).containsExactly(forte.getId(), faible.getId());
        }

        @Test
        void findBestMatchByRequestId_aucunTrajetActif_retourneMapVide() {
            UUID travelerId = UUID.randomUUID();
            when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of());

            assertThat(matchingService.findBestMatchByRequestId(travelerId)).isEmpty();
        }

        @Test
        void findBestMatchByRequestId_trajetsActifsMaisAucuneDemandeCompatible_retourneMapVide()
                throws Exception {
            // Le voyageur a bien un trajet actif, mais la seule demande du corridor
            // pèse plus que la capacité disponible → aucun match.
            UUID travelerId = UUID.randomUUID();

            AnnouncementEntity trajet = new AnnouncementEntity();
            setField(trajet, "id", UUID.randomUUID());
            trajet.setTravelerId(travelerId);
            trajet.setDepartureCity("Paris");
            trajet.setArrivalCity("Dakar");
            trajet.setDepartureDate(LocalDate.of(2026, 8, 10));
            trajet.setAvailableKg(new BigDecimal("5"));
            trajet.setPricePerKg(new BigDecimal("10"));

            PackageRequestEntity tropLourde = new PackageRequestEntity();
            setField(tropLourde, "id", UUID.randomUUID());
            tropLourde.setSenderId(UUID.randomUUID());
            tropLourde.setStatus(PackageRequestStatus.OPEN);
            tropLourde.setDepartureCity("Paris");
            tropLourde.setArrivalCity("Dakar");
            tropLourde.setDesiredDate(LocalDate.of(2026, 8, 10));
            tropLourde.setDateToleranceDays((short) 5);
            tropLourde.setWeightKg(new BigDecimal("30"));
            tropLourde.setTargetPriceEur(new BigDecimal("50"));

            when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of(trajet));
            when(packageRequestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(tropLourde));

            assertThat(matchingService.findBestMatchByRequestId(travelerId)).isEmpty();
            // Aucun candidat retenu → aucun chargement d'expéditeur.
            verify(userRepository, never()).findAllById(any());
        }

        @Test
        void findBestMatchByRequestId_expediteurIntrouvable_exclutLaDemande() throws Exception {
            UUID travelerId = UUID.randomUUID();

            AnnouncementEntity trajet = new AnnouncementEntity();
            setField(trajet, "id", UUID.randomUUID());
            trajet.setTravelerId(travelerId);
            trajet.setDepartureCity("Paris");
            trajet.setArrivalCity("Dakar");
            trajet.setDepartureDate(LocalDate.of(2026, 8, 10));
            trajet.setAvailableKg(new BigDecimal("30"));
            trajet.setPricePerKg(new BigDecimal("10"));

            PackageRequestEntity orpheline = new PackageRequestEntity();
            setField(orpheline, "id", UUID.randomUUID());
            orpheline.setSenderId(UUID.randomUUID());
            orpheline.setStatus(PackageRequestStatus.OPEN);
            orpheline.setDepartureCity("Paris");
            orpheline.setArrivalCity("Dakar");
            orpheline.setDesiredDate(LocalDate.of(2026, 8, 10));
            orpheline.setDateToleranceDays((short) 5);
            orpheline.setWeightKg(new BigDecimal("2"));
            orpheline.setTargetPriceEur(new BigDecimal("50"));

            when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of(trajet));
            when(packageRequestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(orpheline));
            // Le chargement groupé ne ramène pas l'expéditeur → demande exclue,
            // sémantique identique à l'ancien findById().isEmpty().
            when(userRepository.findAllById(any())).thenReturn(List.of());

            assertThat(matchingService.findBestMatchByRequestId(travelerId)).isEmpty();
        }

        @Test
        void findBestMatchByRequestId_conserveLesDemandesEnNegociation() throws Exception {
            // Le repository dédié renvoie OPEN + NEGOTIATING ; le service ne doit pas
            // re-filtrer sur le statut, sinon un voyageur qui négocie perdrait la
            // demande de sa propre liste filtrée.
            UUID travelerId = UUID.randomUUID();

            AnnouncementEntity trajet = new AnnouncementEntity();
            setField(trajet, "id", UUID.randomUUID());
            trajet.setTravelerId(travelerId);
            trajet.setDepartureCity("Paris");
            trajet.setArrivalCity("Dakar");
            trajet.setDepartureDate(LocalDate.of(2026, 8, 10));
            trajet.setAvailableKg(new BigDecimal("30"));
            trajet.setPricePerKg(new BigDecimal("10"));

            PackageRequestEntity enNegociation = new PackageRequestEntity();
            setField(enNegociation, "id", UUID.randomUUID());
            enNegociation.setSenderId(UUID.randomUUID());
            enNegociation.setStatus(PackageRequestStatus.NEGOTIATING);
            enNegociation.setDepartureCity("Paris");
            enNegociation.setArrivalCity("Dakar");
            enNegociation.setDesiredDate(LocalDate.of(2026, 8, 10));
            enNegociation.setDateToleranceDays((short) 5);
            enNegociation.setWeightKg(new BigDecimal("2"));
            enNegociation.setTargetPriceEur(new BigDecimal("50"));

            UserEntity expediteur = new UserEntity();
            setField(expediteur, "id", enNegociation.getSenderId());

            when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of(trajet));
            when(packageRequestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(enNegociation));
            when(userRepository.findAllById(any())).thenReturn(List.of(expediteur));

            assertThat(matchingService.findBestMatchByRequestId(travelerId))
                    .containsOnlyKeys(enNegociation.getId());
        }
    }

    // ---- Helpers ----

    private PackageRequestEntity buildRequest(int weightKg, LocalDate desiredDate, int toleranceDays)
            throws Exception {
        PackageRequestEntity req = new PackageRequestEntity();
        setField(req, "id", REQUEST_ID);
        req.setSenderId(SENDER_ID);
        req.setDepartureCity("Paris");
        req.setArrivalCity("Dakar");
        req.setDesiredDate(desiredDate);
        req.setDateToleranceDays((short) toleranceDays);
        req.setWeightKg(BigDecimal.valueOf(weightKg));
        req.setContentCategory("Vêtements");
        req.setDescription("Colis de vêtements pour la famille");
        req.setTargetPriceEur(BigDecimal.valueOf(30));
        req.setStatus(PackageRequestStatus.OPEN);
        setField(req, "createdAt", LocalDateTime.now().minusDays(1));
        return req;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found on " + target.getClass());
    }
}
