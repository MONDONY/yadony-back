package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ancien contrat du compte de versement mobile money ({@code /payments/mobile-money/account}), servi
 * tant que l'app n'est pas à jour, par-dessus la table des comptes de versement (un compte par code
 * opérateur pawaPay) : toute écriture passe par {@link MobileMoneyPayoutAccountService} (verrou,
 * validation partagée, audit), et le résumé porté par {@link UserEntity} n'est recalculé que par
 * {@link MobileMoneyPayoutAccounts#syncSummary}. Ce service n'écrit jamais lui-même les colonnes
 * {@code users.mobile_money_*}.
 *
 * <p>Numéro fourni par l'appelant TOUJOURS prioritaire s'il est renseigné (il peut légitimement
 * différer du numéro du compte Firebase : numéro d'un proche, opérateur distinct…), sinon numéro du
 * compte Firebase (déjà vérifié par OTP). Risque accepté : un numéro fourni n'est pas vérifié par
 * OTP, une session compromise pourrait donc rediriger les versements futurs ; garde-fous : provenance
 * tracée dans l'audit ({@code source}), numéro jamais rendu qu'en forme masquée, retrait et
 * changement de numéro verrouillés tant qu'un colis attend son versement.
 */
@Service
public class MobileMoneyAccountService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyAccountService.class);

    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final PawapayProviderResolver providers;
    private final MobileMoneyPayoutAccountService payoutAccounts;
    private final PawapayProperties props;
    private final MessagesResolver messagesResolver;

    public MobileMoneyAccountService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                     PawapayProviderResolver providers, MobileMoneyPayoutAccountService payoutAccounts,
                                     PawapayProperties props, MessagesResolver messagesResolver) {
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.providers = providers;
        this.payoutAccounts = payoutAccounts;
        this.props = props;
        this.messagesResolver = messagesResolver;
    }

    /** Résumé porté par l'utilisateur (compte le plus ancien, tous les codes). */
    @Transactional(readOnly = true)
    public MobileMoneyAccountResponse get(UUID userId) {
        return toResponse(userRepository.findById(userId).orElseThrow(() -> notFound(userId)));
    }

    /** Ancien contrat (client sans liste de réseaux) : équivaut à {@code activate(userId, providedPhone, null)}. */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId, String providedPhone) {
        return activate(userId, providedPhone, null);
    }

    /**
     * Crée le compte de versement d'UN code, ou remplace son numéro s'il existe déjà
     * ({@link MobileMoneyPayoutAccountService#upsert}, verrou appliqué : 409
     * {@code mobile-money-account-locked}). Numéro : fourni (normalisé, 422
     * {@code mobile-money-invalid-phone} s'il est invalide), sinon téléphone Firebase, sinon 422
     * {@code mobile-money-phone-required}.
     *
     * <p>Code visé : sans {@code providers}, l'opérateur que pawaPay prédit pour le numéro. Avec
     * {@code providers}, chaque code doit figurer au catalogue PAYOUT du numéro (422
     * {@code mobile-money-account-unsupported} nommant le réseau) et seul le code de repli est
     * visé : le détecté s'il est coché, sinon le premier coché dans l'ordre du catalogue ; les autres
     * codes cochés ne créent rien (chaque compte a désormais son propre numéro). La réponse résume ce
     * compte-là.
     */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId, String providedPhone, List<String> providers) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
        PhoneSource phone = resolvePhone(user, providedPhone, false);
        if ("provided".equals(phone.source())) {
            log.info("Activation mobile money pour {} avec un numéro saisi (peut différer du numéro Firebase)", userId);
        }
        List<String> wanted = cleanCodes(providers);
        String code = null;
        if (!wanted.isEmpty()) {
            Messages m = messagesResolver.forRequest();
            requireWellFormed(m, userId, wanted);
            PawapayProviderResolver.Catalogue catalogue = catalogueOrUnsupported(userId, phone.phone(), m);
            code = fallbackProvider(catalogue, selectAccepted(m, userId, catalogue, wanted));
        }
        return toResponse(payoutAccounts.upsert(userId, phone.phone(), code, phone.source()));
    }

    /**
     * Retire tous les comptes de versement ({@link MobileMoneyPayoutAccountService#removeAll}, tout
     * ou rien : 409 {@code mobile-money-account-locked} si l'un est verrouillé). Suppression LOGIQUE ;
     * le résumé repasse {@code DISABLED}.
     */
    @Transactional
    public MobileMoneyAccountResponse disable(UUID userId) {
        payoutAccounts.removeAll(userId);
        return toResponse(userRepository.findById(userId).orElseThrow(() -> notFound(userId)));
    }

    /**
     * Réseaux utilisables pour le versement sur un numéro : fourni, sinon déjà enregistré, sinon
     * Firebase (422 {@code mobile-money-phone-required} sans aucun). Lecture seule, rien n'est écrit.
     */
    @Transactional(readOnly = true)
    public MobileMoneyProvidersResponse providers(UUID userId, String providedPhone) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
        PhoneSource phone = resolvePhone(user, providedPhone, true);
        return MobileMoneyProvidersResponse.from(
                catalogueOrUnsupported(userId, phone.phone(), messagesResolver.forRequest()));
    }

    /**
     * Ancien {@code PUT /account/providers} : les réseaux acceptés n'existent plus (un compte par
     * code, chacun avec son numéro). 422 {@code mobile-money-update-app}, rien n'est lu ni écrit.
     */
    public MobileMoneyAccountResponse updateProviders() {
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-update-app",
                "Mobile Money Update App", messagesResolver.forRequest().get("problem.mobile-money-account.update-app"));
    }

    /** Numéro retenu et sa provenance ({@code provided}, {@code stored}, {@code firebase}) pour l'audit. */
    record PhoneSource(String phone, String source) {}

    /**
     * Numéro fourni (normalisé, 422 {@code mobile-money-invalid-phone}), sinon numéro déjà enregistré
     * si {@code allowStored}, sinon téléphone Firebase, sinon 422 {@code mobile-money-phone-required}.
     * Le numéro fourni prime sans condition : le compte Firebase n'est même pas consulté.
     */
    private PhoneSource resolvePhone(UserEntity user, String providedPhone, boolean allowStored) {
        if (providedPhone != null && !providedPhone.isBlank()) {
            try {
                return new PhoneSource(Msisdn.normalize(providedPhone), "provided");
            } catch (IllegalArgumentException e) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-invalid-phone",
                        "Mobile Money Invalid Phone", "Numéro de téléphone invalide pour le versement mobile money.");
            }
        }
        if (allowStored && user.getMobileMoneyMsisdn() != null && !user.getMobileMoneyMsisdn().isBlank()) {
            return new PhoneSource(user.getMobileMoneyMsisdn(), "stored");
        }
        String firebasePhone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        if (firebasePhone == null || firebasePhone.isBlank()) {
            log.warn("Numéro mobile money introuvable pour {} : aucun numéro vérifié ni saisi", user.getId());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui recevra vos versements.");
        }
        return new PhoneSource(firebasePhone, "firebase");
    }

    private PawapayProviderResolver.Catalogue catalogueOrUnsupported(UUID userId, String phone, Messages m) {
        try {
            return providers.catalogue(phone, PawapayOperationKind.PAYOUT, null,
                    "le catalogue mobile money de " + userId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(userId, MobileMoneyPayoutAccountService.reasonDetail(m, e));
        }
    }

    /** Codes en majuscules, sans blancs ni doublons ni entrées vides. */
    static List<String> cleanCodes(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream().filter(Objects::nonNull).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().toList();
    }

    /**
     * Revue finale, point 3 (Minor 2) : un code mal formé (retour à la ligne, ponctuation...) ne doit
     * jamais être échoué tel quel dans un detail ou un log. Validé AVANT tout appel pawaPay, avec un
     * message qui ne le répète jamais.
     */
    private static void requireWellFormed(Messages m, UUID userId, List<String> wanted) {
        for (String code : wanted) {
            if (!PawapayProviders.isWellFormed(code)) {
                throw unsupported(userId, m.get("problem.mobile-money-account.invalid-network-code"));
            }
        }
    }

    /** Vérifie chaque code contre le catalogue et rend les acceptés dans l'ordre du catalogue. */
    private static List<String> selectAccepted(Messages m, UUID userId, PawapayProviderResolver.Catalogue catalogue,
                                               List<String> wanted) {
        List<String> available = catalogue.options().stream().map(PawapayProviderConfig::provider).toList();
        for (String code : wanted) {
            if (!available.contains(code)) {
                throw unsupported(userId, m.get("problem.mobile-money.network-unavailable", PawapayProviders.label(code)));
            }
        }
        return available.stream().filter(wanted::contains).toList();
    }

    /** Réseau de repli du versement : le détecté s'il est coché, sinon le premier coché. */
    private static String fallbackProvider(PawapayProviderResolver.Catalogue catalogue, List<String> accepted) {
        return catalogue.detected() != null && accepted.contains(catalogue.detected()) ? catalogue.detected() : accepted.get(0);
    }

    /**
     * Réponse de {@code POST} : le compte visé, seul (son numéro masqué, son code, sa devise). Chaque
     * code ayant désormais son propre numéro, la liste des réseaux n'en porte qu'un.
     */
    private static MobileMoneyAccountResponse toResponse(AccountView a) {
        return new MobileMoneyAccountResponse(MobileMoneyPayoutStatus.ACTIVE.name(), a.msisdnMasked(), a.provider(),
                a.providerLabel(), a.country(), a.currency(), a.verifiedAt(),
                List.of(new MobileMoneyAccountResponse.ProviderView(a.provider(), a.providerLabel())));
    }

    static MobileMoneyAccountResponse toResponse(UserEntity u) {
        List<MobileMoneyAccountResponse.ProviderView> providers = MobileMoneyNetworks.acceptedCodes(u).stream()
                .map(code -> new MobileMoneyAccountResponse.ProviderView(code, PawapayProviders.label(code))).toList();
        return new MobileMoneyAccountResponse(u.getMobileMoneyStatus().name(), u.getMobileMoneyMsisdnMasked(),
                u.getMobileMoneyProvider(), u.getMobileMoneyProvider() == null ? null : PawapayProviders.label(u.getMobileMoneyProvider()),
                u.getMobileMoneyCountry(), u.getMobileMoneyCurrency(), u.getMobileMoneyVerifiedAt(), providers);
    }

    /** Rejet métier (422) : trace la branche d'échec pour le support, sans jamais loguer le numéro. */
    private static YadonyBusinessException unsupported(UUID userId, String detail) {
        log.warn("Activation mobile money refusée pour {} : {}", userId, detail);
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-unsupported",
                "Mobile Money Account Unsupported", detail);
    }

    private static YadonyBusinessException notFound(UUID userId) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                "Utilisateur introuvable : " + userId);
    }
}
