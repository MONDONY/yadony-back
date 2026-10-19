package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    public MobileMoneyPayoutAccounts(MobileMoneyPayoutAccountRepository repository) {
        this.repository = repository;
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
        if (providerCode == null) {
            return false;
        }
        return activeIn(userId, currency).stream().anyMatch(a -> providerCode.equalsIgnoreCase(a.getProvider()));
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

    /** Colis payés sur ce code qui attendent leur versement : verrou du retrait et du changement de numéro. */
    public int pendingPayouts(UUID userId, String providerCode) {
        return repository.countParcelsAwaitingPayout(userId, providerCode);
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
