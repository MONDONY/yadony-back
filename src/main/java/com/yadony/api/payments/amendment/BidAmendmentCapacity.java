package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.CapacityUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Kilos d'une modification retirés du trajet, et rendus une seule fois. Toujours sous le verrou du trajet
 * ({@link AnnouncementRepository#findByIdForUpdate}), pris après celui du colis : deux expéditeurs qui
 * visent les derniers kilos d'un même trajet se sérialisent, le second voit la capacité déjà réduite.
 *
 * <p>{@code capacityReservedKg} dit ce qui a réellement été retiré : 0 en capacité libre ({@code KG_FREE})
 * ou pour des articles seuls, et 0 une fois rendu. Le rendu ne peut donc jamais être compté deux fois.
 */
@Component
public class BidAmendmentCapacity {

    private final AnnouncementRepository announcements;
    private final BidAmendmentRepository amendments;

    public BidAmendmentCapacity(AnnouncementRepository announcements, BidAmendmentRepository amendments) {
        this.announcements = announcements;
        this.amendments = amendments;
    }

    /**
     * 409 {@code amendment-capacity-unavailable} si le trajet n'a pas {@code addedKg} libres. Sans effet en
     * capacité libre ou sans kilos demandés.
     */
    public static void requireAvailable(AnnouncementEntity announcement, BigDecimal addedKg) {
        if (!consumesCapacity(announcement, addedKg)) {
            return;
        }
        BigDecimal available = announcement.getAvailableKg() == null ? BigDecimal.ZERO : announcement.getAvailableKg();
        if (available.compareTo(addedKg) < 0) {
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
        }
        amendment.setCapacityReservedKg(BigDecimal.ZERO);
        amendments.save(amendment);
    }

    private static boolean consumesCapacity(AnnouncementEntity announcement, BigDecimal kg) {
        return kg != null && kg.signum() > 0 && announcement.getCapacityUnit() != CapacityUnit.KG_FREE;
    }
}
