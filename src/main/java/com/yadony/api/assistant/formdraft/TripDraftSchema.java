package com.yadony.api.assistant.formdraft;

import com.yadony.api.matching.TransportMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formulaire « Proposer un trajet » (spec §3.5). Le transport n'est pas requis : le formulaire de l'app ne
 * propose que l'avion et le fixe lui-même.
 */
@Component
public class TripDraftSchema implements FormDraftSchema {

    static final String SUITCASE_23 = "SUITCASE_23KG";
    static final String SUITCASE_32 = "SUITCASE_32KG";
    private static final Set<String> CAPACITY_UNITS = Set.of(SUITCASE_23, SUITCASE_32, "KG_FREE", "KG_EXACT");
    private static final Set<String> PRICING_MODES = Set.of("KG", "MIXED");
    private static final BigDecimal KG_23 = BigDecimal.valueOf(23);
    private static final BigDecimal KG_32 = BigDecimal.valueOf(32);
    private static final List<String> KEYS = List.of("departureCity", "departureCountryCode", "arrivalCity",
            "arrivalCountryCode", "departureDate", "departureTime", "transportMode", "direct", "availableKg",
            "capacityUnit", "pricingMode", "pricePerKg", "currency", "cardEnabled", "cashEnabled",
            "mobileMoneyEnabled", "negotiable", "description");
    private static final List<String> REQUIRED = List.of("departureCity", "arrivalCity", "departureDate",
            "departureTime", "availableKg");
    private static final Map<String, Map<String, String>> FALLBACK = Map.of(
            "fr", Map.of(
                    "departureCity", "D'où partez-vous ?",
                    "arrivalCity", "Où allez-vous ?",
                    "departureDate", "Quel jour partez-vous ?",
                    "departureTime", "À quelle heure partez-vous ?",
                    "availableKg", "Combien de kilos pouvez-vous prendre ?",
                    "price", "Quel est votre prix au kilo ?",
                    "paymentMethod", "Comment voulez-vous être payé : carte, espèces ou mobile money ?"),
            "en", Map.of(
                    "departureCity", "Where are you leaving from?",
                    "arrivalCity", "Where are you going?",
                    "departureDate", "What day do you leave?",
                    "departureTime", "What time do you leave?",
                    "availableKg", "How many kilos can you carry?",
                    "price", "What is your price per kilo?",
                    "paymentMethod", "How do you want to be paid: card, cash or mobile money?"));
    private static final Map<String, String> CONFIRM = Map.of("fr", "Est-ce bien cela ?", "en", "Is that right?");

    private static final String PROMPT = """
            You fill the form "publish a trip" of Yadony, a marketplace where travellers sell spare luggage \
            kilos to people sending parcels. The user is the traveller.
            Draft fields (all may be null):
            - departureCity, arrivalCity: city names as said; departureCountryCode, arrivalCountryCode: ISO-2.
            - departureDate: yyyy-MM-dd; departureTime: HH:mm (24 h).
            - transportMode: PLANE, CAR, TRAIN, BUS, BOAT or OTHER, only if the user says it; never ask for it.
            - direct: true when the user says without stopover ("sans escale", "direct"), false when they mention one.
            - availableKg: number of kilos offered. capacityUnit: SUITCASE_23KG for 23 kg or "a 23 kg suitcase", \
            SUITCASE_32KG for 32 kg, KG_EXACT for any other weight, KG_FREE only if the user says the weight is free.
            - pricingMode: MIXED when the user wants their item price grid ("ma grille", "mes articles"), else KG.
            - pricePerKg: price per kilo, number only; currency: ISO code.
            - cardEnabled, cashEnabled, mobileMoneyEnabled: accepted payment methods ("la carte", "l'espèce", \
            "mobile money"); a method not mentioned stays null.
            - negotiable: true if the price is negotiable. description: a short free note, only if the user dictates one.
            Do not ask for addresses, handover deadline or trip legs: the user fills them in the form.
            """;

