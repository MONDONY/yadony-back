package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import com.yadony.api.auth.dto.PublicTravelerProfileResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.ratings.RatingService;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProfilePublicService {

    private final UserRepository userRepository;
    private final RatingService ratingService;
    private final UserBusinessPrefsRepository userBusinessPrefsRepository;
    private final StorageService storageService;

    public ProfilePublicService(UserRepository userRepository,
                                RatingService ratingService,
                                UserBusinessPrefsRepository userBusinessPrefsRepository,
                                StorageService storageService) {
        this.userRepository = userRepository;
        this.ratingService = ratingService;
        this.userBusinessPrefsRepository = userBusinessPrefsRepository;
        this.storageService = storageService;
    }

    public ProfilePublicResponse getProfilePublic(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        String displayName = buildDisplayName(user);
        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        int completedBidsCount = user.getTotalTrips() + user.getTotalShipments();
        String memberSince = buildMemberSince(user);

        UserRatingsSummaryResponse ratingSummary = ratingService.getUserRatings(userId, 0, 3);

        UserBusinessPrefsEntity prefs = userBusinessPrefsRepository.findById(userId).orElse(null);
        String contactMode = prefs != null ? prefs.getContactMode() : null;
        Integer responseDelayHours = prefs != null ? prefs.getResponseDelayHours() : null;

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
                new ArrayList<>(user.getLanguages())
        );
    }

    /**
     * Minimal public traveler profile for an anonymous shareable link.
     * Reuses the same display-name and rating logic but omits contact preferences.
     */
    public PublicTravelerProfileResponse getPublicTravelerProfile(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        UserRatingsSummaryResponse ratingSummary = ratingService.getUserRatings(userId, 0, 3);

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
        if (user.getCreatedAt() == null) {
            return "Membre depuis récemment";
        }
        String month = user.getCreatedAt()
                .getMonth()
                .getDisplayName(TextStyle.FULL, Locale.FRENCH);
        int year = user.getCreatedAt().getYear();
        return "Membre depuis " + month + " " + year;
    }
}
