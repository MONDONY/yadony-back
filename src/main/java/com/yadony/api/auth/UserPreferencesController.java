package com.yadony.api.auth;

import com.yadony.api.auth.dto.UserPreferencesRequest;
import com.yadony.api.auth.dto.UserPreferencesResponse;
import com.yadony.api.common.i18n.AppLanguage;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Préférences du compte courant. Pour l'instant, seule la langue
 * ({@code preferred_language}, V264) : {@link UserLanguageService} la lit et
 * l'écrit, et sert aussi de port {@code UserLanguageLookup} à {@code
 * MessagesResolver} (module {@code common.i18n}).
 *
 * <p>UID récupéré comme {@code UserBusinessPrefsController}
 * ({@link Authentication#getName()}) — {@code SecurityConfig} ferme déjà
 * {@code /users/me/**} à tout appelant non authentifié (401) ou invité
 * ({@code ROLE_GUEST}, 403) via {@code anyRequest().access(authenticatedNonGuest())}.
 */
@RestController
@RequestMapping("/users/me/preferences")
public class UserPreferencesController {

    private final UserLanguageService userLanguageService;

    public UserPreferencesController(UserLanguageService userLanguageService) {
        this.userLanguageService = userLanguageService;
    }

    @PatchMapping
    public ResponseEntity<UserPreferencesResponse> update(Authentication authentication,
                                                            @Valid @RequestBody UserPreferencesRequest request) {
        AppLanguage language = AppLanguage.fromCode(request.language()).orElseThrow();
        AppLanguage updated = userLanguageService.update(authentication.getName(), language);
        return ResponseEntity.ok(new UserPreferencesResponse(updated.code()));
    }
}
