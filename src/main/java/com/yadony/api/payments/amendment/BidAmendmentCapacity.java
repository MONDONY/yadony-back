package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TripCapacity;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

/**
 * Kilos (ou volume, sur un trajet vendu au m³) d'une modification retirés du trajet, et rendus une seule fois. Toujours sous le verrou du trajet
 * ({@link AnnouncementRepository#findByIdForUpdate}), pris après celui du colis : deux expéditeurs qui
 * visent les derniers kilos d'un même trajet se sérialisent, le second voit la capacité déjà réduite.
 *
 * <p>{@code capacityReservedKg} dit ce qui a réellement été retiré : 0 en capacité libre ({@code KG_FREE})
 * ou pour des articles seuls, et 0 une fois rendu. Le rendu ne peut donc jamais être compté deux fois. Même règle
 * pour le volume ({@code capacityReservedVolumeM3}), pris et rendu par {@link TripCapacity} (bascule {@code FULL}
 * à zéro, {@code FULL → ACTIVE} au rendu, jamais au-delà du volume proposé).
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
     * Le trajet a-t-il {@code addedKg} libres ET {@code addedVolumeM3} libres ? Le volume ne compte que sur un trajet
     * vendu au m³ : ailleurs, la tarification refuse l'ajout de volume (422 {@code amendment-not-eligible}).
     */
    public static boolean isAvailable(AnnouncementEntity announcement, BigDecimal addedKg, BigDecimal addedVolumeM3) {
        return isAvailable(announcement, addedKg) && isVolumeAvailable(announcement, addedVolumeM3);
    }

    /** Le trajet au m³ a-t-il {@code addedVolumeM3} libres ? Toujours vrai sans volume demandé ou hors trajet au m³. */
    static boolean isVolumeAvailable(AnnouncementEntity announcement, BigDecimal addedVolumeM3) {
        if (!consumesVolume(announcement, addedVolumeM3)) {
            return true;
        }
        BigDecimal available = announcement.getAvailableVolumeM3() == null
                ? BigDecimal.ZERO : announcement.getAvailableVolumeM3();
        return available.compareTo(addedVolumeM3) >= 0;
    }

    /**
     * 409 {@code amendment-capacity-unavailable} si le trajet n'a pas {@code addedKg} libres. Sans effet en
     * capacité libre ou sans kilos demandés.
     */
    public static void requireAvailable(AnnouncementEntity announcement, BigDecimal addedKg) {
        if (!isAvailable(announcement, addedKg)) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "amendment-capacity-unavailable", "problem.amendment-capacity-unavailable");
        }
    }

    /**
     * Kilos puis volume : 409 {@code amendment-capacity-unavailable} si le trajet n'a pas {@code addedKg} libres, ou
     * pas {@code addedVolumeM3} libres sur un trajet vendu au m³ (même code, message sur le volume).
     */
    public static void requireAvailable(AnnouncementEntity announcement, BigDecimal addedKg, BigDecimal addedVolumeM3) {
        requireAvailable(announcement, addedKg);
        if (!isVolumeAvailable(announcement, addedVolumeM3)) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "amendment-capacity-unavailable",
                    "problem.amendment-volume-capacity-unavailable");
        }
    }

    /**
     * Retire du trajet les kilos de la modification et le note dans {@code capacityReservedKg}. Le trajet
     * doit être verrouillé par l'appelant (c'est l'instance gérée renvoyée par {@code findByIdForUpdate}).
     */
    public void reserve(BidAmendmentEntity amendment, AnnouncementEntity announcement) {
        BigDecimal kg = amendment.getAddedWeightKg();
        BigDecimal volume = amendment.getAddedVolumeM3();
        requireAvailable(announcement, kg, volume);
        boolean changed = false;
        if (consumesCapacity(announcement, kg)) {
            changed = announcement.reserveCapacity(kg);
            amendment.setCapacityReservedKg(kg);
        }
        if (consumesVolume(announcement, volume)) {
            changed |= TripCapacity.reserveVolume(announcement, volume, "modification " + amendment.getId());
            amendment.setCapacityReservedVolumeM3(volume);
        }
        if (changed) {
            announcements.save(announcement);
            evictSearchAfterCommit();
        }
        if (consumesCapacity(announcement, kg) || consumesVolume(announcement, volume)) {
            amendments.save(amendment);
        }
    }

    /** Rend au trajet les kilos et le volume encore retirés. Idempotent : un second appel ne rend rien. */
    public void release(BidAmendmentEntity amendment) {
        BigDecimal reservedKg = amendment.getCapacityReservedKg();
        BigDecimal reservedVolume = amendment.getCapacityReservedVolumeM3();
        boolean hasKg = reservedKg != null && reservedKg.signum() > 0;
        boolean hasVolume = reservedVolume != null && reservedVolume.signum() > 0;
        if (!hasKg && !hasVolume) {
            return;
        }
        AnnouncementEntity announcement = announcements.findByIdForUpdate(amendment.getAnnouncementId())
                .orElse(null);
        boolean changed = false;
        if (announcement != null && hasKg) {
            changed = announcement.releaseCapacity(reservedKg);
        }
        if (announcement != null && hasVolume) {
            changed |= TripCapacity.releaseVolume(announcement, reservedVolume, "modification " + amendment.getId());
        }
        if (changed) {
            announcements.save(announcement);
            evictSearchAfterCommit();
        }
        if (hasKg) {
            amendment.setCapacityReservedKg(BigDecimal.ZERO);
        }
        if (hasVolume) {
            amendment.setCapacityReservedVolumeM3(BigDecimal.ZERO);
        }
        amendments.save(amendment);
    }

    /** L'ajout a-t-il encore des kilos ou du volume retirés du trajet ? */
    public static boolean holdsCapacity(BidAmendmentEntity amendment) {
        return (amendment.getCapacityReservedKg() != null && amendment.getCapacityReservedKg().signum() > 0)
                || (amendment.getCapacityReservedVolumeM3() != null
                && amendment.getCapacityReservedVolumeM3().signum() > 0);
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

    private static boolean consumesVolume(AnnouncementEntity announcement, BigDecimal volume) {
        return volume != null && volume.signum() > 0 && announcement.isPricedPerM3();
    }
}
