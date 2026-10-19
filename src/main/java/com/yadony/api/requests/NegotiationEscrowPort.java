package com.yadony.api.requests;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Port (implemented in the {@code payments} package) used by the negotiation
 * feature to verify, at synchronous {@code /negotiations/{id}/checkout} time,
 * that an online (Stripe) PaymentIntent presented by the sender is a genuine,
 * currently-authorized escrow bound to the negotiation thread.
 *
 * <p><b>Security.</b> {@code /checkout} previously trusted any client-supplied
 * {@code paymentIntentId} string and finalized the thread without confirming a
 * real payment — letting a sender obtain an accepted shipment (committed traveler
 * + QR + tracking) without paying, bypassing the Stripe escrow entirely. This
 * port closes that bypass for online payment methods. CASH keeps its own
 * commission gate ({@link CashGatePort}); the trusted webhook finalize path does
 * not use this port (the PaymentIntent is already verified by the signed Stripe
 * webhook).
 */
public interface NegotiationEscrowPort {

    /**
     * Returns {@code true} iff the negotiation thread has a server-created escrow
     * payment whose Stripe PaymentIntent id equals {@code paymentIntentId} AND
     * that PaymentIntent is currently in {@code requires_capture} (card authorized,
     * funds held under manual capture).
     *
     * <p>{@code requires_capture} is the authoritative authorization signal even
     * if the {@code amount_capturable_updated} webhook has not yet been processed,
     * so the legitimate synchronous checkout still works while a forged or
     * never-paid PaymentIntent is rejected. The amount is guaranteed correct by
     * construction: the escrow is created server-side from the thread's locked
     * price, and the client must present that exact PaymentIntent id.
     *
     * <p>Implementations MUST NOT throw on a verification failure or Stripe error
     * — return {@code false} instead (fail closed).
     */
    boolean verifyNegotiationEscrow(UUID threadId, String paymentIntentId);

    /**
     * Releases any in-flight Stripe escrow for this thread (canceling the card
     * hold) so the sender can switch the thread to another payment method (e.g.
     * CASH) at {@code /checkout} without orphaning the hold.
     *
     * @return {@code true} if there was no in-flight escrow, or it was released;
     *         {@code false} if a live escrow exists but could not be released
     *         (e.g. already captured / Stripe error) — the caller MUST then keep
     *         the current method and refuse the switch.
     */
    boolean releaseEscrowForMethodSwitch(UUID threadId);

    /**
     * Total du paiement en ligne du fil (carte ou mobile money : un seul paiement par fil, keyé sur
     * {@code negotiation_thread_id}), dans la devise du fil, tel qu'enregistré à sa création :
     * c'est le montant débité à l'expéditeur, commission comprise. Vide sans paiement vivant
     * (aucun, échoué, annulé ou remboursé). Lecture pure.
     */
    Optional<BigDecimal> paidAmount(UUID threadId);
}
