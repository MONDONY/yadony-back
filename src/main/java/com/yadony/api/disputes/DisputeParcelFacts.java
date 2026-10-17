package com.yadony.api.disputes;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Litiges du colis, ouverts ou tranchés ; le destinataire n'est pas partie à un litige. */
@Component
@Order(50)
public class DisputeParcelFacts implements ParcelFactsContributor {

    private final DisputeRepository disputeRepository;

    public DisputeParcelFacts(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelFacts facts(ParcelRef parcel) {
        if (parcel.role() == ParcelRole.RECIPIENT) return ParcelFacts.NONE;
        List<String> lines = new ArrayList<>();
        Set<ParcelLink> links = EnumSet.noneOf(ParcelLink.class);
        for (DisputeEntity d : disputeRepository.findByBidIdIn(List.of(parcel.bidId()))) {
            links.add(ParcelLink.DISPUTES);
            if (DisputeTypes.STATUS_RESOLVED.equals(d.getStatus())) {
                lines.add("A dispute on this parcel was decided by the team"
                        + (d.getResolvedAt() != null ? " on " + d.getResolvedAt().toLocalDate() : "")
                        + "; the decision is in My disputes.");
            } else {
                lines.add("A dispute is open on this parcel and the team is reviewing it; it can be followed in "
                        + "My disputes." + (d.isRefundFrozen()
                        ? " Refunds and payouts for this parcel are frozen until the decision." : ""));
            }
        }
        return new ParcelFacts(lines, links);
    }
}
