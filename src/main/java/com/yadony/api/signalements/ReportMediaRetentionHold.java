package com.yadony.api.signalements;

import com.yadony.api.common.MessagingMediaRetentionHold;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Un signalement ouvert sur le bid, ou sur un message d'une de ses conversations, retient
 * les photos de messagerie de ce bid (FLUTTER-B4). Pour une cible MESSAGE, {@code target_id}
 * est la conversation (cf. {@link ReportService}).
 */
@Component
public class ReportMediaRetentionHold implements MessagingMediaRetentionHold {

    private final ReportRepository reportRepository;

    public ReportMediaRetentionHold(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Override
    public boolean holds(UUID bidId, Collection<UUID> conversationIds) {
        if (bidId != null && reportRepository.existsByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.BID, bidId)) {
            return true;
        }
        return conversationIds != null && !conversationIds.isEmpty()
                && reportRepository.existsByStatusAndTargetTypeAndTargetIdIn(
                        ReportStatus.OPEN, ReportTargetType.MESSAGE, conversationIds);
    }
}
