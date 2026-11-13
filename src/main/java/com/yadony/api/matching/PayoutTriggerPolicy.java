package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Seul point de calcul de la règle de versement d'un colis ({@link PayoutTrigger},
 * {@code bids.payout_trigger}, V383).
 *
 * <p>Au dépôt ({@link PayoutTrigger#DEPOSIT}) si et seulement si, à l'instant de l'acceptation,
 * le trajet est un bateau, l'interrupteur bateau est allumé et le transporteur (propriétaire du
 * trajet) est vérifié au sens de {@link BoatCarrierPolicy} (GP Pro ou compte PRO). Sinon à la
 * livraison ({@link PayoutTrigger#DELIVERY}), y compris quand un de ces faits manque : dans le
 * doute, l'argent attend la livraison.
 *
 * <p>{@link #freeze} est appelé juste avant chaque passage d'un colis à ACCEPTED (test
 * d'architecture {@code AcceptanceFreezesPayoutTriggerTest}) et n'écrit qu'une fois dans la vie du
 * colis : la première acceptation fige la règle, une réacceptation après une remise en attente la
 * garde. Rien ne la recalcule ensuite. Il ne lève jamais d'exception : certains chemins l'appellent
 * après que l'argent est encaissé (dépôt mobile money confirmé).
 */
@Component
public class PayoutTriggerPolicy {

    private static final Logger log = LoggerFactory.getLogger(PayoutTriggerPolicy.class);

    private final PlatformSettingsService settings;
    private final BoatCarrierPolicy boatCarrierPolicy;
    private final UserRepository users;
    private Clock clock = Clock.systemUTC();

    public PayoutTriggerPolicy(PlatformSettingsService settings, BoatCarrierPolicy boatCarrierPolicy,
                               UserRepository users) {
        this.settings = settings;
        this.boatCarrierPolicy = boatCarrierPolicy;
        this.users = users;
    }

    /** Visible pour les tests : horloge fixe. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Règle qui s'appliquerait à un colis de ce trajet accepté maintenant.
     *
     * @param trip     le trajet du colis ; {@code null} : à la livraison
     * @param traveler le propriétaire du trajet ; {@code null} ou un autre compte : à la livraison
     */
    public PayoutTrigger decide(AnnouncementEntity trip, UserEntity traveler) {
        if (trip == null || trip.getTransportMode() != TransportMode.BOAT) {
            return PayoutTrigger.DELIVERY;
        }
        if (!settings.transportBoatEnabled()) {
            return PayoutTrigger.DELIVERY;
        }
        if (traveler == null || traveler.getId() == null || !traveler.getId().equals(trip.getTravelerId())) {
            if (traveler != null) {
                log.error("Règle de versement : le compte {} n'est pas le transporteur du trajet {}, colis à la livraison",
                        traveler.getId(), trip.getId());
            }
            return PayoutTrigger.DELIVERY;
        }
        return boatCarrierPolicy.isVerifiedCarrier(traveler) ? PayoutTrigger.DEPOSIT : PayoutTrigger.DELIVERY;
    }

    /**
     * Fige la règle sur un colis qui passe à ACCEPTED. Sans effet si le colis est déjà ACCEPTED (ce
     * n'est pas un passage) ou si sa règle a déjà été figée par une acceptation antérieure.
     */
    public void freeze(BidEntity bid, AnnouncementEntity trip, UserEntity traveler) {
        if (bid.getStatus() == BidStatus.ACCEPTED || bid.isPayoutTriggerFrozen()) {
            return;
        }
        PayoutTrigger trigger = decide(trip, traveler);
        bid.freezePayoutTrigger(trigger, LocalDateTime.now(clock));
        if (trigger == PayoutTrigger.DEPOSIT) {
            log.info("Colis {} accepté : versement au transporteur au dépôt (trajet {})",
                    bid.getId(), trip.getId());
        }
    }

    /**
     * Comme {@link #freeze(BidEntity, AnnouncementEntity, UserEntity)}, le transporteur relu en base :
     * le propriétaire du trajet tel qu'il est à cet instant (badge GP Pro, compte PRO).
     */
    public void freeze(BidEntity bid, AnnouncementEntity trip) {
        if (bid.getStatus() == BidStatus.ACCEPTED || bid.isPayoutTriggerFrozen()) {
            return;
        }
        UserEntity traveler = trip == null || trip.getTravelerId() == null
                ? null
                : users.findById(trip.getTravelerId()).orElse(null);
        freeze(bid, trip, traveler);
    }
}
