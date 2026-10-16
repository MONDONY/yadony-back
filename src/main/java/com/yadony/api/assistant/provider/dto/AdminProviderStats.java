package com.yadony.api.assistant.provider.dto;

import java.time.Instant;

public record AdminProviderStats(int requestsToday, int successesToday, Integer lastStatus, String lastError,
                                 Instant cooldownUntil) {
}
