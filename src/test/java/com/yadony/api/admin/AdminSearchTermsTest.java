package com.yadony.api.admin;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSearchTermsTest {

    @Test
    void uuid_reconnuSeulementSousFormeCanonique() {
        UUID id = UUID.randomUUID();
        assertThat(AdminSearchTerms.uuid(" " + id + " ")).isEqualTo(id);
        assertThat(AdminSearchTerms.uuid("DON-1234")).isNull();
        assertThat(AdminSearchTerms.uuid("x".repeat(36))).isNull();
        assertThat(AdminSearchTerms.uuid(null)).isNull();
    }

    @Test
    void like_minusculesSansJokers_ouNulSiVide() {
        assertThat(AdminSearchTerms.like("  Awa_D%iop ")).isEqualTo("%awadiop%");
        assertThat(AdminSearchTerms.like(" %_ ")).isNull();
        assertThat(AdminSearchTerms.like(null)).isNull();
    }
}
