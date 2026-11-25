package com.yadony.api.matching;

import com.yadony.api.matching.dto.PriceSuggestionResponse;
import com.yadony.api.matching.dto.PriceSuggestionResponse.ItemPriceSuggestion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prix conseillés à l'étape « Prix » de la création d'un trajet : la médiane de ce que les
 * voyageurs demandent déjà sur la même route. Le voyageur part d'un prix crédible au lieu
 * d'un champ vide, ce qui supprime les allers-retours observés sur l'écran des prix.
 *
 * <p>Seuls les trajets réellement mis en ligne comptent (pas les brouillons, ni les trajets
 * annulés ou retirés), partis il y a au plus {@link #WINDOW_DAYS} jours ou à venir, et dans
 * la devise demandée : comparer des euros à des francs CFA n'aurait pas de sens.
 */
@Service
public class PriceSuggestionService {

    /** Fenêtre glissante : les prix d'il y a un an ne disent rien des prix d'aujourd'hui. */
    static final int WINDOW_DAYS = 180;
    /** En dessous, la médiane d'un ou deux trajets serait le prix d'un seul voyageur. */
    static final int MIN_KG_SAMPLES = 3;
    /** Un article doit avoir un prix sur au moins deux trajets pour être proposé. */
    static final int MIN_ITEM_SAMPLES = 2;
    static final int MAX_ITEMS = 4;

    static final Set<AnnouncementStatus> COUNTED_STATUSES = EnumSet.of(
            AnnouncementStatus.ACTIVE,
            AnnouncementStatus.FULL,
            AnnouncementStatus.IN_PROGRESS,
            AnnouncementStatus.COMPLETED);

    @PersistenceContext
    private EntityManager em;

    private final Clock clock;

    public PriceSuggestionService(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PriceSuggestionResponse suggest(String departureCity, String arrivalCity, String currency) {
        String from = normalize(departureCity);
        String to = normalize(arrivalCity);
        String cur = currency.trim().toUpperCase(Locale.ROOT);
        LocalDate since = LocalDate.now(clock).minusDays(WINDOW_DAYS);

        List<BigDecimal> kgPrices = em.createQuery("""
                SELECT a.pricePerKg FROM AnnouncementEntity a
                WHERE lower(trim(a.departureCity)) = :from
                  AND lower(trim(a.arrivalCity)) = :to
                  AND a.currency = :currency
                  AND a.status IN :statuses
                  AND a.departureDate >= :since
                  AND a.pricePerKg IS NOT NULL
                  AND a.pricePerKg > 0
                """, BigDecimal.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("currency", cur)
                .setParameter("statuses", COUNTED_STATUSES)
                .setParameter("since", since)
                .getResultList();

        List<Object[]> itemRows = em.createQuery("""
                SELECT g.label, g.unitPriceNet, a.id FROM AnnouncementPriceGridItemEntity g, AnnouncementEntity a
                WHERE g.announcementId = a.id
                  AND lower(trim(a.departureCity)) = :from
                  AND lower(trim(a.arrivalCity)) = :to
                  AND a.currency = :currency
                  AND a.status IN :statuses
                  AND a.departureDate >= :since
                  AND g.unitPriceNet > 0
                """, Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("currency", cur)
                .setParameter("statuses", COUNTED_STATUSES)
                .setParameter("since", since)
                .getResultList();

        BigDecimal kgMedian = kgPrices.size() >= MIN_KG_SAMPLES ? roundToUnit(median(kgPrices)) : null;
        return new PriceSuggestionResponse(cur, kgMedian, kgPrices.size(), itemSuggestions(itemRows));
    }

    /**
     * Regroupe les articles par libellé sans tenir compte de la casse ni des espaces, garde
     * l'écriture la plus fréquente, et ne compte chaque article qu'une fois par trajet.
     */
    static List<ItemPriceSuggestion> itemSuggestions(List<Object[]> rows) {
        Map<String, ItemBucket> buckets = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String label = row[0] == null ? "" : row[0].toString().trim();
            if (label.isEmpty() || row[1] == null) continue;
            ItemBucket bucket = buckets.computeIfAbsent(label.toLowerCase(Locale.ROOT), k -> new ItemBucket());
            bucket.add(label, (BigDecimal) row[1], row[2]);
        }
        List<ItemPriceSuggestion> out = new ArrayList<>();
        buckets.values().stream()
                .filter(b -> b.trips.size() >= MIN_ITEM_SAMPLES)
                .sorted(Comparator.comparingInt((ItemBucket b) -> b.trips.size()).reversed()
                        .thenComparing(ItemBucket::displayLabel))
                .limit(MAX_ITEMS)
                .forEach(b -> out.add(new ItemPriceSuggestion(
                        b.displayLabel(), roundToUnit(median(b.prices)), b.trips.size())));
        return out;
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal roundToUnit(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    static String normalize(String city) {
        return city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ItemBucket {
        private final Map<String, Integer> spellings = new LinkedHashMap<>();
        private final Map<Object, BigDecimal> trips = new LinkedHashMap<>();
        private final List<BigDecimal> prices = new ArrayList<>();

        void add(String label, BigDecimal price, Object tripId) {
            spellings.merge(label, 1, Integer::sum);
            // Un trajet qui liste deux fois le même article ne compte qu'une fois (premier prix).
            if (trips.putIfAbsent(tripId, price) == null) {
                prices.add(price);
            }
        }

        String displayLabel() {
            return spellings.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey)
                    .orElse("");
        }
    }
}
