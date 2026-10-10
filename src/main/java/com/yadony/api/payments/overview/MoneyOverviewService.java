package com.yadony.api.payments.overview;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.overview.dto.MoneyItemDto;
import com.yadony.api.payments.overview.dto.MoneyOverviewResponse;
import com.yadony.api.payments.overview.dto.SenderTotalDto;
import com.yadony.api.payments.overview.dto.TravelerTotalDto;
import com.yadony.api.payments.overview.dto.WalletBalanceLineDto;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aperçu « Mon argent » (FLUTTER-HV) : soldes du portefeuille, montants à venir ou en séquestre
 * par colis, côté voyageur et côté expéditeur. Lecture seule ; l'utilisateur ne voit que les colis
 * dont il est le voyageur (annonce) ou l'expéditeur (bid) — le filtre est dans la requête
 * ({@link MoneyOverviewReadModel}), jamais sur un identifiant fourni par le client.
 */
@Service
public class MoneyOverviewService {

    /** Fenêtre des montants « libérés / remboursés récemment ». */
    static final int RECENT_WINDOW_DAYS = 30;

    /** États qui comptent dans « à recevoir » du voyageur. */
    private static final Set<MoneyState> TRAVELER_UPCOMING = EnumSet.of(
            MoneyState.ESCROWED, MoneyState.AWAITING_DELIVERY_CONFIRMATION, MoneyState.RELEASE_SCHEDULED,
            MoneyState.IN_DISPUTE, MoneyState.ON_HOLD, MoneyState.PAYOUT_IN_PROGRESS);

    /** États où l'argent payé par l'expéditeur est encore bloqué en séquestre. */
    private static final Set<MoneyState> SENDER_BLOCKED = EnumSet.of(
            MoneyState.ESCROWED, MoneyState.AWAITING_DELIVERY_CONFIRMATION, MoneyState.RELEASE_SCHEDULED,
            MoneyState.IN_DISPUTE, MoneyState.ON_HOLD);

    /** Ordre d'affichage : ce qui attend une action ou une date d'abord, l'historique ensuite. */
    private static final List<MoneyState> DISPLAY_ORDER = List.of(
            MoneyState.IN_DISPUTE, MoneyState.ON_HOLD, MoneyState.RELEASE_SCHEDULED,
            MoneyState.AWAITING_DELIVERY_CONFIRMATION, MoneyState.ESCROWED, MoneyState.PAYOUT_IN_PROGRESS,
            MoneyState.REFUND_PENDING, MoneyState.CASH, MoneyState.RELEASED_RECENTLY, MoneyState.REFUNDED_RECENTLY);

    private final MoneyOverviewReadModel readModel;
    private final WalletAccountRepository walletAccountRepository;
    private final UserRepository userRepository;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public MoneyOverviewService(MoneyOverviewReadModel readModel,
                                WalletAccountRepository walletAccountRepository,
                                UserRepository userRepository,
                                ActiveCurrencyResolver activeCurrencyResolver) {
        this(readModel, walletAccountRepository, userRepository, activeCurrencyResolver, Clock.systemUTC());
    }

