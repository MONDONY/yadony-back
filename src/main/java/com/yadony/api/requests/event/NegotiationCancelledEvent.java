package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * A negotiation ended before the deal was sealed — either a participant closed
 * the thread ({@code cancelNegotiation}), or the sender cancelled the whole
 * package request out from under it ({@code PackageRequestService.cancel}).
 * Notify the other party that the negotiation is over.
 *
 * <p>Two financial side-effects can be owed, and each is carried by its own
 * flag rather than re-derived by the listener — only the transaction that
 * cancelled the thread knows which status it was in before the update:
 *
 * <ul>
 *   <li>{@code releaseEscrow} — the thread was AWAITING_PAYMENT, so an
 *       in-flight Stripe card hold may exist and MUST be cancelled.</li>
 *   <li>{@code refundCommission} — the thread was AWAITING_COMMISSION, so the
 *       traveler may already have paid yadony's commission for a deal that will
 *       now never happen. Keeping it would be charging for nothing.</li>
 * </ul>
 *
 * <p>Per CLAUDE.md rule #18 both Stripe side-effects run in
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}
 * listeners, so they only fire once the cancel transaction actually commits (a
 * rollback fires no AFTER_COMMIT → no financial leak). Running the refund
 * inline would be worse than slow: it would open a {@code REQUIRES_NEW}
 * transaction on a row the caller's transaction already holds, blocking on
 * itself with no cycle visible to PostgreSQL, hence no deadlock detection.
 */
public record NegotiationCancelledEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID byUserId,
    UUID toUserId,
    String byName,
    boolean releaseEscrow,
    boolean refundCommission
) {}
