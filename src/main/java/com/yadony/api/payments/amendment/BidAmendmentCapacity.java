package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.CapacityUnit;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

/**
 * Kilos d'une modification retirés du trajet, et rendus une seule fois. Toujours sous le verrou du trajet
 * ({@link AnnouncementRepository#findByIdForUpdate}), pris après celui du colis : deux expéditeurs qui
 * visent les derniers kilos d'un même trajet se sérialisent, le second voit la capacité déjà réduite.
 *
 * <p>{@code capacityReservedKg} dit ce qui a réellement été retiré : 0 en capacité libre ({@code KG_FREE})
 * ou pour des articles seuls, et 0 une fois rendu. Le rendu ne peut donc jamais être compté deux fois.
 *
 * <p>Tout changement réel des kilos libres vide le cache de recherche ({@value #SEARCH_CACHE}) après le
 * commit, comme les autres chemins qui touchent la capacité ({@code BidService}, annulations) : sans cela, la
 * recherche montrerait des kilos déjà pris, ou un trajet « Complet » qui a retrouvé de la place, jusqu'au TTL.
 * Ici plutôt que sur les méthodes du service : le portail de remise et les rails rendent aussi des kilos.
 */
@Component
public class BidAmendmentCapacity {

    /** Cache des pages de recherche de trajets ({@code AnnouncementService#search}). */
    static final String SEARCH_CACHE = "announcements-search";

    private final AnnouncementRepository announcements;
    private final BidAmendmentRepository amendments;
    private final CacheManager cacheManager;

    public BidAmendmentCapacity(AnnouncementRepository announcements, BidAmendmentRepository amendments,
                                CacheManager cacheManager) {
        this.announcements = announcements;
        this.amendments = amendments;
        this.cacheManager = cacheManager;
    }

    /** Le trajet a-t-il {@code addedKg} libres ? Toujours vrai en capacité libre ou sans kilos demandés. */
    public static boolean isAvailable(AnnouncementEntity announcement, BigDecimal addedKg) {
        if (!consumesCapacity(announcement, addedKg)) {
            return true;
        }
        BigDecimal available = announcement.getAvailableKg() == null ? BigDecimal.ZERO : announcement.getAvailableKg();
        return available.compareTo(addedKg) >= 0;
    }

    /**
     * 409 {@code amendment-capacity-unavailable} si le trajet n'a pas {@code addedKg} libres. Sans effet en
     * capacité libre ou sans kilos demandés.
     */
    public static void requireAvailable(AnnouncementEntity announcement, BigDecimal addedKg) {
        if (!isAvailable(announcement, addedKg)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-capacity-unavailable",
                    "Amendment Capacity Unavailable", "Le trajet n'a plus assez de kilos libres pour cet ajout");
        }
    }

    /**
     * Retire du trajet les kilos de la modification et le note dans {@code capacityReservedKg}. Le trajet
     * doit être verrouillé par l'appelant (c'est l'instance gérée renvoyée par {@code findByIdForUpdate}).
     */
    public void reserve(BidAmendmentEntity amendment, AnnouncementEntity announcement) {
        BigDecimal kg = amendment.getAddedWeightKg();
        requireAvailable(announcement, kg);
        if (!consumesCapacity(announcement, kg)) {
            return;
        }
        if (announcement.reserveCapacity(kg)) {
            announcements.save(announcement);
            evictSearchAfterCommit();
        }
        amendment.setCapacityReservedKg(kg);
        amendments.save(amendment);
    }

    /** Rend au trajet les kilos encore retirés. Idempotent : un second appel ne rend rien. */
    public void release(BidAmendmentEntity amendment) {
        BigDecimal reserved = amendment.getCapacityReservedKg();
        if (reserved == null || reserved.signum() <= 0) {
            return;
        }
        AnnouncementEntity announcement = announcements.findByIdForUpdate(amendment.getAnnouncementId())
                .orElse(null);
        if (announcement != null && announcement.releaseCapacity(reserved)) {
            announcements.save(announcement);
            evictSearchAfterCommit();
        }
        amendment.setCapacityReservedKg(BigDecimal.ZERO);
        amendments.save(amendment);
    }

    /**
     * Vide le cache de recherche après le commit (jamais avant : une recherche concurrente le reremplirait
     * avec l'ancien état), ou tout de suite hors transaction. Une transaction annulée ne vide rien.
     */
    private void evictSearchAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearSearchCache();
                }
            });
        } else {
            clearSearchCache();
        }
    }

    private void clearSearchCache() {
        Cache cache = cacheManager.getCache(SEARCH_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    private static boolean consumesCapacity(AnnouncementEntity announcement, BigDecimal kg) {
        return kg != null && kg.signum() > 0 && announcement.getCapacityUnit() != CapacityUnit.KG_FREE;
    }
}
