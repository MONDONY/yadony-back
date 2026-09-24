package com.yadony.api.auth;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.UserLanguageLookup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implémentation {@code auth} du port {@link UserLanguageLookup} lu par
 * {@code MessagesResolver} (module {@code common.i18n}) : lit et écrit la langue
 * préférée d'un utilisateur (colonne {@code users.preferred_language}, V264).
 *
 * <p>Vit dans {@code auth} et non dans {@code common} pour que {@code common} ne
 * dépende jamais de {@code auth} (règle du dépôt : pas d'injection croisée entre
 * packages hors événements Spring).
 */
@Service
public class UserLanguageService implements UserLanguageLookup {

    private final UserRepository userRepository;

    public UserLanguageService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<AppLanguage> languageOf(UUID userId) {
        return userRepository.findPreferredLanguageById(userId).flatMap(AppLanguage::fromCode);
    }

    /**
     * Met à jour la langue préférée de l'utilisateur identifié par son
     * {@code firebaseUid} et la renvoie. 404 {@code user-not-found} si le compte
     * n'existe pas — même exception que {@link AuthService#getProfile}.
     */
    @Transactional
    public AppLanguage update(String firebaseUid, AppLanguage language) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setPreferredLanguage(language);
        userRepository.save(user);
        return language;
    }
}
