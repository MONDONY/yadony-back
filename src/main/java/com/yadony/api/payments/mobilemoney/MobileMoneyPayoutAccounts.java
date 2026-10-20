package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Lecture des comptes de versement mobile money d'un voyageur (un par code opérateur pawaPay),
 * routage du versement, verrou des comptes et résumé dénormalisé sur {@link UserEntity}.
 */
@Component
public class MobileMoneyPayoutAccounts {

    /** Où verser : le compte choisi, son numéro en clair (jamais loggé) et son propre code. */
    public record PayoutDestination(UUID accountId, String msisdn, String provider, String country) {
        @Override
        public String toString() {
            return "PayoutDestination[accountId=" + accountId + ", provider=" + provider + ", country=" + country + "]";
        }
    }

    private final MobileMoneyPayoutAccountRepository repository;
    private final MessagesResolver messagesResolver;

    public MobileMoneyPayoutAccounts(MobileMoneyPayoutAccountRepository repository, MessagesResolver messagesResolver) {
        this.repository = repository;
        this.messagesResolver = messagesResolver;
    }

    /** Comptes vivants, le plus ancien d'abord. */
    public List<MobileMoneyPayoutAccountEntity> active(UUID userId) {
        return repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId);
    }

    /** Comptes vivants dans cette devise (insensible à la casse), le plus ancien d'abord. */
    public List<MobileMoneyPayoutAccountEntity> activeIn(UUID userId, String currency) {
        if (currency == null) {
            return List.of();
        }
        return active(userId).stream().filter(a -> currency.equalsIgnoreCase(a.getCurrency())).toList();
    }

    /** Le voyageur a-t-il un compte de ce code exact (insensible à la casse) dans cette devise ? */
    public boolean accepts(UUID userId, String providerCode, String currency) {
        return providerCode != null && acceptedCodes(userId, currency).contains(providerCode.toUpperCase(Locale.ROOT));
    }

    /**
     * Codes (en majuscules) des comptes vivants dans cette devise : à charger une fois avant de filtrer
     * un catalogue d'opérateurs, plutôt qu'un {@link #accepts} (une requête) par option.
     */
    public Set<String> acceptedCodes(UUID userId, String currency) {
        return activeIn(userId, currency).stream().map(a -> a.getProvider().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Numéro à utiliser pour PAYER dans cette devise : celui du plus ancien compte vivant de cette
     * devise, ou vide. Le compte a été vérifié auprès de l'opérateur à son ajout : c'est le numéro le
     * plus sûr pour un dépôt, avant le numéro saisi à la demande ou le téléphone du compte (souvent
     * étranger, par exemple +33, et refusé par pawaPay, Sentry FLUTTER-DE). Jamais le numéro d'un
     * compte d'une autre devise (un expéditeur XOF + XAF paie en XAF avec son numéro XAF).
     */
    public Optional<String> payerMsisdnIn(UUID userId, String currency) {
        return activeIn(userId, currency).stream().findFirst().map(MobileMoneyPayoutAccountEntity::getMsisdn);
    }

    /**
     * Libellés des réseaux où le voyageur a un compte dans cette devise (« Orange Money », « Wave »),
     * dédoublonnés, le plus ancien compte d'abord. Une même marque présente dans deux pays est
     * suffixée du code pays (« Orange Money (SN) », « Orange Money (CI) »).
     */
    public List<String> acceptedLabels(UUID userId, String currency) {
        return labelsOf(activeIn(userId, currency), null);
    }

    /**
     * Motif du refus d'un dépôt payé sur {@code providerCode} : vide si le voyageur a un compte de ce
     * code exact dans la devise du paiement, sinon le détail du 422 du payeur. Quand le voyageur a la
     * même marque dans un autre pays, les deux pays sont nommés pour que le message ne se contredise pas.
     */
    public Optional<String> payerRefusal(UUID userId, String providerCode, String providerLabel,
                                         String countryAlpha2, String currency) {
        if (accepts(userId, providerCode, currency)) {
            return Optional.empty();
        }
        List<MobileMoneyPayoutAccountEntity> accounts = activeIn(userId, currency);
        if (accounts.isEmpty()) {
            return Optional.of(messagesResolver.forRequest().get("problem.mobile-money-payer.no-network"));
        }
        String brand = PawapayProviders.brand(providerCode);
        boolean sameBrandElsewhere = brand != null
                && accounts.stream().anyMatch(a -> brand.equals(PawapayProviders.brand(a.getProvider())));
        String rejected = sameBrandElsewhere && countryAlpha2 != null
                ? providerLabel + " (" + countryAlpha2.toUpperCase(Locale.ROOT) + ")" : providerLabel;
        return Optional.of(messagesResolver.forRequest().get("problem.mobile-money-payer.network-refused", rejected,
                String.join(", ", labelsOf(accounts, sameBrandElsewhere ? brand : null))));
    }

    /**
     * @param alwaysSuffixBrand marque dont le pays est toujours précisé (celle du dépôt refusé), ou null.
     */
    private static List<String> labelsOf(List<MobileMoneyPayoutAccountEntity> accounts, String alwaysSuffixBrand) {
        return accounts.stream().map(a -> {
            String brand = PawapayProviders.brand(a.getProvider());
            long countries = accounts.stream()
                    .filter(o -> Objects.equals(brand, PawapayProviders.brand(o.getProvider())))
                    .map(o -> o.getCountry().toUpperCase(Locale.ROOT)).distinct().count();
            String label = PawapayProviders.label(a.getProvider());
            return countries > 1 || brand != null && brand.equals(alwaysSuffixBrand)
                    ? label + " (" + a.getCountry().toUpperCase(Locale.ROOT) + ")" : label;
        }).distinct().toList();
    }

    /**
     * Compte de versement d'un colis payé par un dépôt {@code depositProvider} : le compte du même
     * code dans la devise du paiement ; à défaut le compte repris par la migration dans cette devise
     * (son numéro et son propre code, jamais le code du dépôt) ; sinon vide.
     */
    public Optional<PayoutDestination> destinationFor(UUID userId, String depositProvider, String currency) {
        List<MobileMoneyPayoutAccountEntity> inCurrency = activeIn(userId, currency);
        Optional<MobileMoneyPayoutAccountEntity> sameCode = depositProvider == null || depositProvider.isBlank()
                ? Optional.empty()
                : inCurrency.stream().filter(a -> depositProvider.trim().equalsIgnoreCase(a.getProvider())).findFirst();
        return sameCode
                .or(() -> inCurrency.stream().filter(MobileMoneyPayoutAccountEntity::isMigrated).findFirst())
                .map(a -> new PayoutDestination(a.getId(), a.getMsisdn(), a.getProvider(), a.getCountry()));
    }

    /**
     * Colis qui attendent leur versement sur ce compte : verrou du retrait et du changement de numéro,
     * et {@code pendingPayouts} de la vue. Ceux payés sur son propre code ; pour le compte repris par la
     * migration, repli de versement ({@link #destinationFor}), aussi ceux payés sur un des réseaux
     * cochés sur l'ancien compte ({@code legacyAcceptedProviders}) qui n'a pas de compte vivant à lui.
     * Un colis tenu par plusieurs de ces codes ne compte qu'une fois.
     */
    public int pendingPayouts(MobileMoneyPayoutAccountEntity account) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        codes.add(account.getProvider().toUpperCase(Locale.ROOT));
        if (account.isMigrated() && account.getLegacyAcceptedProviders() != null) {
            Set<String> liveCodes = active(account.getUserId()).stream()
                    .map(a -> a.getProvider().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            for (String raw : account.getLegacyAcceptedProviders().split(",")) {
                String code = raw.trim().toUpperCase(Locale.ROOT);
                if (PawapayProviders.isWellFormed(code) && !liveCodes.contains(code)) {
                    codes.add(code);
                }
            }
        }
        return repository.countParcelsAwaitingPayout(account.getUserId(), List.copyOf(codes));
    }

    /**
     * Recalcule le résumé porté par l'utilisateur depuis ses comptes vivants. À appeler dans la
     * transaction de chaque écriture sur les comptes, avant {@code userRepository.save}. Le compte le
     * plus ancien fournit les champs historiques (numéro, code, pays, devise) ; les listes portent tous
     * les codes et toutes les devises. Sans compte : {@code DISABLED} si un compte a existé, sinon
     * {@code NOT_CONFIGURED} inchangé.
     */
    public void syncSummary(UserEntity user) {
        List<MobileMoneyPayoutAccountEntity> accounts = active(user.getId());
        if (accounts.isEmpty()) {
            if (user.getMobileMoneyStatus() != MobileMoneyPayoutStatus.NOT_CONFIGURED) {
                user.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED);
            }
            user.setMobileMoneyProviderList(List.of());
            user.setMobileMoneyCurrencyList(List.of());
            return;
        }
        MobileMoneyPayoutAccountEntity primary = accounts.get(0);
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyProvider(primary.getProvider());
        user.setMobileMoneyMsisdn(primary.getMsisdn());
        user.setMobileMoneyMsisdnMasked(primary.getMsisdnMasked());
        user.setMobileMoneyCountry(primary.getCountry());
        user.setMobileMoneyCurrency(primary.getCurrency());
        user.setMobileMoneyVerifiedAt(primary.getVerifiedAt());
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        LinkedHashSet<String> currencies = new LinkedHashSet<>();
        for (MobileMoneyPayoutAccountEntity a : accounts) {
            providers.add(a.getProvider());
            currencies.add(a.getCurrency());
        }
        user.setMobileMoneyProviderList(List.copyOf(providers));
        user.setMobileMoneyCurrencyList(List.copyOf(currencies));
    }
}
