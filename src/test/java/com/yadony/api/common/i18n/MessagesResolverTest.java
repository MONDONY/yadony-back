package com.yadony.api.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagesResolverTest {

    @AfterEach
    void tearDown() {
        TestMessages.clearRequest();
    }

    @Test
    void forUser_langueDuPortSiPresente() {
        UUID userId = UUID.randomUUID();
        UserLanguageLookup users = mock(UserLanguageLookup.class);
        when(users.languageOf(userId)).thenReturn(Optional.of(AppLanguage.EN));
        MessagesResolver resolver = new MessagesResolver(TestMessages.source(), users);

        assertThat(resolver.forUser(userId).language()).isEqualTo(AppLanguage.EN);
    }

    @Test
    void forUser_frSiPortVide() {
        UUID userId = UUID.randomUUID();
        UserLanguageLookup users = mock(UserLanguageLookup.class);
        when(users.languageOf(userId)).thenReturn(Optional.empty());
        MessagesResolver resolver = new MessagesResolver(TestMessages.source(), users);

        assertThat(resolver.forUser(userId).language()).isEqualTo(AppLanguage.FR);
    }

    @Test
    void forUser_frPourIdNullSansAppelerLePort() {
        UserLanguageLookup users = mock(UserLanguageLookup.class);
        MessagesResolver resolver = new MessagesResolver(TestMessages.source(), users);

        assertThat(resolver.forUser(null).language()).isEqualTo(AppLanguage.FR);
        verify(users, never()).languageOf(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void forRequest_frSansRequete() {
        MessagesResolver resolver = TestMessages.resolver();
        assertThat(resolver.forRequest().language()).isEqualTo(AppLanguage.FR);
    }

    @Test
    void forRequest_enPourAcceptLanguageAnglais() {
        TestMessages.requestWithAcceptLanguage("en-GB");
        MessagesResolver resolver = TestMessages.resolver();
        assertThat(resolver.forRequest().language()).isEqualTo(AppLanguage.EN);
    }

    @Test
    void forRequest_frPourAcceptLanguageFrancais() {
        TestMessages.requestWithAcceptLanguage("fr");
        MessagesResolver resolver = TestMessages.resolver();
        assertThat(resolver.forRequest().language()).isEqualTo(AppLanguage.FR);
    }

    @Test
    void of_rendLesMessagesDeLaLangueDemandee() {
        MessagesResolver resolver = TestMessages.resolver();
        assertThat(resolver.of(AppLanguage.EN).get("validation.language.required"))
                .isEqualTo("Language is required");
    }
}
