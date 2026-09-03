package com.yadony.api.toolkit;

import com.yadony.api.toolkit.ToolsCompletionResponse.ToolStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ToolsCompletionService {

    private final ToolsCompletionQueryRepository repository;

    public ToolsCompletionService(ToolsCompletionQueryRepository repository) {
        this.repository = repository;
    }

    public ToolsCompletionResponse compute(UUID userId) {
        List<ToolStatus> tools = List.of(
                ToolStatus.of(ToolKey.ADDRESSES, repository.countAddresses(userId)),
                ToolStatus.of(ToolKey.RECIPIENTS, repository.countRecipients(userId)),
                ToolStatus.of(ToolKey.ALERTS, repository.countAlerts(userId)),
                ToolStatus.of(ToolKey.TRIP_TEMPLATES, repository.countTripTemplates(userId)),
                ToolStatus.of(ToolKey.PRICE_GRID, repository.countPriceGridItems(userId)));
        int ready = (int) tools.stream().filter(ToolStatus::ready).count();
        return new ToolsCompletionResponse(tools.size(), ready, tools);
    }
}
