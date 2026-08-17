package com.yadony.api.requests.event;

import com.yadony.api.requests.entity.NegotiationThreadStatus;
import java.util.UUID;

/**
 * A negotiation ended before the deal was sealed — either a participant closed
 * the thread ({@code cancelNegotiation}), or the sender cancelled the whole
 * package request out from under it ({@code PackageRequestService.cancel}).
 * Notify the other party that the negotiation is over.
 *
 * <p>The event carries the thread's status from BEFORE the cancelling mutation
 * — only the transaction that cancelled the thread knows it — and the two
 * financial side-effects that may be owed are <em>derived</em> from it right
 * here, so no publisher ever re-implements the mapping:
 *
 * <ul>
 *   <li>{@link #releaseEscrow()} — the thread was AWAITING_PAYMENT, so an
 *       in-flight Stripe card hold may exist and MUST be cancelled.</li>
 *   <li>{@link #refundCommission()} — the thread was AWAITING_COMMISSION, so
 *       the traveler may already have paid yadony's commission for a deal that
 *       will now never happen. Keeping it would be charging for nothing.</li>
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
    NegotiationThreadStatus previousStatus
) {
    /** An in-flight Stripe card hold may exist and must be cancelled. */
    public boolean releaseEscrow() {
        return previousStatus == NegotiationThreadStatus.AWAITING_PAYMENT;
    }

    /** A commission may already have been charged and must be refunded. */
    public boolean refundCommission() {
        return previousStatus == NegotiationThreadStatus.AWAITING_COMMISSION;
    }
}
