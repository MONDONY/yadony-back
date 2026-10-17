package com.yadony.api.matching;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelRef;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Rôle du demandeur, trajet et étape du colis : le socle des faits donnés à l'assistant. */
@Component
@Order(10)
public class ParcelProgressFacts implements ParcelFactsContributor {

    private final BidRepository bidRepository;

    public ParcelProgressFacts(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelFacts facts(ParcelRef parcel) {
        BidEntity bid = bidRepository.findById(parcel.bidId()).orElse(null);
        if (bid == null) return ParcelFacts.NONE;
        List<String> lines = new ArrayList<>();
        lines.add(switch (parcel.role()) {
            case SENDER -> "The user is the sender of this parcel.";
            case TRAVELER -> "The user is the traveler carrying this parcel.";
            case RECIPIENT -> "The user is the recipient of this parcel.";
        });
        if (parcel.departureCity() != null && parcel.arrivalCity() != null) {
            lines.add("Trip: " + parcel.departureCity() + " to " + parcel.arrivalCity()
                    + (parcel.departureDate() != null ? ", departure on " + parcel.departureDate() : "") + ".");
        }
        lines.add("Current step: " + step(bid));
        return ParcelFacts.of(lines);
    }

    static String step(BidEntity bid) {
        return switch (bid.getStatus()) {
            case AWAITING_PAYMENT -> "waiting for the sender's payment; the traveler does not see the request until it is paid.";
            case PENDING -> "request sent, waiting for the traveler to accept or decline it.";
            case PAYMENT_ESCROWED -> "paid, waiting for the traveler to accept or decline the request.";
            case ACCEPTED -> "accepted by the traveler; the parcel has not been handed over to the traveler yet.";
            case HANDED_OVER -> "handed over to the traveler, who has the parcel.";
            case IN_TRANSIT -> "in transit with the traveler.";
            case ARRIVED -> "arrived at destination, waiting for the recipient to collect it with the pickup code.";
            case COMPLETED -> "delivered to the recipient" + on(bid.getDeliveredAt()) + ".";
            case CANCELLED -> "cancelled" + on(bid.getCancelledAt()) + ".";
            case REJECTED -> "declined by the traveler.";
            case EXPIRED -> "expired: it was not accepted or not paid in time.";
            case NO_SHOW -> "closed after a no-show at handover or delivery.";
            case PARCEL_REFUSED -> "refused by the traveler at handover.";
            case NEGOTIATING, NEGOTIATION_CLOSED -> "price discussion, not a booked parcel.";
        };
    }

    private static String on(LocalDateTime at) {
        return at == null ? "" : " on " + at.toLocalDate();
    }
}
