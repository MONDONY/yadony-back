package com.yadony.api.matching.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record HandoverAlertEvent(
        UUID bidId,
        UUID senderId,
        String handoverLocation,
        LocalDateTime handoverDeadline
) {}
