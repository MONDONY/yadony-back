package com.yadony.api.assistant.parcel;

import com.yadony.api.assistant.dto.AssistantAction;
import com.yadony.api.assistant.dto.AssistantParcelChoice;
import com.yadony.api.common.assistant.ParcelDirectory;
import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Les colis de l'utilisateur dans l'assistant : retrouver celui dont il parle, réunir ce que
 * chaque package en sait, et traduire en boutons les repères que le modèle a le droit de citer.
 * L'accès est contrôlé à chaque question par {@link ParcelDirectory}, jamais déduit de la
 * conversation ni de la réponse du modèle.
 */
@Component
public class AssistantParcelFlow {

    private static final Logger log = LoggerFactory.getLogger(AssistantParcelFlow.class);

    static final int MAX_CHOICES = 5;
    /** Repères donnés au modèle à la place des chemins : il ne recopie jamais un identifiant. */
    public static final String OPEN_TOKEN = "parcel:open";
    public static final String NEW_CODE_TOKEN = "parcel:new-code";

    private final ParcelDirectory directory;
    private final List<ParcelFactsContributor> contributors;
    private final AssistantLookupGuard guard;

    public AssistantParcelFlow(ParcelDirectory directory, List<ParcelFactsContributor> contributors,
                               AssistantLookupGuard guard) {
        this.directory = directory;
        this.contributors = List.copyOf(contributors);
        this.guard = guard;
    }

    /** Colis dont parle la question : numéro DON-… écrit, sinon colis choisi plus tôt ({@code parcelId}). */
    public Lookup lookup(String uid, String question, UUID parcelId) {
        Optional<String> number = TrackingNumbers.find(question);
        if (number.isPresent()) {
            if (guard.blocked(uid)) return Lookup.INCORRECT;
            Optional<ParcelRef> parcel = directory.byTrackingNumber(uid, number.get());
            if (parcel.isEmpty()) {
                guard.recordFailure(uid);
                return Lookup.INCORRECT;
            }
            return new Lookup(parcel.get(), false, true);
        }
        if (parcelId == null) return Lookup.NONE;
        // Colis choisi plus tôt mais plus accessible (retiré de sa liste, compte changé) : on repart de zéro.
        return directory.byId(uid, parcelId).map(p -> new Lookup(p, false, false)).orElse(Lookup.NONE);
    }

    public List<ParcelRef> choices(String uid) {
        return directory.recentParcels(uid, MAX_CHOICES);
    }

    /** Faits de tous les packages, dans leur ordre ; un package en panne n'empêche pas de répondre. */
    public ParcelFacts facts(ParcelRef parcel) {
        List<String> lines = new ArrayList<>();
        Set<ParcelLink> links = EnumSet.noneOf(ParcelLink.class);
        for (ParcelFactsContributor contributor : contributors) {
            try {
                ParcelFacts facts = contributor.facts(parcel);
                lines.addAll(facts.lines());
                links.addAll(facts.links());
            } catch (RuntimeException e) {
                log.warn("Assistant : faits du colis indisponibles ({})", contributor.getClass().getSimpleName(), e);
            }
        }
        return new ParcelFacts(lines, links);
    }

    /** Boutons que le modèle peut proposer pour ce colis, par repère ; la fiche du colis d'abord. */
    public Map<String, AssistantAction> actions(ParcelRef parcel, ParcelFacts facts, String locale) {
        Map<String, AssistantAction> actions = new LinkedHashMap<>();
        actions.put(OPEN_TOKEN, open(parcel, locale));
        if (facts.links().contains(ParcelLink.RENEW_PICKUP_CODE) && parcel.role() == ParcelRole.SENDER) {
            actions.put(NEW_CODE_TOKEN, new AssistantAction("/bids/" + parcel.bidId() + "?action=new-code",
                    ParcelTexts.linkLabel(ParcelLink.RENEW_PICKUP_CODE, locale)));
        }
        if (facts.links().contains(ParcelLink.DISPUTES)) {
            actions.put("/disputes", new AssistantAction("/disputes", ParcelTexts.linkLabel(ParcelLink.DISPUTES, locale)));
        }
        if (facts.links().contains(ParcelLink.SUPPORT)) {
            actions.put("/support", new AssistantAction("/support", ParcelTexts.linkLabel(ParcelLink.SUPPORT, locale)));
        }
        return actions;
    }

    public AssistantAction open(ParcelRef parcel, String locale) {
        String route = parcel.role() == ParcelRole.RECIPIENT
                ? "/receptions/" + parcel.bidId() : "/bids/" + parcel.bidId();
        return new AssistantAction(route, ParcelTexts.openLabel(parcel, locale));
    }

    public static AssistantParcelChoice choice(ParcelRef parcel) {
        return new AssistantParcelChoice(parcel.bidId(), parcel.trackingNumber(), parcel.departureCity(),
                parcel.arrivalCity(), parcel.departureDate(), parcel.status(), parcel.role().name(), parcel.active());
    }

    public static String incorrect(String locale) {
        return ParcelTexts.INCORRECT.get(locale);
    }

    public static String noParcel(String locale) {
        return ParcelTexts.NO_PARCEL.get(locale);
    }

    /** Invite à choisir ; sans colis en cours, le dit d'abord. */
    public static String choose(List<ParcelRef> choices, String locale) {
        boolean anyActive = choices.stream().anyMatch(ParcelRef::active);
        return (anyActive ? ParcelTexts.CHOOSE : ParcelTexts.RECENT_ONLY).get(locale);
    }

    public static String status(ParcelRef parcel, String locale) {
        return ParcelTexts.status(parcel, locale);
    }

    /**
     * @param parcel    colis retrouvé, ou {@code null}
     * @param incorrect un numéro a été écrit mais ne mène à aucun colis de l'utilisateur
     * @param byNumber  le colis a été retrouvé par le numéro écrit dans cette question
     */
    public record Lookup(ParcelRef parcel, boolean incorrect, boolean byNumber) {
        static final Lookup NONE = new Lookup(null, false, false);
        static final Lookup INCORRECT = new Lookup(null, true, false);
    }
}