    @Override
    public FormKind kind() {
        return FormKind.TRIP;
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
        put(d, "departureCity", DraftFields.text(raw.get("departureCity"), 2, 80));
        put(d, "departureCountryCode", DraftFields.country(raw.get("departureCountryCode")));
        put(d, "arrivalCity", DraftFields.text(raw.get("arrivalCity"), 2, 80));
        put(d, "arrivalCountryCode", DraftFields.country(raw.get("arrivalCountryCode")));
        put(d, "departureDate", DraftFields.date(raw.get("departureDate"), ctx.today(), 365));
        put(d, "departureTime", DraftFields.time(raw.get("departureTime")));
        put(d, "transportMode", DraftFields.enumName(raw.get("transportMode"), TransportMode.class));
        put(d, "direct", DraftFields.bool(raw.get("direct")));
        BigDecimal kg = DraftFields.decimal(raw.get("availableKg"), BigDecimal.ONE, BigDecimal.valueOf(200));
        put(d, "availableKg", kg);
        put(d, "capacityUnit", capacityUnit(DraftFields.oneOf(raw.get("capacityUnit"), CAPACITY_UNITS), kg));
        put(d, "pricingMode", DraftFields.oneOf(raw.get("pricingMode"), PRICING_MODES));
        put(d, "pricePerKg", DraftFields.decimal(raw.get("pricePerKg"), BigDecimal.ZERO, BigDecimal.valueOf(1_000_000)));
        put(d, "currency", DraftFields.currency(raw.get("currency")));
        put(d, "cardEnabled", DraftFields.bool(raw.get("cardEnabled")));
        put(d, "cashEnabled", DraftFields.bool(raw.get("cashEnabled")));
        put(d, "mobileMoneyEnabled", DraftFields.bool(raw.get("mobileMoneyEnabled")));
        put(d, "negotiable", DraftFields.bool(raw.get("negotiable")));
        put(d, "description", DraftFields.text(raw.get("description"), 1, 500));
        if (d.get("departureCity") instanceof String from && d.get("arrivalCity") instanceof String to
                && from.equalsIgnoreCase(to)) {
            d.remove("arrivalCity");
            d.remove("arrivalCountryCode");
        }
        return d;
    }

    /** 23 et 32 kg sont des valises ; une valise annoncée avec un autre poids devient un poids exact. */
    static String capacityUnit(String unit, BigDecimal kg) {
        if (kg == null) return unit;
        String expected = kg.compareTo(KG_23) == 0 ? SUITCASE_23 : kg.compareTo(KG_32) == 0 ? SUITCASE_32 : null;
        if (SUITCASE_23.equals(unit) || SUITCASE_32.equals(unit)) return unit.equals(expected) ? unit : "KG_EXACT";
        if (unit == null) return expected != null ? expected : "KG_EXACT";
        return unit;
    }

    @Override
    public List<String> missing(Map<String, Object> d) {
        List<String> m = new ArrayList<>();
        for (String k : REQUIRED) {
            if (!d.containsKey(k)) m.add(k);
        }
        if (!d.containsKey("pricePerKg") && !"MIXED".equals(d.get("pricingMode"))) m.add("price");
        if (!anyPayment(d)) m.add("paymentMethod");
        return m;
    }

    @Override
    public List<String> understood(Map<String, Object> d) {
        List<String> u = new ArrayList<>();
        if (d.containsKey("departureCity") && d.containsKey("arrivalCity")) u.add("route");
        if (d.containsKey("departureDate")) u.add("date");
        if (d.containsKey("departureTime")) u.add("time");
        if (d.containsKey("transportMode")) u.add("transport");
        if (d.containsKey("availableKg")) u.add("capacity");
        if (d.containsKey("pricePerKg")) u.add("price");
        if ("MIXED".equals(d.get("pricingMode"))) u.add("grid");
        if (anyPayment(d)) u.add("payment");
        if (Boolean.TRUE.equals(d.get("direct"))) u.add("direct");
        return u;
    }

    @Override
    public String fallbackQuestion(String field, String locale) {
        String lang = "en".equals(locale) ? "en" : "fr";
        String q = field == null ? null : FALLBACK.get(lang).get(field);
        return q != null ? q : CONFIRM.get(lang);
    }

    private static boolean anyPayment(Map<String, Object> d) {
        return Boolean.TRUE.equals(d.get("cardEnabled")) || Boolean.TRUE.equals(d.get("cashEnabled"))
                || Boolean.TRUE.equals(d.get("mobileMoneyEnabled"));
    }

    private static void put(Map<String, Object> d, String key, Object value) {
        if (value != null) d.put(key, value);
    }
}
