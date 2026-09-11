package com.yadony.api.requests.event;

import java.util.UUID;

/** Le fil revient à « à payer » : dépôt échoué, échéance passée ou renoncement. {@code reason} : deposit-failed | deposit-expired | sender-cancelled. */
public record NegotiationDepositRevertedEvent(UUID threadId, UUID packageRequestId, UUID senderId, UUID travelerId, String reason) {}
