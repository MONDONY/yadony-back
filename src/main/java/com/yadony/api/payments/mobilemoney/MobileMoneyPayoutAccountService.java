package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.ProviderView;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comptes de versement mobile money d'un voyageur : un compte actif par code opérateur pawaPay
 * (opérateur + pays), chacun avec son propre numéro (spec §6).
 *
 * <p>Chaque écriture prend d'abord le verrou pessimiste de l'utilisateur
 * ({@code findByIdForUpdate}), puis recalcule le résumé porté par {@link UserEntity}
 * ({@link MobileMoneyPayoutAccounts#syncSummary}) dans la même transaction, et trace l'action dans
 * {@code audit_log}. Le numéro n'apparaît jamais en clair hors de la colonne chiffrée : réponses,
 * logs, audit et messages d'erreur ne portent que sa forme masquée.
 *
 * <p>Un numéro n'est accepté pour un code que s'il figure au catalogue PAYOUT de pawaPay pour ce
 * numéro ET que pawaPay n'y détecte pas un autre opérateur : jamais un compte Orange sur un numéro
 * MTN (422 {@code mobile-money-account-wrong-network}). Retrait et changement de numéro sont refusés
 * (409 {@code mobile-money-account-locked}) tant qu'un colis payé sur ce code attend son versement.
 */
@Service
public class MobileMoneyPayoutAccountService {

    static final String ADDED = "MM_PAYOUT_ACCOUNT_ADDED";
    static final String NUMBER_CHANGED = "MM_PAYOUT_ACCOUNT_NUMBER_CHANGED";
    static final String REMOVED = "MM_PAYOUT_ACCOUNT_REMOVED";

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutAccountService.class);

    private final UserRepository userRepository;
    private final MobileMoneyPayoutAccountRepository repository;
    private final MobileMoneyPayoutAccounts accounts;
    private final PawapayProviderResolver providers;
    private final AuditService audit;
    private final PawapayProperties props;
    private final MessagesResolver messagesResolver;

    public MobileMoneyPayoutAccountService(UserRepository userRepository, MobileMoneyPayoutAccountRepository repository,
                                           MobileMoneyPayoutAccounts accounts, PawapayProviderResolver providers,
                                           AuditService audit, PawapayProperties props,
                                           MessagesResolver messagesResolver) {
        this.userRepository = userRepository;
        this.repository = repository;
        this.accounts = accounts;
        this.providers = providers;
        this.audit = audit;
        this.props = props;
        this.messagesResolver = messagesResolver;
    }

    /** Numéro et code validés par pawaPay : {@code msisdn} en clair, à ne jamais loguer. */
    private record Checked(String msisdn, String provider, String country, String currency) {
        @Override
        public String toString() {
            return "Checked[provider=" + provider + ", country=" + country + ", currency=" + currency + "]";
        }
    }

    /**
     * Comptes vivants (le plus ancien d'abord) avec leur verrou, et réseaux à suggérer : codes cochés
     * sur l'ancien compte unique (comptes repris, {@code legacyAcceptedProviders}) qui n'ont pas de
     * compte actif. Lecture seule, sans appel pawaPay.
     */
    @Transactional(readOnly = true)
    public MobileMoneyPayoutAccountsResponse list(UUID userId) {
        List<MobileMoneyPayoutAccountEntity> live = accounts.active(userId);
        Set<String> liveCodes = live.stream().map(a -> a.getProvider().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        LinkedHashSet<String> suggested = new LinkedHashSet<>();
        for (MobileMoneyPayoutAccountEntity a : live) {
            if (!a.isMigrated() || a.getLegacyAcceptedProviders() == null) {
                continue;
            }
            for (String raw : a.getLegacyAcceptedProviders().split(",")) {
                String code = raw.trim().toUpperCase(Locale.ROOT);
                if (PawapayProviders.isWellFormed(code) && !liveCodes.contains(code)) {
                    suggested.add(code);
                }
            }
        }
        return new MobileMoneyPayoutAccountsResponse(
                live.stream().map(a -> view(userId, a)).toList(),
                suggested.stream().map(c -> new ProviderView(c, PawapayProviders.label(c))).toList());
    }

    /**
     * Ajoute un compte pour {@code provider}. 409 {@code mobile-money-account-exists} si un compte
     * actif de ce code existe déjà (changer son numéro passe par {@link #changeNumber}).
     */
    @Transactional
    public AccountView add(UUID userId, String phoneNumber, String provider) {
        requireEnabled();
        Messages m = messagesResolver.forRequest();
        String msisdn = normalizePhone(phoneNumber, m);
        String code = cleanCode(userId, provider, true, m);
        UserEntity user = lockUser(userId);
        if (repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, code).isPresent()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-account-exists",
                    "Mobile Money Account Exists", m.get("problem.mobile-money-account.exists", PawapayProviders.label(code)));
        }
        return create(user, check(userId, msisdn, code, m), "provided");
    }

    /** Remplace le numéro d'un compte de l'appelant, l'opérateur restant celui du compte. */
    @Transactional
    public AccountView changeNumber(UUID userId, UUID accountId, String phoneNumber) {
        requireEnabled();
        Messages m = messagesResolver.forRequest();
        String msisdn = normalizePhone(phoneNumber, m);
        UserEntity user = lockUser(userId);
        MobileMoneyPayoutAccountEntity account = ownedAccount(userId, accountId, m);
        requireUnlocked(userId, account, m);
        return replaceNumber(user, account, check(userId, msisdn, account.getProvider(), m), "provided");
    }

    /**
     * Retire (soft delete) un compte de l'appelant. Permis même si pawaPay est coupé : aucun appel
     * pawaPay, aucun mouvement d'argent.
     */
    @Transactional
    public void remove(UUID userId, UUID accountId) {
        Messages m = messagesResolver.forRequest();
        UserEntity user = lockUser(userId);
        MobileMoneyPayoutAccountEntity account = ownedAccount(userId, accountId, m);
        requireUnlocked(userId, account, m);
        account.softDelete();
        repository.save(account);
        accounts.syncSummary(user);
        userRepository.save(user);
        audit.log("USER", userId, REMOVED, userId, Map.of("accountId", account.getId(),
                "provider", account.getProvider(), "country", account.getCountry(),
                "currency", account.getCurrency(), "msisdnMasked", account.getMsisdnMasked()));
    }

    /**
     * Ancien contrat {@code POST /payments/mobile-money/account} : crée le compte de ce code s'il n'y
     * en a pas d'actif, sinon remplace son numéro (verrou appliqué, jamais de 409 {@code exists}).
     * {@code provider} nul ou blanc : l'opérateur que pawaPay détecte pour le numéro.
     * {@code source} ({@code provided}, {@code firebase}) va dans l'audit.
     */
    @Transactional
    public AccountView upsert(UUID userId, String phoneNumber, String provider, String source) {
        requireEnabled();
        Messages m = messagesResolver.forRequest();
        String msisdn = normalizePhone(phoneNumber, m);
        String code = cleanCode(userId, provider, false, m);
        UserEntity user = lockUser(userId);
        Checked checked = check(userId, msisdn, code, m);
        String origin = Objects.requireNonNullElse(source, "provided");
        Optional<MobileMoneyPayoutAccountEntity> existing =
                repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, checked.provider());
        if (existing.isEmpty()) {
            return create(user, checked, origin);
        }
        requireUnlocked(userId, existing.get(), m);
        return replaceNumber(user, existing.get(), checked, origin);
    }

    // ── écritures ───────────────────────────────────────────────────────────

    private AccountView create(UserEntity user, Checked checked, String source) {
        MobileMoneyPayoutAccountEntity account = repository.save(new MobileMoneyPayoutAccountEntity(user.getId(),
                checked.provider(), checked.country(), checked.currency(), checked.msisdn(),
                Msisdn.mask(checked.msisdn()), Instant.now()));
        accounts.syncSummary(user);
        userRepository.save(user);
        audit.log("USER", user.getId(), ADDED, user.getId(), Map.of("accountId", account.getId(),
                "provider", account.getProvider(), "country", account.getCountry(), "currency", account.getCurrency(),
                "msisdnMasked", account.getMsisdnMasked(), "source", source));
        return view(user.getId(), account);
    }

    private AccountView replaceNumber(UserEntity user, MobileMoneyPayoutAccountEntity account, Checked checked,
                                      String source) {
        String oldMasked = account.getMsisdnMasked();
        account.setMsisdn(checked.msisdn());
        account.setMsisdnMasked(Msisdn.mask(checked.msisdn()));
        account.setVerifiedAt(Instant.now());
        repository.save(account);
        accounts.syncSummary(user);
        userRepository.save(user);
        audit.log("USER", user.getId(), NUMBER_CHANGED, user.getId(), Map.of("accountId", account.getId(),
                "provider", account.getProvider(), "oldMsisdnMasked", oldMasked,
                "msisdnMasked", account.getMsisdnMasked(), "source", source));
        return view(user.getId(), account);
    }

    // ── validation partagée (ajout, changement de numéro, upsert) ───────────

    private void requireEnabled() {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
    }

    // Verrou pessimiste avant toute lecture d'état et toute écriture : deux ajouts concurrents du
    // même code se suivent au lieu de se croiser (règle projet #17).
    private UserEntity lockUser(UUID userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable : " + userId));
    }

    private static String normalizePhone(String phoneNumber, Messages m) {
        try {
            return Msisdn.normalize(phoneNumber);
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-invalid-phone",
                    "Mobile Money Invalid Phone", m.get("problem.mobile-money-account.invalid-phone"));
        }
    }

    /**
     * Code en majuscules, validé AVANT tout écho dans un message ou un log. Vide : 422 si
     * {@code required}, sinon {@code null} (l'opérateur détecté s'appliquera).
     */
    private static String cleanCode(UUID userId, String provider, boolean required, Messages m) {
        String code = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            if (required) {
                throw unsupported(userId, m.get("problem.mobile-money-account.choose-network"));
            }
            return null;
        }
        if (!PawapayProviders.isWellFormed(code)) {
            throw unsupported(userId, m.get("problem.mobile-money-account.invalid-network-code"));
        }
        return code;
    }

    /**
     * Le numéro peut-il recevoir des versements sur {@code code} ? Seul l'opérateur que pawaPay
     * PRÉDIT pour le numéro est accepté, même quand il est fermé au versement (jamais un compte
     * Orange sur un numéro MTN pendant une fermeture MTN, Review Focus 3) :
     * <ul>
     *   <li>code hors du catalogue PAYOUT du numéro : 422 {@code unsupported} (« Réseau X
     *       indisponible ») ; c'est le prédit lui-même, fermé au versement : 422 {@code unsupported}
     *       ({@code operation-closed}) ;</li>
     *   <li>code au catalogue mais prédit différent : 422 {@code wrong-network} nommant le prédit.</li>
     * </ul>
     * Sans prédiction (pas de chemin connu aujourd'hui : le résolveur rejette un prédit inconnu),
     * le code choisi au catalogue est accepté. {@code code} nul : le prédit.
     */
    private Checked check(UUID userId, String msisdn, String code, Messages m) {
        PawapayProviderResolver.Catalogue catalogue;
        try {
            catalogue = providers.catalogue(msisdn, PawapayOperationKind.PAYOUT, null,
                    "le compte de versement mobile money de " + userId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(userId, reasonDetail(m, e));
        }
        String predicted = catalogue.predicted();
        String wanted = code != null ? code : predicted;
        if (wanted == null) {
            throw unsupported(userId, m.get("problem.mobile-money-account.no-provider"));
        }
        boolean isPredicted = wanted.equalsIgnoreCase(predicted);
        PawapayProviderConfig option = catalogue.option(wanted).orElseThrow(() -> unsupported(userId, isPredicted
                ? m.get("problem.mobile-money-account.operation-closed", PawapayProviders.label(wanted))
                : m.get("problem.mobile-money.network-unavailable", PawapayProviders.label(wanted))));
        if (predicted != null && !isPredicted) {
            log.warn("Compte de versement {} refusé pour {} : pawaPay prédit {}", option.provider(), userId, predicted);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-wrong-network",
                    "Mobile Money Account Wrong Network",
                    m.get("problem.mobile-money-account.wrong-network", PawapayProviders.label(predicted)));
        }
        return new Checked(catalogue.msisdn(), option.provider(), catalogue.countryAlpha2(),
                catalogue.currency().toUpperCase(Locale.ROOT));
    }

    /** Libellé métier d'un numéro reconnu mais inexploitable pour le versement. */
    static String reasonDetail(Messages m, PawapayProviderResolver.UnsupportedNumberException e) {
        return switch (e.reason()) {
            case NO_PROVIDER -> m.get("problem.mobile-money-account.no-provider");
            case OPERATION_CLOSED -> m.get("problem.mobile-money-account.operation-closed", e.providerLabel());
            case CURRENCY_MISMATCH -> m.get("problem.mobile-money-account.currency-mismatch", e.providerCurrency());
            case COUNTRY_UNKNOWN -> m.get("problem.mobile-money.country-unknown");
            case PROVIDER_NOT_AVAILABLE -> m.get("problem.mobile-money.network-unavailable", e.providerLabel());
        };
    }

    private MobileMoneyPayoutAccountEntity ownedAccount(UUID userId, UUID accountId, Messages m) {
        // Un compte d'un autre utilisateur est introuvable, comme un compte inconnu : on ne
        // confirme jamais son existence.
        return repository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId).orElseThrow(() ->
                new YadonyBusinessException(HttpStatus.NOT_FOUND, "mobile-money-account-not-found",
                        "Mobile Money Account Not Found", m.get("problem.mobile-money-account.not-found")));
    }

    private void requireUnlocked(UUID userId, MobileMoneyPayoutAccountEntity account, Messages m) {
        int pending = accounts.pendingPayouts(userId, account.getProvider());
        if (pending > 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-account-locked",
                    "Mobile Money Account Locked", m.plural("problem.mobile-money-account.locked", pending, pending),
                    Map.of("pendingPayouts", pending));
        }
    }

    private AccountView view(UUID userId, MobileMoneyPayoutAccountEntity a) {
        return new AccountView(a.getId(), a.getProvider(), PawapayProviders.label(a.getProvider()),
                PawapayProviders.brand(a.getProvider()), a.getCountry(), a.getCurrency(), a.getMsisdnMasked(),
                a.getVerifiedAt(), accounts.pendingPayouts(userId, a.getProvider()));
    }

    /** Rejet métier (422) : trace la branche d'échec pour le support, sans jamais loguer le numéro. */
    private static YadonyBusinessException unsupported(UUID userId, String detail) {
        log.warn("Compte de versement mobile money refusé pour {} : {}", userId, detail);
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-unsupported",
                "Mobile Money Account Unsupported", detail);
    }
}
