package com.yadony.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeclaredPhoneHasherTest {

    private static final String PHONE = "+2250707070707";

    @Test
    void hash_isDeterministicAndKeyed() {
        DeclaredPhoneHasher a = new DeclaredPhoneHasher("cle-a");
        DeclaredPhoneHasher b = new DeclaredPhoneHasher("cle-b");

        String hash = a.hash(PHONE);

        assertThat(hash).isEqualTo(a.hash(PHONE));
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(hash).doesNotContain("225");
        assertThat(b.hash(PHONE)).isNotEqualTo(hash);
        assertThat(a.hash("+2250707070708")).isNotEqualTo(hash);
    }

    @Test
    void hash_matchesKnownHmacSha256Vector() {
        // RFC 4231 cas 2 : clé « Jefe », message « what do ya want for nothing? ».
        assertThat(new DeclaredPhoneHasher("Jefe").hash("what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void isConfigured_trueWithKey_falseWhenBlankOrNull() {
        assertThat(new DeclaredPhoneHasher("cle").isConfigured()).isTrue();
        assertThat(new DeclaredPhoneHasher("").isConfigured()).isFalse();
        assertThat(new DeclaredPhoneHasher("  ").isConfigured()).isFalse();
        assertThat(new DeclaredPhoneHasher(null).isConfigured()).isFalse();
    }

    @Test
    void hash_throwsClearlyWhenNotConfigured() {
        assertThatThrownBy(() -> new DeclaredPhoneHasher(" ").hash(PHONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DECLARED_PHONE_HASH_KEY");
    }

    @Test
    void hash_rejectsBlankNumber() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle");
        assertThatThrownBy(() -> hasher.hash(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
