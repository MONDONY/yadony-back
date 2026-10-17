package com.yadony.api.assistant.formdraft;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.ParcelSize;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Formulaire « Publier une demande d'envoi » (spec §3.6), bornes de PackageRequestCreateRequest. */
@Component
public class PackageRequestDraftSchema implements FormDraftSchema {

    private static final Set<String> PAYMENT_METHODS = Set.of("STRIPE", "CASH", "MOBILE_MONEY");
    /** contentCategory @Size(max = 500) : les libellés joints doivent tenir. */
    private static final int MAX_CATEGORIES_LENGTH = 500;
    private static final List<String> KEYS = List.of("departureCity", "arrivalCity", "desiredDate",
            "dateToleranceDays", "transportMode", "weightKg", "parcelSize", "categories", "description",
            "pickupNeighborhood", "deliveryNeighborhood", "negotiable", "acceptedPaymentMethods", "totalBudget",
            "currency", "budgetDeclined");
    private static final List<String> REQUIRED = List.of("departureCity", "arrivalCity", "desiredDate", "weightKg",
            "categories");
    private static final Map<String, Map<String, String>> FALLBACK = Map.of(
            "fr", Map.of(
                    "departureCity", "D'où part votre colis ?",
                    "arrivalCity", "Où va votre colis ?",
                    "desiredDate", "Vers quelle date voulez-vous envoyer votre colis ?",
                    "weightKg", "Combien pèse votre colis ? (entre 0,5 et 32 kg)",
                    "categories", "Qu'y a-t-il dans votre colis ?",
                    "paymentMethod", "Comment voulez-vous payer : carte, espèces ou mobile money ?",
                    "budget", "Avez-vous un budget en tête ? Vous pouvez répondre « je ne sais pas »."),
            "en", Map.of(
                    "departureCity", "Where does your parcel leave from?",
                    "arrivalCity", "Where is your parcel going?",
                    "desiredDate", "Around what date do you want to send your parcel?",
                    "weightKg", "How much does your parcel weigh? (between 0.5 and 32 kg)",
                    "categories", "What is in your parcel?",
                    "paymentMethod", "How do you want to pay: card, cash or mobile money?",
                    "budget", "Do you have a budget in mind? You can answer \"I don't know\"."));
    private static final Map<String, String> CONFIRM = Map.of("fr", "Est-ce bien cela ?", "en", "Is that right?");

    private static final String PROMPT = """
            You fill the form "send a parcel" of Yadony, a marketplace where people sending parcels find \
            travellers with spare luggage kilos. The user is the sender.
            Draft fields (all may be null):
            - departureCity, arrivalCity: city names as said.
            - desiredDate: yyyy-MM-dd. dateToleranceDays: 0 to 7 days of flexibility around it; "vers le 20" or \
            "around the 20th" without a number -> 3, and confirm it in "question"; "exactement" -> 0.
            - transportMode: PLANE, CAR, TRAIN, BUS, BOAT or OTHER, only if the user says it; never ask for it.
            - weightKg: 0.5 to 32. categories: 1 to 5 short content labels ("Vêtements", "Médicaments").
            - description: a short free note, only if the user dictates one.
            - pickupNeighborhood, deliveryNeighborhood: neighbourhoods if said; never ask for them.
            - negotiable: true if the budget is negotiable.
            - acceptedPaymentMethods: list of STRIPE (card), CASH, MOBILE_MONEY.
            - totalBudget: number only, in currency (ISO code). budgetDeclined: true when the user says they do not \
            know or have no budget.
            Do not ask for photos or a promo code: the user adds them in the form.
            """;

    @Override
    public FormKind kind() {
        return FormKind.PACKAGE_REQUEST;
    }

    @Override
    public List<String> keys() {
        return KEYS;
    }

    @Override
    public String systemPrompt(DraftContext ctx) {
        return PROMPT + FormDraftPrompts.common(ctx);
    }

    @Override
    public Map<String, Object> sanitize(Map<String, ?> raw, DraftContext ctx) {
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "departureCity", DraftFields.text(raw.get("departureCity"), 2, 100));
        put(d, "arrivalCity", DraftFields.text(raw.get("arrivalCity"), 2, 100));
        put(d, "desiredDate", DraftFields.date(raw.get("desiredDate"), ctx.today(), 365));
        put(d, "dateToleranceDays", DraftFields.integer(raw.get("dateToleranceDays"), 0, 7));
        put(d, "transportMode", DraftFields.enumName(raw.get("transportMode"), TransportMode.class));
        BigDecimal kg = DraftFields.decimal(raw.get("weightKg"), new BigDecimal("0.5"), BigDecimal.valueOf(32));
        put(d, "weightKg", kg);
        put(d, "parcelSize", kg == null ? null : ParcelSize.fromWeightKg(kg).name());
        List<String> categories = DraftFields.texts(raw.get("categories"), 5, 2, 40);
        if (categories != null && String.join(", ", categories).length() <= MAX_CATEGORIES_LENGTH) {
            d.put("categories", categories);
        }
        put(d, "description", DraftFields.text(raw.get("description"), 1, 500));
        put(d, "pickupNeighborhood", DraftFields.text(raw.get("pickupNeighborhood"), 1, 100));
        put(d, "deliveryNeighborhood", DraftFields.text(raw.get("deliveryNeighborhood"), 1, 100));
        put(d, "negotiable", DraftFields.bool(raw.get("negotiable")));
        put(d, "acceptedPaymentMethods", DraftFields.subset(raw.get("acceptedPaymentMethods"), PAYMENT_METHODS));
        put(d, "totalBudget", DraftFields.decimal(raw.get("totalBudget"), BigDecimal.ZERO, BigDecimal.valueOf(1_000_000)));
        put(d, "currency", DraftFields.currency(raw.get("currency")));
        put(d, "budgetDeclined", DraftFields.bool(raw.get("budgetDeclined")));
        if (d.get("departureCity") instanceof String from && d.get("arrivalCity") instanceof String to
                && from.equalsIgnoreCase(to)) {
            d.remove("arrivalCity");
        }
        return d;
    }

    @Override
    public List<String> missing(Map<String, Object> d) {
        List<String> m = new ArrayList<>();
        for (String k : REQUIRED) {
            if (!d.containsKey(k)) m.add(k);
        }
        if (!d.containsKey("acceptedPaymentMethods")) m.add("paymentMethod");
        if (!d.containsKey("totalBudget") && !Boolean.TRUE.equals(d.get("budgetDeclined"))) m.add("budget");
        return m;
    }

    @Override
    public List<String> understood(Map<String, Object> d) {
        List<String> u = new ArrayList<>();
        if (d.containsKey("departureCity") && d.containsKey("arrivalCity")) u.add("route");
        if (d.containsKey("desiredDate")) u.add("date");
        if (d.containsKey("dateToleranceDays")) u.add("flexibility");
        if (d.containsKey("weightKg")) u.add("weight");
        if (d.containsKey("categories")) u.add("content");
        if (d.containsKey("totalBudget")) u.add("budget");
        if (d.containsKey("acceptedPaymentMethods")) u.add("payment");
        return u;
    }

    @Override
    public String fallbackQuestion(String field, String locale) {
        String lang = "en".equals(locale) ? "en" : "fr";
        String q = field == null ? null : FALLBACK.get(lang).get(field);
        return q != null ? q : CONFIRM.get(lang);
    }

    private static void put(Map<String, Object> d, String key, Object value) {
        if (value != null) d.put(key, value);
    }
}
