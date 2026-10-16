package com.yadony.api.assistant.dto;

import java.util.List;

public record AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                   List<String> sources, String provider, int remainingToday) {
}
