package com.yadony.api.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StorageKeysTest {

    private static final String OWNER = "bids/11111111-1111-1111-1111-111111111111/";

    @Test
    void ownedKey_underOwnerPrefix_isAccepted() {
        assertThat(StorageKeys.isOwnedKey(OWNER + "1729500000000_abc.jpg", OWNER)).isTrue();
    }

    // Audit sécu 21/10 : toutes ces clés passaient le simple startsWith(ownerPrefix).
    @ParameterizedTest
    @ValueSource(strings = {
            "../../package_requests/22222222-2222-2222-2222-222222222222/x.jpg",
            "../x.jpg",
            "./x.jpg",
            "a/../../x.jpg",
            "sub//x.jpg",
            "..\\x.jpg",
            "x.jpg\u0000",
            "x\n.jpg",
            ""
    })
    void ownedKey_withEscapingOrMalformedSuffix_isRejected(String suffix) {
        assertThat(StorageKeys.isOwnedKey(OWNER + suffix, OWNER)).isFalse();
    }

    @Test
    void ownedKey_nullOrForeignOrAbsolute_isRejected() {
        assertThat(StorageKeys.isOwnedKey(null, OWNER)).isFalse();
        assertThat(StorageKeys.isOwnedKey("bids/33333333-3333-3333-3333-333333333333/x.jpg", OWNER)).isFalse();
        assertThat(StorageKeys.isOwnedKey("/" + OWNER + "x.jpg", OWNER)).isFalse();
    }

    @Test
    void ownedKey_dotsInsideAFileName_areStillAccepted() {
        assertThat(StorageKeys.isOwnedKey(OWNER + "photo..v2.jpg", OWNER)).isTrue();
        assertThat(StorageKeys.isOwnedKey(OWNER + ".hidden.jpg", OWNER)).isTrue();
    }

    @Test
    void hasTraversal_flagsOnlyDotSegmentsBackslashesAndControlChars() {
        assertThat(StorageKeys.hasTraversal("bids/a/../b/x.jpg")).isTrue();
        assertThat(StorageKeys.hasTraversal("bids/a/./x.jpg")).isTrue();
        assertThat(StorageKeys.hasTraversal("bids\\a\\x.jpg")).isTrue();
        assertThat(StorageKeys.hasTraversal("bids/a/x\u0007.jpg")).isTrue();
        // Clés construites côté serveur : jamais bloquées par le garde-fou.
        assertThat(StorageKeys.hasTraversal("bids/a/1729500000000_abc.jpg")).isFalse();
        assertThat(StorageKeys.hasTraversal("avatars/a/photo..v2.jpg")).isFalse();
        assertThat(StorageKeys.hasTraversal(null)).isFalse();
    }
}
