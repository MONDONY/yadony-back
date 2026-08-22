package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.admin.account.AdminAuthorities;
import com.yadony.api.auth.dto.AdminInfo;
import com.yadony.api.auth.dto.DeleteImmediatelyRequest;
import com.yadony.api.auth.dto.DeletionEligibilityResponse;
import com.yadony.api.auth.dto.RegisterRequest;
import com.yadony.api.auth.dto.UpdateProfileRequest;
import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.auth.dto.WalletRefundRequestResponse;
import com.yadony.api.auth.events.UserRegisteredEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.FirebaseSignInProvider;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.payments.wallet.WalletRefundRequestService;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final UserService userService;
    private final AccountFinalizationService accountFinalizationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConnectedDevicesService connectedDevicesService;
    private final StorageService storageService;
    private final AdminAuthService adminAuthService;
    private final FirebaseContactService firebaseContact;
    private final UsernameGenerator usernameGenerator;
    private final WalletRefundRequestService walletRefundRequestService;

    public AuthService(UserRepository userRepository,
                       AuditService auditService,
                       UserService userService,
                       AccountFinalizationService accountFinalizationService,
                       ApplicationEventPublisher eventPublisher,
                       ConnectedDevicesService connectedDevicesService,
                       StorageService storageService,
                       AdminAuthService adminAuthService,
                       FirebaseContactService firebaseContact,
                       UsernameGenerator usernameGenerator,
                       WalletRefundRequestService walletRefundRequestService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.userService = userService;
        this.accountFinalizationService = accountFinalizationService;
        this.eventPublisher = eventPublisher;
        this.connectedDevicesService = connectedDevicesService;
        this.storageService = storageService;
        this.adminAuthService = adminAuthService;
        this.firebaseContact = firebaseContact;
        this.usernameGenerator = usernameGenerator;
        this.walletRefundRequestService = walletRefundRequestService;
    }

    @Transactional
    public UserResponse register(String firebaseUid, FirebaseToken decodedToken, RegisterRequest request) {
        // Un jeton ENCORE anonyme ne donne jamais un compte réel. Refus posé ici, en tête,
        // et non sur chaque branche : `createUser` le refusait déjà via le `default` de son
        // switch sur le provider, mais les deux retours anticipés ci-dessous (ligne active,
        // ligne soft-deletée) ne l'atteignent jamais.
        //
        // La branche réactivation était la faille : elle accorde SENDER+TRAVELER sans aucun
        // contrôle de provider. Inoffensive tant qu'une ligne soft-deletée restait rare, elle
        // ne l'est plus depuis que `GuestClaimService` en produit une à chaque réclamation
        // réussie (et la purge des lignes invitées en produira aussi). La séquence : poser un
        // favori en visiteur, réclamer ses données, puis appeler /auth/register avec le même
        // jeton encore anonyme — et obtenir un compte complet sur un UID anonyme, sans
        // téléphone ni email, en contournant toutes les validations d'identité. Cela ferait
        // aussi refuser, ensuite, une réclamation pourtant légitime de cette même session
        // (`guest-claim-has-roles`).
        //
        // /auth/** est en permitAll : un invité atteint réellement cet endpoint, ce n'est pas
        // un cas théorique. Aucun parcours d'inscription légitime ne présente un jeton anonyme
        // — l'app promeut d'abord la session par linkWithCredential (le provider devient alors
        // `phone`), ou bascule sur le compte existant si le numéro est déjà pris.
        if (FirebaseSignInProvider.isAnonymous(decodedToken)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid-provider",
                    "Invalid Provider", "Mode d'authentification non supporté");
        }

        // Active user → return as-is, sauf s'il s'agit d'une ligne invitée à promouvoir
        Optional<UserEntity> active = userRepository.findByFirebaseUid(firebaseUid);
        if (active.isPresent()) {
            return toResponse(promoteGuestRowIfNeeded(active.get(), decodedToken));
        }
        // Soft-deleted user with same Firebase UID → reactivate via native UPDATE to bypass
        // @Where filter (em.merge() would SELECT with the filter, find nothing, attempt INSERT → constraint violation)
        Optional<UserEntity> deleted = userRepository.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (deleted.isPresent()) {
            userRepository.reactivateByFirebaseUid(firebaseUid, UserStatus.ACTIVE.name());
            UserEntity reactivated = userRepository.findByFirebaseUid(firebaseUid).orElseThrow();
            reactivated.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
            // Reset pseuyadonymized fields (GDPR deletion sets placeholder values)
            reactivated.setFirstName(null);
            reactivated.setLastName(null);
            reactivated.setKycStatus(KycStatus.NOT_STARTED);
            // Coordonnées : rien à restaurer localement, elles vivent dans Firebase.
            // Le compte Firebase qui se ré-inscrit porte déjà son téléphone (provider
            // phone) ou son email (google/apple) ; seul le custom token doit se voir
            // écrire son email, un compte custom n'en portant aucun.
            String signInProvider = FirebaseSignInProvider.of(decodedToken);
            if ("custom".equals(signInProvider) && request.email() != null) {
                firebaseContact.updateEmail(firebaseUid, request.email());
            }
            firebaseContact.evict(firebaseUid);
            userRepository.save(reactivated);
            auditService.log("USER", reactivated.getId(), "USER_REACTIVATED", reactivated.getId(),
                    Map.of("firebaseUid", firebaseUid));
            return toResponse(reactivated);
        }
        return createUser(firebaseUid, decodedToken, request);
    }

    /**
     * Promeut en compte réel la ligne {@code users} d'un ancien visiteur.
     *
     * <p><b>Pourquoi c'est nécessaire.</b> {@code linkWithCredential} conserve l'UID Firebase :
     * quand un visiteur s'inscrit, {@code register} retombe donc sur SA ligne, déjà active,
     * créée sans aucun rôle par {@code GuestUserProvisioner} au premier favori. La renvoyer
     * telle quelle laissait le compte à autorités vides : aucun endpoint exigeant un rôle ne
     * lui était accessible, et la purge des lignes invitées abandonnées l'aurait visé comme
     * un visiteur qui n'est jamais revenu.
     *
     * <p><b>Deux conditions, toutes deux nécessaires.</b> On ne touche qu'aux lignes
     * {@code roles.isEmpty()} : seul {@code GuestUserProvisioner} en produit, un compte inscrit
     * ou réactivé porte toujours SENDER+TRAVELER. Et on exige un jeton probant.
     *
     * <p>Le cas anonyme est déjà fermé en tête de {@link #register} depuis la ronde 2 ; la
     * condition conservée ici y ajoute ce que ce refus ne couvre pas : un jeton <b>absent</b>
     * ({@code isAnonymous(null)} vaut {@code false}). Un jeton nul ne prouve rien, il ne peut
     * donc pas déclencher de promotion — c'est déjà la position de {@code createUser}, dont le
     * {@code switch} rejette un provider nul ou inconnu.
     */
    private UserEntity promoteGuestRowIfNeeded(UserEntity user, FirebaseToken decodedToken) {
        boolean guestRow = user.getRoles().isEmpty();
        boolean tokenPromoted = decodedToken != null && !FirebaseSignInProvider.isAnonymous(decodedToken);
        if (!guestRow || !tokenPromoted) {
            return user;
        }
        user.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        UserEntity saved = userRepository.save(user);
        auditService.log("USER", saved.getId(), "GUEST_PROMOTED", saved.getId(),
                Map.of("provider", String.valueOf(FirebaseSignInProvider.of(decodedToken))));
        // L'inscription d'un invité EST une inscription : sans cet event, le compte naîtrait
        // sans code de parrainage et n'apparaîtrait dans aucune métrique d'acquisition.
        eventPublisher.publishEvent(new UserRegisteredEvent(saved.getId(), saved.getFirebaseUid()));
        return saved;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(String firebaseUid, UpdateProfileRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        if (request.firstName() != null) {
            String v = request.firstName().trim();
            user.setFirstName(v.isEmpty() ? null : v);
        }
        if (request.lastName() != null) {
            String v = request.lastName().trim();
            user.setLastName(v.isEmpty() ? null : v);
        }
        if (request.birthDate() != null) {
            user.setBirthDate(request.birthDate());
        }
        if (request.city() != null) {
            String v = request.city().trim();
            user.setCity(v.isEmpty() ? null : v);
        }
        if (request.phoneNumber() != null) {
            String v = request.phoneNumber().trim();
            // Lu ici seulement : une mise à jour qui ne touche pas au numéro n'a
            // aucune raison d'interroger Firebase.
            String currentPhone = firebaseContact.getContact(firebaseUid).phoneNumber();
            if (!v.isEmpty() && !v.equals(currentPhone)) {
                if (firebaseContact.isPhoneTakenByAnother(v, firebaseUid)) {
                    throw new YadonyBusinessException(HttpStatus.CONFLICT, "phone-already-exists",
                            "Phone Number Already Registered", "Ce numéro est déjà associé à un compte");
                }
                firebaseContact.updatePhone(firebaseUid, v);
            }
        }
        if (request.bio() != null) {
            String v = request.bio().trim();
            user.setBio(v.isEmpty() ? null : v);
        }
        if (request.languages() != null) {
            user.setLanguages(new HashSet<>(request.languages()));
        }
        if (request.transportMode() != null) {
            String v = request.transportMode().trim();
            user.setTransportMode(v.isEmpty() ? null : TransportMode.valueOf(v));
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void updateFcmToken(String firebaseUid, String fcmToken,
                                String deviceId, String deviceName, String platform) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setFcmToken(fcmToken);
        userRepository.save(user);

        if (deviceId != null && !deviceId.isBlank()
                && deviceName != null && platform != null) {
            connectedDevicesService.upsertDevice(user.getId(), deviceId, deviceName, platform, fcmToken);
        }
    }

    @Transactional(readOnly = true)
    public com.yadony.api.auth.dto.PrivacySettingsResponse getPrivacySettings(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        return new com.yadony.api.auth.dto.PrivacySettingsResponse(
                user.isContactKycOnly(), user.isHidePhoneNumber());
    }

    /**
     * @param hidePhoneNumber {@code null} = préférence laissée inchangée (client
     *        antérieur à ce champ), et non « remettre à false ».
     */
    @Transactional
    public void updatePrivacySettings(String firebaseUid, boolean contactKycOnly,
                                      Boolean hidePhoneNumber) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setContactKycOnly(contactKycOnly);
        boolean hideChanged = hidePhoneNumber != null
                && hidePhoneNumber != user.isHidePhoneNumber();
        if (hidePhoneNumber != null) {
            user.setHidePhoneNumber(hidePhoneNumber);
        }
        userRepository.save(user);

        // Masquer son numéro retire un canal de contact à la contrepartie d'un deal
        // en cours : on garde une trace datée de la décision, comme pour le
        // consentement analytics. Aucune PII dans le payload.
        if (hideChanged) {
            auditService.log("USER", user.getId(), "PHONE_VISIBILITY_UPDATED", user.getId(),
                    Map.of("hidePhoneNumber", hidePhoneNumber));
        }
    }

    @Transactional(readOnly = true)
    public com.yadony.api.auth.dto.AnalyticsConsentResponse getAnalyticsConsent(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        Instant consentAt = user.getAnalyticsConsentAt();
        return new com.yadony.api.auth.dto.AnalyticsConsentResponse(
                user.getAnalyticsConsent(),
                consentAt == null ? null : consentAt.toString(),
                user.getAnalyticsConsentVersion());
    }

    @Transactional
    public void updateAnalyticsConsent(String firebaseUid, boolean granted,
                                       String policyVersion, String source) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setAnalyticsConsent(granted);
        user.setAnalyticsConsentAt(Instant.now());
        user.setAnalyticsConsentVersion(policyVersion);
        user.setAnalyticsConsentSource(source);
        userRepository.save(user);

        // PII interdite dans le payload : uniquement granted / policyVersion / source.
        // HashMap (et non Map.of) car policyVersion et source peuvent être null.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("granted", granted);
        payload.put("policyVersion", policyVersion == null ? "" : policyVersion);
        payload.put("source", source == null ? "" : source);
        auditService.log("USER", user.getId(), "ANALYTICS_CONSENT_UPDATED", user.getId(), payload);
    }

    /**
     * Adresse de résidence. Le pays n'est pas modifiable ici : il vient de
     * l'étape « pays » de l'onboarding et Stripe verrouille celui du compte
     * connecté.
     *
     * <p>{@code city} écrase le champ profil existant : c'est la même donnée,
     * en garder deux versions les ferait diverger.
     */
    @Transactional
    public void updateResidenceAddress(String firebaseUid, com.yadony.api.auth.dto.ResidenceAddressRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setResidenceStreet(request.street());
        user.setResidenceLine2(request.line2());
        user.setResidencePostalCode(request.postalCode());
        user.setCity(request.city());
        userRepository.save(user);

        // Aucune PII dans le journal : on trace le fait, pas l'adresse.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("hasLine2", request.line2() != null && !request.line2().isBlank());
        auditService.log("USER", user.getId(), "RESIDENCE_ADDRESS_UPDATED", user.getId(), payload);
    }

    /**
     * Marque le parcours d'onboarding comme vu. Idempotent : une seconde
     * réponse ne réécrit pas la date, sinon un utilisateur qui rouvre le
     * récapitulatif repousserait indéfiniment son propre horodatage.
     */
    @Transactional
    public void markOnboardingSeen(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        if (user.getOnboardingSeenAt() != null) {
            return;
        }
        user.setOnboardingSeenAt(Instant.now());
        userRepository.save(user);
    }

    /**
     * Retourne l'UUID de l'utilisateur courant à partir du FirebaseUID dans le SecurityContext.
     */
    @Transactional(readOnly = true)
    public UUID requireUserId() {
        String firebaseUid = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"))
                .getId();
    }

    /**
     * Supprime le compte : soft-delete en DB + suppression dans Firebase Auth.
     */
    @Transactional
    // Story 9.8 — Delegates full GDPR deletion (pseuyadonymization, KYC cleanup, Firebase revoke)
    public void deleteAccount(String firebaseUid) {
        userService.deleteAccount(firebaseUid);
    }

    // Story 9.8 — Vérification lecture seule avant tentative de suppression (cf. UserService).
    public DeletionEligibilityResponse checkDeletionEligibility(String firebaseUid) {
        return userService.checkDeletionEligibility(firebaseUid);
    }

    /** Demande explicite et autonome de remboursement du solde wallet (ex. l'utilisateur garde
     *  son compte mais veut récupérer son argent) : ouvre un ticket manuel pour chaque devise
     *  en solde positif. La suppression de compte ouvre déjà ce même ticket automatiquement
     *  (cf. {@link UserService#openWalletRefundTicketIfNeeded}) — cet endpoint n'est donc plus
     *  un prérequis pour supprimer son compte, juste un raccourci pour qui veut être remboursé
     *  sans attendre. */
    public List<WalletRefundRequestResponse> requestWalletRefund(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        return walletRefundRequestService.request(user.getId()).stream()
                .map(WalletRefundRequestResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Suppression immédiate du compte (HARD_IMMEDIATE).
     * Vérifie : statut BANNED → 409, escrow actif → 422.
     * Délègue la finalisation RGPD à {@link AccountFinalizationService}.
     */
    @Transactional
    public void deleteImmediately(String firebaseUid, DeleteImmediatelyRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() == UserStatus.BANNED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "account-banned",
                    "Conflict", "Ce compte est banni et ne peut pas être supprimé");
        }

        if (userService.hasActiveEscrow(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }

        // Un solde wallet réel (rechargé par carte) ouvre un ticket de remboursement
        // automatique mais ne bloque jamais la suppression (Apple 5.1.1(v)). Règle portée par
        // UserService pour rester identique entre suppression immédiate (ici) et suppression
        // J+30 (requestDeletion).
        userService.openWalletRefundTicketIfNeeded(user.getId());

        auditService.log("USER", user.getId(), "ACCOUNT_DELETE_IMMEDIATELY_REQUESTED",
                user.getId(), Map.of("initiatedBy", "user"));
        accountFinalizationService.finalize(user, FinalizationReason.HARD_IMMEDIATE);
    }

    /**
     * Réactive un compte supprimé : restore status à ACTIVE et deletedAt à null.
     */
    @Transactional
    public UserResponse reactivateAccount(String firebaseUid) {
        userService.reactivateAccount(firebaseUid);
        return getProfile(firebaseUid);
    }

    /**
     * Upgrades the authenticated user to a PRO account.
     * Delegates business-rule enforcement to {@link UserService#upgradeToPro}.
     *
     * <p>The already-loaded {@link UserEntity} is passed directly to
     * {@code UserService.upgradeToPro} to avoid a redundant DB lookup.
     */
    @Transactional
    public UserResponse upgradeToPro(String firebaseUid, UpgradeToProRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        UserEntity updated = userService.upgradeToPro(user, request);
        return toResponse(updated);
    }

    @Transactional
    public UserResponse downgradePro(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.isProAccount()) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "not-pro-account", "Not a PRO account", "Ce compte n'est pas un compte PRO");
        }
        UserEntity updated = userService.downgradePro(user);
        return toResponse(updated);
    }

    private UserResponse createUser(String firebaseUid, FirebaseToken decodedToken, RegisterRequest request) {
        // Tout compte reçoit SENDER + TRAVELER : le rôle voyageur est universel,
        // les capacités (carte via Stripe) sont gérées séparément.
        Set<Role> roles = new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER));

        String signInProvider = FirebaseSignInProvider.of(decodedToken);

        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        // Identifiant public immédiat : le compte naît sans prénom (celui-ci n'arrive qu'au
        // KYC ou à l'édition du profil) et doit pourtant être nommable dès sa première offre.
        user.setUsername(usernameGenerator.generate());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setRoles(roles);

        // Aucune coordonnée n'est écrite en base : téléphone et email restent dans
        // Firebase, qui garantit aussi leur unicité (un numéro / une adresse = un
        // compte Firebase). Les branches ci-dessous ne font donc que valider le
        // provider et rattacher un éventuel compte local déjà existant.
        switch (signInProvider == null ? "" : signInProvider) {
            case "phone" -> {
                // Le numéro vient du token, jamais du corps de requête : ce dernier
                // n'est pas authentifié et permettrait d'usurper un autre numéro.
                if (extractPhoneClaim(decodedToken) == null) {
                    throw new YadonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "phone-required",
                            "Phone Required", "Le numéro de téléphone est requis");
                }
            }
            case "google.com", "apple.com" -> {
                if (decodedToken == null) {
                    throw new YadonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "token-required",
                            "Token Required", "Token Firebase manquant");
                }
                String email = decodedToken.getEmail();
                if (email == null) {
                    throw new YadonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "email-required",
                            "Email Required", "L'email est introuvable dans le token Firebase");
                }
                Optional<UserResponse> existing = findLocalAccountByEmail(email, firebaseUid);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            case "custom" -> {
                if ("sms".equals(extractOtpChannelClaim(decodedToken))) {
                    // Custom token issu de SmsOtpService.verifyOtp() : l'UID a déjà été
                    // résolu via getUserByPhoneNumber()/createUser() (même UID que le SDK
                    // natif aurait attribué à ce numéro), et le numéro est donc déjà écrit
                    // sur le UserRecord Firebase — rien à comparer à request.email(), ce
                    // champ est d'ailleurs naturellement absent du body pour ce flow.
                    if (firebaseContact.getContact(firebaseUid).phoneNumber() == null) {
                        throw new YadonyBusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "phone-required",
                                "Phone Required", "Le numéro de téléphone est introuvable");
                    }
                } else if ("email".equals(extractOtpChannelClaim(decodedToken))) {
                    // Custom token issu de EmailOtpService.verifyOtp() : l'UID a été résolu
                    // via getUserByEmail()/createUser(), l'adresse est donc déjà portée par
                    // le UserRecord. Rien à comparer à request.email() — et surtout rien à
                    // réécrire : réattribuer une adresse déjà détenue par ce même compte
                    // faisait échouer l'inscription sur EMAIL_ALREADY_EXISTS.
                    //
                    // L'identité ne vient plus du corps de la requête mais de Firebase, ce
                    // qui retire au client toute prise sur l'UID auquel il s'inscrit.
                    if (firebaseContact.getContact(firebaseUid).email() == null) {
                        throw new YadonyBusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "email-required",
                                "Email Required", "L'adresse email est introuvable");
                    }
                    Optional<UserResponse> existing =
                            findLocalAccountByEmail(firebaseContact.getContact(firebaseUid).email(), firebaseUid);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                } else {
                    // Custom token email émis avant l'ajout du claim `otp_channel` : l'UID
                    // y valait l'adresse elle-même. Conservé le temps que les jetons en
                    // circulation expirent (1 h), les comptes déjà créés ainsi restant
                    // valides puisque leur UID porte désormais l'adresse.
                    if (request.email() == null) {
                        throw new YadonyBusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "email-required",
                                "Email Required", "L'adresse email est requise");
                    }
                    if (!firebaseUid.equalsIgnoreCase(request.email())) {
                        throw new YadonyBusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "email-mismatch",
                                "Email Mismatch", "L'email fourni ne correspond pas au token Firebase");
                    }
                    Optional<UserResponse> existing = findLocalAccountByEmail(request.email(), firebaseUid);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    // Un compte créé par custom token ne porte aucune adresse côté Firebase :
                    // on l'y écrit pour que Firebase reste la seule source de vérité.
                    firebaseContact.updateEmail(firebaseUid, request.email());
                }
            }
            default -> throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid-provider",
                    "Invalid Provider", "Mode d'authentification non supporté");
        }

        UserEntity saved = userRepository.save(user);

        auditService.log(
                "USER", saved.getId(), "USER_CREATED", saved.getId(),
                Map.of("provider", String.valueOf(signInProvider))
        );

        eventPublisher.publishEvent(new UserRegisteredEvent(saved.getId(), saved.getFirebaseUid()));

        return toResponse(saved);
    }

    /** Numéro porté par le token Firebase (claim {@code phone_number}), ou null. */
    private static String extractPhoneClaim(FirebaseToken decodedToken) {
        if (decodedToken == null) {
            return null;
        }
        Object claim = decodedToken.getClaims().get("phone_number");
        return claim instanceof String s ? s : null;
    }

    /**
     * Developer claim posé par {@code SmsOtpService.verifyOtp()} au moment du
     * {@code createCustomToken(uid, claims)}, propagé tel quel (top-level, pas
     * nested sous {@code firebase}) dans l'ID token produit par
     * {@code signInWithCustomToken()}. Distingue un custom-token SMS OTP (uid =
     * UID Firebase natif du numéro) d'un custom-token email OTP (uid = email).
     */
    private static String extractOtpChannelClaim(FirebaseToken decodedToken) {
        if (decodedToken == null) {
            return null;
        }
        Object claim = decodedToken.getClaims().get("otp_channel");
        return claim instanceof String s ? s : null;
    }

    /**
     * Compte local déjà rattaché à cette adresse côté Firebase. S'exclut lui-même :
     * le compte qui s'authentifie porte déjà l'adresse, sans quoi toute inscription
     * google/apple se croirait en conflit avec elle-même.
     */
    private Optional<UserResponse> findLocalAccountByEmail(String email, String selfUid) {
        return firebaseContact.findUidByEmail(email)
                .filter(uid -> !uid.equals(selfUid))
                .flatMap(userRepository::findByFirebaseUid)
                .map(this::toResponse);
    }

    @Transactional
    public UserResponse updateAvatar(String firebaseUid, MultipartFile file) throws IOException {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"));
        if (file == null || file.isEmpty()) {
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST,
                    "file-missing",
                    "File Missing",
                    "Fichier manquant");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new YadonyBusinessException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "file-too-large",
                    "File Too Large",
                    "Image trop volumineuse (max 10 Mo)");
        }
        String key = storageService.uploadFile(file, "users/" + firebaseUid + "/");
        user.setAvatarUrl(key);
        return toResponse(userRepository.save(user));
    }

    public UserResponse toResponse(UserEntity user) {
        // Check if this user is also an admin and resolve admin info if so
        AdminInfo adminInfo = null;
        Optional<AdminAuthorities> adminAuthOpt = adminAuthService.resolve(user.getFirebaseUid());
        if (adminAuthOpt.isPresent()) {
            AdminAuthorities auth = adminAuthOpt.get();
            List<String> permissions = auth.authorities().stream()
                    .map(a -> a.getAuthority())
                    .filter(a -> !a.startsWith("ROLE_"))
                    .collect(Collectors.toList());
            adminInfo = new AdminInfo(
                    auth.email(),
                    auth.role(),
                    permissions,
                    auth.mustChangePassword()
            );
        }

        FirebaseContactService.Contact contact = firebaseContact.getContact(user.getFirebaseUid());

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                contact.phoneNumber(),
                contact.email(),
                user.getFirstName(),
                user.getLastName(),
                user.getBirthDate(),
                user.getCity(),
                user.getRoles().stream().map(Role::name).collect(Collectors.toSet()),
                user.getKycStatus().name(),
                user.getStatus().name(),
                user.getTotalTrips(),
                user.getTotalShipments(),
                user.isProAccount(),
                user.getStripeAccountStatus(),
                user.getCountry(),
                user.getBio(),
                user.getLanguages(),
                user.getTransportMode() != null ? user.getTransportMode().name() : null,
                storageService.avatarUrl(user.getAvatarUrl()),
                user.getAverageRating() != null ? user.getAverageRating().doubleValue() : null,
                adminInfo,
                user.getResidenceStreet(),
                user.getResidenceLine2(),
                user.getResidencePostalCode(),
                user.getOnboardingSeenAt() == null ? null : user.getOnboardingSeenAt().toString()
        );
    }
}
