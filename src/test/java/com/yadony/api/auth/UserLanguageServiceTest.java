package com.yadony.api.auth;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.AppLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserLanguageServiceTest {

    @Mock UserRepository userRepository;

    UserLanguageService service;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserLanguageService(userRepository);
    }

    @Test
    void languageOf_rendLaLangueEnregistree() {
        when(userRepository.findPreferredLanguageById(userId)).thenReturn(Optional.of("en"));

        Optional<AppLanguage> result = service.languageOf(userId);

        assertThat(result).contains(AppLanguage.EN);
    }

    @Test
    void languageOf_videSiUtilisateurAbsent() {
        when(userRepository.findPreferredLanguageById(userId)).thenReturn(Optional.empty());

        Optional<AppLanguage> result = service.languageOf(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void languageOf_videSiCodeInconnuEnColonne() {
        when(userRepository.findPreferredLanguageById(userId)).thenReturn(Optional.of("de"));

        Optional<AppLanguage> result = service.languageOf(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void update_enregistreEtRendLaLangue() {
        UserEntity user = new UserEntity();
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AppLanguage result = service.update("uid-1", AppLanguage.EN);

        assertThat(result).isEqualTo(AppLanguage.EN);
        assertThat(user.getPreferredLanguage()).isEqualTo(AppLanguage.EN);
        verify(userRepository).save(user);
    }

    @Test
    void update_utilisateurAbsent_leve404UserNotFound() {
        when(userRepository.findByFirebaseUid("uid-inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("uid-inconnu", AppLanguage.EN))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException business = (YadonyBusinessException) e;
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(business.getErrorCode()).isEqualTo("user-not-found");
                });
    }
}
