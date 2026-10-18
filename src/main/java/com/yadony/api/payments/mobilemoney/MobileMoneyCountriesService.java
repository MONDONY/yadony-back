package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.CountryOption;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.OperatorOption;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayCountries;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapayValidationHints;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Catalogue des pays mobile money pour le champ pays de l'app (autocomplétion) : les pays où un
 * opérateur est ouvert pour l'opération demandée, plus les pays annoncés « bientôt »
 * ({@code yadony.pawapay.upcoming-countries}). Lecture seule, sans donnée personnelle : la
 * configuration pawaPay vient du cache de {@link PawapayClient#activeConfiguration()}.
 */
@Service
public class MobileMoneyCountriesService {

    /** Ordre d'affichage des marques dans un pays ; une marque inconnue passe après, par libellé. */
    private static final List<String> BRAND_ORDER = List.of("ORANGE", "WAVE", "MTN", "MOOV", "AIRTEL", "FREE");

    private final PawapayClient client;
    private final PawapayProperties props;
    private final PawapayValidationHints hints;
    private final List<String> upcomingCountries;

    public MobileMoneyCountriesService(PawapayClient client, PawapayProperties props, PawapayValidationHints hints,
                                       @Value("${yadony.pawapay.upcoming-countries:ML}") String upcomingCountries) {
        this.client = client;
        this.props = props;
        this.hints = hints;
        this.upcomingCountries = parseCountries(upcomingCountries);
    }

    /**
     * @param kind {@link PawapayOperationKind#DEPOSIT} (recharge) ou {@link PawapayOperationKind#PAYOUT}
     *             (versement) ; toute autre valeur est traitée comme un dépôt.
     * @throws YadonyBusinessException 422 {@code mobile-money-disabled} si le rail est coupé,
     *         502 {@code mobile-money-provider-unavailable} si pawaPay ne répond pas
     */
    public MobileMoneyCountriesResponse countries(PawapayOperationKind kind) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        boolean payout = kind == PawapayOperationKind.PAYOUT;
        Map<String, PawapayProviderConfig> configs;
        try {
            configs = client.activeConfiguration();
        } catch (YadonyBusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw PawapayErrors.providerUnavailable("active-conf", "la liste des pays mobile money", e);
        }

        // TreeMap : pays triés par code alpha-2, réponse déterministe.
        Map<String, List<PawapayProviderConfig>> byCountry = new TreeMap<>();
        Map<String, String> currencyByCountry = new TreeMap<>();
        for (PawapayProviderConfig config : configs.values()) {
            if (config == null || !(payout ? config.supportsPayout() : config.supportsDeposit())) {
                continue;
            }
            String alpha2 = PawapayCountries.toAlpha2(config.countryAlpha3());
            if (alpha2 == null) {
                continue;
            }
            byCountry.computeIfAbsent(alpha2, k -> new ArrayList<>()).add(config);
            currencyByCountry.putIfAbsent(alpha2, config.currency());
        }

        List<CountryOption> available = new ArrayList<>();
        byCountry.forEach((alpha2, list) -> available.add(new CountryOption(alpha2, currencyByCountry.get(alpha2),
                list.stream()
                        .sorted(Comparator.comparingInt((PawapayProviderConfig c) -> brandRank(c.provider()))
                                .thenComparing(c -> PawapayProviders.label(c.provider()))
                                .thenComparing(PawapayProviderConfig::provider))
                        .map(c -> operator(c, payout))
                        .toList())));

        List<String> upcoming = upcomingCountries.stream().filter(c -> !byCountry.containsKey(c)).toList();
        return new MobileMoneyCountriesResponse(List.copyOf(available), upcoming);
    }

    private OperatorOption operator(PawapayProviderConfig config, boolean payout) {
        PawapayProviderConfig.Limits limits = payout ? config.payout() : config.deposit();
        return new OperatorOption(config.provider(), PawapayProviders.label(config.provider()),
                PawapayProviders.brand(config.provider()), hints.forProvider(config.provider()),
                limits.minAmount(), limits.maxAmount());
    }

    private static int brandRank(String provider) {
        int i = BRAND_ORDER.indexOf(PawapayProviders.brand(provider));
        return i < 0 ? BRAND_ORDER.size() : i;
    }

    /** Liste séparée par des virgules → alpha-2 majuscules, sans doublon ni valeur mal formée. */
    private static List<String> parseCountries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> s.matches("[A-Z]{2}"))
                .forEach(out::add);
        return List.copyOf(out);
    }
}
