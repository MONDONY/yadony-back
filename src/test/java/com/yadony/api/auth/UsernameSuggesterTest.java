package com.yadony.api.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameSuggesterTest {

    @Test
    void suggest_troisPropositionsValidesDistinctes() {
        List<String> out = UsernameSuggester.suggest("awa", s -> !s.equals("awa"));
        assertThat(out).hasSize(3).doesNotContain("awa").doesNotHaveDuplicates();
        assertThat(out).allSatisfy(s -> assertThat(UsernamePolicy.violation(s)).isEmpty());
    }

    @Test
    void suggest_baseLongueTronquee() {
        List<String> out = UsernameSuggester.suggest("abcdefghijklmnopqrst", s -> true);
        assertThat(out).isNotEmpty();
        assertThat(out).allSatisfy(s -> assertThat(UsernamePolicy.violation(s)).isEmpty());
    }

    @Test
    void suggest_toutPrisRenvoieVide() {
        assertThat(UsernameSuggester.suggest("awa", s -> false)).isEmpty();
    }
}
