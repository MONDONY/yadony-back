package com.yadony.api.listings;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.listings.dto.UserListingItem;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementSearchMapper;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestSearchMapper;
import com.yadony.api.requests.service.ViewerPaymentCapabilities;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Annonces ouvertes d'un utilisateur, pour la section « En cours » de son profil.
 *
 * <p>Mélange ses trajets (réservables ou complets) et ses demandes d'envoi ouvertes dans une
 * seule liste, triée par date de publication décroissante. Les mappers sont ceux de la
 * recherche, comme {@code FavoriteService} : l'app réutilise ses cartes sans rien adapter.
 */
@Service
public class UserListingsService {

    /** Plafond par type : un profil n'a pas de pagination, et 20 + 20 tient largement l'écran. */
    static final int MAX_PER_KIND = 20;

    private static final Set<AnnouncementStatus> OPEN_TRIP_STATUSES =
            Set.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL);
    private static final Set<PackageRequestStatus> OPEN_REQUEST_STATUSES =
            Set.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING);

    private final AnnouncementRepository announcementRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final AnnouncementSearchMapper announcementSearchMapper;
    private final PackageRequestSearchMapper packageRequestSearchMapper;
    private final UserRepository userRepository;
    private final BlockVisibility blockVisibility;

    public UserListingsService(AnnouncementRepository announcementRepository,
                               PackageRequestRepository packageRequestRepository,
                               AnnouncementSearchMapper announcementSearchMapper,
                               PackageRequestSearchMapper packageRequestSearchMapper,
                               UserRepository userRepository,
                               BlockVisibility blockVisibility) {
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.announcementSearchMapper = announcementSearchMapper;
        this.packageRequestSearchMapper = packageRequestSearchMapper;
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
    }

    /**
     * @param ownerId  l'utilisateur dont on lit le profil
     * @param viewerId l'appelant, {@code null} s'il n'est pas identifiable
     * @throws com.yadony.api.common.YadonyBusinessException (404) si l'un a bloqué l'autre
     */
    @Transactional(readOnly = true)
    public List<UserListingItem> openListings(UUID ownerId, UUID viewerId) {
        blockVisibility.assertVisible(viewerId, ownerId);

        List<UserListingItem> items = new ArrayList<>();
        items.addAll(openTrips(ownerId));
        items.addAll(openRequests(ownerId, viewerId));
        items.sort(Comparator.comparing(UserListingItem::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private List<UserListingItem> openTrips(UUID ownerId) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var pageable = PageRequest.of(0, MAX_PER_KIND, Sort.by("createdAt").descending());
        List<AnnouncementEntity> trips = new ArrayList<>();
        for (AnnouncementStatus status : OPEN_TRIP_STATUSES) {
            trips.addAll(announcementRepository.findByTravelerIdAndStatus(ownerId, status, pageable).getContent());
        }
        // Mêmes garde-fous que la recherche : un trajet déjà parti ou dont la date limite de
        // dépôt est passée n'accepte plus de colis, il ne doit pas figurer parmi les « en cours ».
        List<AnnouncementEntity> presentable = trips.stream()
                .filter(a -> !a.getDepartureDate().isBefore(today) && !a.isHandoverDeadlinePassed(now))
                .toList();
        return announcementSearchMapper.toSearchResponseList(presentable, Set.of()).stream()
                .map(r -> new UserListingItem(UserListingItem.KIND_TRIP, r.createdAt(), r, null))
                .toList();
    }

    private List<UserListingItem> openRequests(UUID ownerId, UUID viewerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<PackageRequestEntity> requests = packageRequestRepository
                .findOpenBySenderId(ownerId, OPEN_REQUEST_STATUSES, PageRequest.of(0, MAX_PER_KIND))
                .stream()
                // « Date flexible » : pas de date, jamais périmée par ce filtre.
                .filter(r -> r.getDesiredDate() == null || !r.getDesiredDate().isBefore(today))
                .toList();
        if (requests.isEmpty()) return List.of();
        ViewerPaymentCapabilities viewer = ViewerPaymentCapabilities.of(
                viewerId == null ? null : userRepository.findById(viewerId).orElse(null));
        var responses = packageRequestSearchMapper.toSearchResponseList(requests, Set.of(), viewer);
        List<UserListingItem> items = new ArrayList<>(responses.size());
        for (int i = 0; i < responses.size(); i++) {
            items.add(new UserListingItem(UserListingItem.KIND_REQUEST,
                    publishedAt(requests.get(i)), null, responses.get(i)));
        }
        return items;
    }

    /** Même définition de la publication que l'expiration des demandes sans date. */
    static LocalDateTime publishedAt(PackageRequestEntity request) {
        return request.getDisclaimerSignedAt() != null
                ? request.getDisclaimerSignedAt()
                : request.getCreatedAt();
    }
}
