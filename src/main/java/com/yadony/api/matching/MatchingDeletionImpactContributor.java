package com.yadony.api.matching;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Ce que la disparition d'un compte fait subir à ses contreparties de trajet.
 *
 * <p>Le colis en transit est le cas grave : quelqu'un attend physiquement une remise. Il n'est
 * pas bloquant pour autant — un compte peut devoir être supprimé en urgence, mais l'administrateur
 * doit savoir qui prévenir.
 */
@Component
public class MatchingDeletionImpactContributor implements UserDeletionImpactContributor {

    /** Le colis est physiquement pris en charge. Constante déjà définie sur l'enum. */
    private static final Collection<BidStatus> IN_TRANSIT = BidStatus.EN_ROUTE;

    /** Une décision est attendue de l'une des deux parties. */
    private static final Collection<BidStatus> AWAITING =
            EnumSet.of(BidStatus.PENDING, BidStatus.NEGOTIATING);

    /** Trajets qui n'ont pas encore produit tous leurs effets. */
    private static final Collection<AnnouncementStatus> UPCOMING = EnumSet.of(
            AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public MatchingDeletionImpactContributor(BidRepository bidRepository,
                                             AnnouncementRepository announcementRepository) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        List<ImpactFinding> findings = new ArrayList<>();

        addIfAny(findings, "PARCEL_IN_TRANSIT", bothRoles(userId, IN_TRANSIT));
        addIfAny(findings, "PENDING_BID", bothRoles(userId, AWAITING));

        long upcoming = announcementRepository.countByTravelerIdAndStatusIn(userId, UPCOMING);
        if (upcoming > 0) {
            findings.add(ImpactFinding.plain(
                    ImpactSeverity.WARNING, "UPCOMING_ANNOUNCEMENT", (int) upcoming));
        }

        return findings;
    }

    /** Un compte est expéditeur sur certaines offres et voyageur sur d'autres : les deux comptent. */
    private List<ImpactFinding.AffectedParty> bothRoles(UUID userId, Collection<BidStatus> statuses) {
        List<ImpactFinding.AffectedParty> parties = new ArrayList<>();
        for (var c : bidRepository.findTravelerCounterpartiesForSender(userId, statuses)) {
            parties.add(new ImpactFinding.AffectedParty(c.getCounterpartyId(), c.getBidId()));
        }
        for (var c : bidRepository.findSenderCounterpartiesForTraveler(userId, statuses)) {
            parties.add(new ImpactFinding.AffectedParty(c.getCounterpartyId(), c.getBidId()));
        }
        return parties;
    }

    private void addIfAny(List<ImpactFinding> findings, String code,
                          List<ImpactFinding.AffectedParty> parties) {
        if (!parties.isEmpty()) {
            findings.add(ImpactFinding.of(ImpactSeverity.WARNING, code, parties));
        }
    }
}
