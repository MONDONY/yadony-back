package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import com.yadony.api.auth.dto.PublicTravelerProfileResponse;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.ratings.RatingService;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProfilePublicService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePublicService.class);

    private final UserRepository userRepository;
    private final RatingService ratingService;
    private final UserBusinessPrefsRepository userBusinessPrefsRepository;
    private final StorageService storageService;
    private final BlockVisibility blockVisibility;
    private final MessagesResolver messagesResolver;
    private final FirebaseContactService firebaseContact;

    public ProfilePublicService(UserRepository userRepository,
                                RatingService ratingService,
                                UserBusinessPrefsRepository userBusinessPrefsRepository,
                                StorageService storageService,
                                BlockVisibility blockVisibility,
                                MessagesResolver messagesResolver,
                                FirebaseContactService firebaseContact) {
        this.userRepository = userRepository;
        this.ratingService = ratingService;
        this.userBusinessPrefsRepository = userBusinessPrefsRepository;
        this.storageService = storageService;
        this.blockVisibility = blockVisibility;
        this.messagesResolver = messagesResolver;
        this.firebaseContact = firebaseContact;
    }

    /**
     * Profil public d'un utilisateur, vu par {@code viewerId}.
     *
     * <p>{@code viewerId} est {@code null} pour un appelant anonyme : aucun masquage
     * n'est alors possible, faute d'identité à confronter aux blocages.
     *
     * <p>Le contrôle de blocage passe avant la lecture en base : masqué ou inexistant
     * donnent le même 404, c'est précisément ce qui rend le blocage indétectable.
     */
    public ProfilePublicResponse getProfilePublic(UUID userId, UUID viewerId) {
        blockVisibility.assertVisible(viewerId, userId);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        String displayName = buildDisplayName(user);
        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        int completedBidsCount = user.getTotalTrips() + user.getTotalShipments();
        String memberSince = buildMemberSince(user);

        UserRatingsSummaryResponse ratingSummary = ratingService.getUserRatings(userId, 0, 3, viewerId);

        UserBusinessPrefsEntity prefs = userBusinessPrefsRepository.findById(userId).orElse(null);
        String contactMode = prefs != null ? prefs.getContactMode() : null;
        Integer responseDelayHours = prefs != null ? prefs.getResponseDelayHours() : null;

        // Vérifications (FLUTTER-4H) : seuls des booléens sortent d'ici, jamais le
        // numéro ni l'e-mail. Firebase indisponible → Contact vide → « non vérifié ».
        FirebaseContactService.Contact contact = firebaseContact.getContact(user.getFirebaseUid());
        boolean phoneVerified = contact.phoneNumber() != null && !contact.phoneNumber().isBlank();
        boolean emailVerified = contact.email() != null && !contact.email().isBlank();
        // Filtré ici, jamais côté app : sans consentement, le pays ne part pas.
        String residenceCountry = user.isShowResidenceCountry() ? user.getCountry() : null;
        Integer measuredResponseMinutes = measuredResponseMinutes(userId);
        Integer lastSeenDaysAgo = lastSeenDaysAgo(user);

        return new ProfilePublicResponse(
                userId.toString(),
                displayName,
                storageService.avatarUrl(user.getAvatarUrl()),
                kycVerified,
                user.isProAccount(),
                user.isKiloPro(),
                completedBidsCount,
                ratingSummary.averageRating(),
                ratingSummary.ratingCount(),
                memberSince,
                List.of(),
                contactMode,
                responseDelayHours,
                user.getBio(),
                new ArrayList<>(user.getLanguages()),
                phoneVerified,
                emailVerified,
                residenceCountry,
                measuredResponseMinutes,
                lastSeenDaysAgo
        );
    }

    /** Au-dessous, une médiane ne dit rien du voyageur. */
    static final int MIN_DECISIONS_FOR_RESPONSE_TIME = 3;
    static final int RESPONSE_TIME_WINDOW_DAYS = 90;

    /**
     * Temps de réponse mesuré (FLUTTER-4H) : médiane des délais de décision du
     * voyageur sur ses demandes des 90 derniers jours. Nul sous
     * {@link #MIN_DECISIONS_FOR_RESPONSE_TIME} décisions, ou pour un expéditeur pur.
     */
    private Integer measuredResponseMinutes(UUID userId) {
        try {
            UserRepository.DecisionDelayStats stats = userRepository.travelerDecisionDelay(
                    userId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(RESPONSE_TIME_WINDOW_DAYS));
            if (stats == null || stats.getMedianMinutes() == null || stats.getDecisions() == null
                    || stats.getDecisions() < MIN_DECISIONS_FOR_RESPONSE_TIME) {
                return null;
            }
            return (int) Math.max(1, Math.ceil(stats.getMedianMinutes()));
        } catch (RuntimeException e) {
            // Indicateur de confort : son échec ne doit jamais masquer le profil.
            log.warn("Temps de réponse non calculé pour {} : {}", userId, e.getMessage());
            return null;
        }
    }

    /** Au jour près, et seulement si l'utilisateur ne l'a pas masquée. */
    private static Integer lastSeenDaysAgo(UserEntity user) {
        if (!user.isShowLastSeen() || user.getLastSeenAt() == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(user.getLastSeenAt().toLocalDate(), LocalDate.now(ZoneOffset.UTC));
        return (int) Math.max(0, days);
    }

    /**
     * Minimal public traveler profile for an anonymous shareable link.
     * Reuses the same display-name and rating logic but omits contact preferences.
     *
     * <p>Le lien est partageable, donc ouvert aux visiteurs anonymes ({@code viewerId}
     * nul, aucun masquage). Mais un utilisateur connecté qui ouvre le lien d'un compte
     * qu'il a bloqué (ou qui l'a bloqué) reçoit le même 404 que partout ailleurs : le
     * lien public ne doit pas devenir la porte dérobée du blocage.
     */
    public PublicTravelerProfileResponse getPublicTravelerProfile(UUID userId, UUID viewerId) {
        blockVisibility.assertVisible(viewerId, userId);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        UserRatingsSummaryResponse ratingSummary = ratingService.getUserRatings(userId, 0, 3, viewerId);

        return new PublicTravelerProfileResponse(
                buildDisplayName(user),
                user.getKycStatus() == KycStatus.VERIFIED,
                user.isKiloPro(),
                user.getTotalTrips() + user.getTotalShipments(),
                ratingSummary.averageRating(),
                ratingSummary.ratingCount(),
                buildMemberSince(user),
                List.of()
        );
    }

    /** Délègue à {@link UserEntity#publicDisplayName()} : repli sur le username du compte. */
    private String buildDisplayName(UserEntity user) {
        return user.publicDisplayName();
    }

    private String buildMemberSince(UserEntity user) {
        Messages m = messagesResolver.forRequest();
        if (user.getCreatedAt() == null) {
            return m.get("profile.member-since.recent");
        }
        String month = user.getCreatedAt()
                .getMonth()
                .getDisplayName(TextStyle.FULL, m.locale());
        int year = user.getCreatedAt().getYear();
        return m.get("profile.member-since", month + " " + year);
    }
}