    MoneyOverviewService(MoneyOverviewReadModel readModel,
                         WalletAccountRepository walletAccountRepository,
                         UserRepository userRepository,
                         ActiveCurrencyResolver activeCurrencyResolver,
                         Clock clock) {
        this.readModel = readModel;
        this.walletAccountRepository = walletAccountRepository;
        this.userRepository = userRepository;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MoneyOverviewResponse overview(String firebaseUid) {
        UUID userId = userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Utilisateur introuvable", ""));
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime since = now.minusDays(RECENT_WINDOW_DAYS);

        List<MoneyRow> fetched = readModel.findRows(userId, since);
        boolean truncated = fetched.size() > MoneyOverviewReadModel.MAX_ROWS;
        List<MoneyRow> rows = truncated ? fetched.subList(0, MoneyOverviewReadModel.MAX_ROWS) : fetched;
        Map<UUID, String> names = counterpartyNames(rows);

        List<MoneyItemDto> travelerItems = new ArrayList<>();
        List<MoneyItemDto> senderItems = new ArrayList<>();
        for (MoneyRow row : rows) {
            Optional<MoneyStateResolver.Resolution> resolved = MoneyStateResolver.resolve(row);
            if (resolved.isEmpty()) {
                continue;
            }
            String name = row.counterpartyId() == null ? null : names.get(row.counterpartyId());
            MoneyItemDto item = toItem(row, resolved.get(), name);
            (row.role() == MoneyRole.TRAVELER ? travelerItems : senderItems).add(item);
        }
        travelerItems.sort(DISPLAY);
        senderItems.sort(DISPLAY);

        String activeCurrency = activeCurrencyResolver.resolve(userId).toUpperCase(Locale.ROOT);
        return new MoneyOverviewResponse(
                now,
                RECENT_WINDOW_DAYS,
                walletBalances(userId, activeCurrency),
                new MoneyOverviewResponse.RoleSection<>(travelerTotals(travelerItems), travelerItems),
                new MoneyOverviewResponse.RoleSection<>(senderTotals(senderItems), senderItems),
                activeCurrency,
                truncated);
    }

    private static final Comparator<MoneyItemDto> DISPLAY = Comparator
            .comparingInt((MoneyItemDto i) -> DISPLAY_ORDER.indexOf(i.state()))
            .thenComparing(MoneyItemDto::releaseAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MoneyItemDto::settledAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MoneyItemDto::departureDate, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Soldes du portefeuille, devise active en tête (FLUTTER-J4) : l'écran met en avant la
     * première ligne, et l'ordre alphabétique montrait « 0 CA$ » à un utilisateur en USD. Les
     * autres devises suivent par solde décroissant, puis par code.
     *
     * <p>Devise active lue comme {@code GET /wallet/balance} ({@link ActiveCurrencyResolver}, même
     * repli sur le pays que {@code UserBusinessPrefsService.getPrefs}). Ce dernier crée à zéro le
     * portefeuille de la devise active s'il manque : on reproduit la même ligne à 0 en tête, sans
     * rien écrire (lecture seule).
     */
    private List<WalletBalanceLineDto> walletBalances(UUID userId, String activeCurrency) {
        List<WalletBalanceLineDto> lines = new ArrayList<>(walletAccountRepository.findAllByUserId(userId).stream()
                .map(w -> new WalletBalanceLineDto(w.getCurrency().toUpperCase(Locale.ROOT), w.getBalance()))
                .toList());
        if (lines.stream().noneMatch(l -> l.currency().equals(activeCurrency))) {
            lines.add(new WalletBalanceLineDto(activeCurrency, BigDecimal.ZERO));
        }
        lines.sort(Comparator
                .comparing((WalletBalanceLineDto l) -> !l.currency().equals(activeCurrency))
                .thenComparing(WalletBalanceLineDto::balance, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WalletBalanceLineDto::currency));
        return List.copyOf(lines);
    }

    /** Prénom + initiale de la contrepartie, en une requête pour toutes les lignes. */
    private Map<UUID, String> counterpartyNames(List<MoneyRow> rows) {
        Set<UUID> ids = new HashSet<>();
        for (MoneyRow row : rows) {
            if (row.counterpartyId() != null) {
                ids.add(row.counterpartyId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .filter(u -> u.publicDisplayName() != null)
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::publicDisplayName, (a, b) -> a));
    }

    private static MoneyItemDto toItem(MoneyRow row, MoneyStateResolver.Resolution r, String counterpartyName) {
        return new MoneyItemDto(
                row.bidId(), row.trackingNumber(), row.announcementId(), row.role(),
                row.departureCity(), row.arrivalCity(), row.departureDate(),
                r.amount(), r.currency(), channel(row), r.state(), r.condition(),
                r.releaseAt(), r.settledAt(), counterpartyName, row.bidStatus(), row.weightKg(),
                r.state() == MoneyState.CASH ? row.commissionStatus() : null,
                row.arrivalDate());
    }

    static MoneyChannel channel(MoneyRow row) {
        if (!row.hasPayment()) {
            return MoneyChannel.CASH;
        }
        return "PAWAPAY".equals(row.rail()) ? MoneyChannel.MOBILE_MONEY : MoneyChannel.CARD;
    }

    private static List<TravelerTotalDto> travelerTotals(List<MoneyItemDto> items) {
        Map<String, BigDecimal> upcoming = sum(items, i -> TRAVELER_UPCOMING.contains(i.state()));
        Map<String, BigDecimal> released = sum(items, i -> i.state() == MoneyState.RELEASED_RECENTLY);
        return currencies(upcoming, released).stream()
                .map(c -> new TravelerTotalDto(c, upcoming.getOrDefault(c, BigDecimal.ZERO),
                        released.getOrDefault(c, BigDecimal.ZERO)))
                .toList();
    }

    private static List<SenderTotalDto> senderTotals(List<MoneyItemDto> items) {
        Map<String, BigDecimal> blocked = sum(items, i -> SENDER_BLOCKED.contains(i.state()));
        Map<String, BigDecimal> refundPending = sum(items, i -> i.state() == MoneyState.REFUND_PENDING);
        Map<String, BigDecimal> refunded = sum(items, i -> i.state() == MoneyState.REFUNDED_RECENTLY);
        return currencies(blocked, refundPending, refunded).stream()
                .map(c -> new SenderTotalDto(c, blocked.getOrDefault(c, BigDecimal.ZERO),
                        refundPending.getOrDefault(c, BigDecimal.ZERO), refunded.getOrDefault(c, BigDecimal.ZERO)))
                .toList();
    }

    /** Somme par devise des montants connus (les colis en espèces, sans montant, n'y entrent pas). */
    private static Map<String, BigDecimal> sum(List<MoneyItemDto> items,
                                               java.util.function.Predicate<MoneyItemDto> filter) {
        return items.stream()
                .filter(filter)
                .filter(i -> i.amount() != null && i.currency() != null)
                .collect(Collectors.groupingBy(MoneyItemDto::currency, TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, MoneyItemDto::amount, BigDecimal::add)));
    }

    @SafeVarargs
    private static List<String> currencies(Map<String, BigDecimal>... maps) {
        return java.util.Arrays.stream(maps)
                .flatMap(m -> m.keySet().stream())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
