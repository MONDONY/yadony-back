package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Capacité d'un trajet prise et rendue par un colis : point d'écriture unique de la place d'un colis, appelé par
 * chaque acceptation (carte, automatisation, fil payé, espèces, mobile money) et chaque rendu (annulation,
 * refus, absence, report, expiration, annulation admin). Seuls les kilos ajoutés par une modification de colis
 * passent encore directement par l'entité ({@code BidAmendmentCapacity}, au kilo).
 *
 * <ul>
 *   <li><b>Trajet au kilo</b> (tous modes, bateau au kilo compris) : règle de l'entité
 *       ({@link AnnouncementEntity#reserveCapacity} / {@link AnnouncementEntity#releaseCapacity}), inchangée :
 *       kilos décrémentés ou rendus, sauf kilo libre et colis sans poids, bascule {@code FULL} à zéro (jamais
 *       sur un trajet hors marché), {@code FULL → ACTIVE} au rendu.</li>
 *   <li><b>Trajet bateau vendu au m³</b> ({@link AnnouncementEntity#isPricedPerM3()}) : aucun kilo (nuls),
 *       {@code availableVolumeM3 ∓ bid.volumeM3}, même bascule {@code FULL} / {@code ACTIVE} sur le volume.</li>
 *   <li><b>Bateau au kilo avec un volume facultatif</b> (informatif) : le volume n'est JAMAIS décrémenté par un
 *       colis au kilo (décision du lot 3) : ces colis n'ont pas de volume ({@link ParcelFitRules#resolveBidVolume}
 *       ignore les dimensions hors trajet au m³) et seul le prix au m³ fait d'un trajet un trajet au volume.</li>
 * </ul>
 *
 * <p>Concurrence : chaque appelant tient le verrou pessimiste du trajet ({@code findByIdForUpdate}) quand il
 * vérifie puis prend la place ; deux acceptations simultanées sur le dernier m³ se sérialisent, la seconde
 * relit le volume à jour et reçoit 409 {@code insufficient-volume}.
 */
public final class TripCapacity {

    private static final Logger log = LoggerFactory.getLogger(TripCapacity.class);

    private TripCapacity() {}

    /**
     * Réservation (offre, paiement carte, proposition de prix) : poids au-delà des kilos restants, 422
     * {@code weight-exceeds-capacity}, comme avant. Kilo libre, poids absent et trajet sans kilos (bateau au m³,
     * dont seul le volume compte) ne bloquent rien.
     */
    public static void assertWeightBookable(AnnouncementEntity trip, BigDecimal weightKg) {
        if (trip != null && trip.getCapacityUnit() != CapacityUnit.KG_FREE && weightKg != null
                && trip.getAvailableKg() != null && weightKg.compareTo(trip.getAvailableKg()) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "weight-exceeds-capacity",
                    "problem.weight-exceeds-capacity");
        }
    }

    /**
     * Refuse l'acceptation d'un colis qui ne tient plus dans la place restante (409, la place a été prise depuis
     * la réservation) : volume au m³ ({@code insufficient-volume}), kilos sinon ({@code capacity-insufficient},
     * comme avant ; kilo libre, colis sans poids ou capacité inconnue ne bloquent rien).
     */
    public static void assertAcceptable(AnnouncementEntity trip, BidEntity bid) {
        if (trip == null || bid == null) {
            return;
        }
        if (trip.isPricedPerM3()) {
            if (!ParcelFitRules.fitsVolume(trip.getAvailableVolumeM3(), bid.getVolumeM3())) {
                throw ParcelFitRules.insufficientVolume(HttpStatus.CONFLICT);
            }
            return;
        }
        if (trip.getCapacityUnit() != CapacityUnit.KG_FREE && bid.getWeightKg() != null
                && trip.getAvailableKg() != null && bid.getWeightKg().compareTo(trip.getAvailableKg()) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "capacity-insufficient",
                    "problem.capacity-insufficient");
        }
    }

    /**
     * Prend la place du colis sur le trajet.
     *
     * @return {@code true} si le trajet a changé (l'appelant le sauvegarde alors)
     */
    public static boolean reserve(AnnouncementEntity trip, BidEntity bid) {
        if (trip == null || bid == null) {
            return false;
        }
        if (!trip.isPricedPerM3()) {
            return trip.reserveCapacity(bid.getWeightKg());
        }
        return reserveVolume(trip, bid.getVolumeM3(), "colis " + bid.getId());
    }

    /**
     * Retire {@code volumeM3} du volume restant d'un trajet vendu au m³ (colis, ou volume ajouté par une
     * modification de colis) et bascule le trajet {@code FULL} à zéro (jamais un trajet hors marché). Sans effet
     * hors trajet au m³, sans volume ou sans volume restant connu.
     *
     * @param owner ce qui prend la place, pour le journal ({@code "colis <id>"}, {@code "modification <id>"})
     * @return {@code true} si le trajet a changé (l'appelant le sauvegarde alors)
     */
    public static boolean reserveVolume(AnnouncementEntity trip, BigDecimal volumeM3, String owner) {
        if (trip == null || !trip.isPricedPerM3() || volumeM3 == null || volumeM3.signum() <= 0) {
            return false;
        }
        BigDecimal available = trip.getAvailableVolumeM3();
        if (available == null) {
            return false;
        }
        BigDecimal left = available.subtract(volumeM3);
        if (left.signum() < 0) {
            // Inatteignable par les acceptations (assertAcceptable sous verrou) ; possible pour un fil déjà payé
            // dont le trajet lié s'est rempli entre le contrôle d'avant paiement et la matérialisation (même
            // surréservation préexistante qu'au kilo). Jamais sous zéro : chk_announcements_volume ferait échouer la
            // matérialisation du colis payé, l'argent étant déjà pris.
            log.error("Trajet {} : {} de {} m³ pour {} m³ restants, volume ramené à 0 (surréservation)",
                    trip.getId(), owner, volumeM3, available);
            left = BigDecimal.ZERO;
        }
        trip.setAvailableVolumeM3(left);
        if (left.signum() <= 0 && !AnnouncementStatus.OUT_OF_MARKET.contains(trip.getStatus())) {
            trip.setStatus(AnnouncementStatus.FULL);
        }
        return true;
    }

    /**
     * Rend la place du colis au trajet et rouvre un trajet {@code FULL}. Inverse exact de {@link #reserve}.
     *
     * @return {@code true} si le trajet a changé (l'appelant le sauvegarde alors)
     */
    public static boolean release(AnnouncementEntity trip, BidEntity bid) {
        if (trip == null || bid == null) {
            return false;
        }
        if (!trip.isPricedPerM3()) {
            return trip.releaseCapacity(bid.getWeightKg());
        }
        return releaseVolume(trip, bid.getVolumeM3(), "colis " + bid.getId());
    }

    /**
     * Rend {@code volumeM3} au volume restant d'un trajet vendu au m³, plafonné au volume proposé, et rouvre un
     * trajet {@code FULL}. Inverse exact de {@link #reserveVolume}.
     *
     * @return {@code true} si le trajet a changé (l'appelant le sauvegarde alors)
     */
    public static boolean releaseVolume(AnnouncementEntity trip, BigDecimal volumeM3, String owner) {
        if (trip == null || !trip.isPricedPerM3() || volumeM3 == null || volumeM3.signum() <= 0) {
            return false;
        }
        BigDecimal available = trip.getAvailableVolumeM3();
        if (available == null) {
            return false;
        }
        BigDecimal restored = available.add(volumeM3);
        BigDecimal capacity = trip.getVolumeCapacityM3();
        if (capacity != null && restored.compareTo(capacity) > 0) {
            // Jamais au-delà du volume proposé (chk_announcements_volume) : un trajet dédié garde tout le volume de
            // sa demande (rien n'est pris à la matérialisation de son colis), le lui rendre le ferait déborder.
            log.info("Trajet {} : rendu de {} plafonné au volume proposé ({} m³)", trip.getId(), owner, capacity);
            restored = capacity;
        }
        trip.setAvailableVolumeM3(restored);
        if (trip.getStatus() == AnnouncementStatus.FULL) {
            trip.setStatus(AnnouncementStatus.ACTIVE);
        }
        return true;
    }
}
