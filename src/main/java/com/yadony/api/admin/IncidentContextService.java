package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminIncidentContextResponse;
import com.yadony.api.admin.dto.AdminIncidentContextResponse.RelatedIncident;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.cancellation.NoShowReasons;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contexte d'un litige ou d'une déclaration d'absence pour le poste de traitement (consultation
 * seule). Lit les dépôts des autres packages sans injecter leurs services, comme les autres vues
 * admin.
 */
@Service
public class IncidentContextService {

    /** Autres incidents montrés au plus. */
    static final int RELATED_LIMIT = 10;
    static final String DISPUTE_ENTITY = "DISPUTE";
    static final String CANCELLATION_ENTITY = "CANCELLATION";

    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final BidRepository bidRepo;
    private final AnnouncementRepository announcementRepo;
    private final ReportContextService reportContext;

    public IncidentContextService(DisputeRepository disputeRepo, CancellationRepository cancellationRepo,
                                  BidRepository bidRepo, AnnouncementRepository announcementRepo,
                                  ReportContextService reportContext) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.reportContext = reportContext;
    }

    @Transactional(readOnly = true)
    public AdminIncidentContextResponse forDispute(DisputeEntity dispute) {
        Set<UUID> parties = new LinkedHashSet<>();
        if (dispute.getSenderId() != null) {
            parties.add(dispute.getSenderId());
        }
        if (dispute.getTravelerId() != null) {
            parties.add(dispute.getTravelerId());
        }
        return new AdminIncidentContextResponse(related(parties, dispute.getId(), null),
                reportContext.history(DISPUTE_ENTITY, dispute.getId()));
    }

    @Transactional(readOnly = true)
    public AdminIncidentContextResponse forCancellation(CancellationEntity cancellation) {
        Set<UUID> parties = new LinkedHashSet<>();
        bidRepo.findById(cancellation.getBidId()).ifPresent(bid -> {
            if (bid.getSenderId() != null) {
                parties.add(bid.getSenderId());
            }
            if (bid.getAnnouncementId() != null) {
                announcementRepo.findById(bid.getAnnouncementId()).ifPresent(a -> {
                    if (a.getTravelerId() != null) {
                        parties.add(a.getTravelerId());
                    }
                });
            }
        });
        return new AdminIncidentContextResponse(related(parties, null, cancellation.getId()),
                reportContext.history(CANCELLATION_ENTITY, cancellation.getId()));
    }

    /**
     * Litiges et absences déclarées où l'une des deux parties figure, les plus récents d'abord, sans
     * l'incident lui-même. Les identifiants viennent de l'incident, jamais d'une saisie.
     */
    private java.util.List<RelatedIncident> related(Set<UUID> parties, UUID selfDispute, UUID selfCancellation) {
        Map<String, RelatedIncident> found = new LinkedHashMap<>();
        for (UUID party : parties) {
            for (DisputeEntity d : disputeRepo.findAdminByParty(party, PageRequest.of(0, RELATED_LIMIT)).getContent()) {
                if (d.getId().equals(selfDispute)) {
                    continue;
                }
                found.putIfAbsent("D" + d.getId(), new RelatedIncident("DISPUTE", d.getId(), d.getType(), null,
                        DisputeTypes.STATUS_OPEN.equals(d.getStatus()), d.getCreatedAt()));
            }
            for (CancellationEntity c : cancellationRepo.findAdminByParty(party, PageRequest.of(0, RELATED_LIMIT)).getContent()) {
                if (!NoShowReasons.ALL.contains(c.getReason()) || c.getId().equals(selfCancellation)) {
                    continue;
                }
                found.putIfAbsent("N" + c.getId(), new RelatedIncident("NOSHOW", c.getId(), c.getReason(),
                        c.getScope() != null ? c.getScope().name() : null, needsDecision(c), c.getCreatedAt()));
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparing(RelatedIncident::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RELATED_LIMIT)
                .toList();
    }

    /** Une absence attend l'arbitrage tant qu'elle est en attente ou contestée et qu'aucun admin n'a tranché. */
    private static boolean needsDecision(CancellationEntity c) {
        return c.getAdminDecision() == null
                && (c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                || c.getNoShowStatus() == CancellationStatus.CONTESTED);
    }
}
