package com.yadony.api.common.media;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaKeysTest {

    @Test
    void plainKey_isReturnedAsIs() {
        assertThat(MediaKeys.normalize("tracking/b1/1700_x.jpg")).contains("tracking/b1/1700_x.jpg");
        assertThat(MediaKeys.normalize("package_requests/u/1.jpg")).contains("package_requests/u/1.jpg");
    }

    @Test
    void nullBlankOrUnknown_isEmpty() {
        assertThat(MediaKeys.normalize(null)).isEmpty();
        assertThat(MediaKeys.normalize("  ")).isEmpty();
        assertThat(MediaKeys.normalize("autre/u/1.jpg")).isEmpty();
        assertThat(MediaKeys.normalize("https://exemple.com/photo.jpg")).isEmpty();
    }

    @Test
    void presignedPathStyleUrl_isReducedToKey_withoutQuery() {
        String url = "https://acc.r2.cloudflarestorage.com/yadony-prod/package_requests/uid/x.jpg"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc";
        assertThat(MediaKeys.normalize(url)).contains("package_requests/uid/x.jpg");
    }

    @Test
    void virtualHostUrl_isReducedToKey() {
        assertThat(MediaKeys.normalize("http://yadony-staging.r2.dev/users/abc/1.jpg#frag"))
                .contains("users/abc/1.jpg");
    }

    @Test
    void percentEncodedPath_isDecoded() {
        assertThat(MediaKeys.normalize("https://h/b/support/admin/a%20b/1.jpg")).contains("support/admin/a b/1.jpg");
    }

    @Test
    void prefixInsideASegment_isNotAMatch() {
        // « requests/ » apparaît dans « package_requests/ » mais pas en début de segment.
        assertThat(MediaKeys.normalize("package_requests/u/1.jpg")).contains("package_requests/u/1.jpg");
        assertThat(MediaKeys.normalize("https://h/b/package_requests/u/1.jpg")).contains("package_requests/u/1.jpg");
    }

    @Test
    void leadingSlash_isStripped() {
        assertThat(MediaKeys.normalize("/bids/s/1.jpg")).contains("bids/s/1.jpg");
    }

    @Test
    void malformedUrl_fallsBackToManualParsing() {
        assertThat(MediaKeys.normalize("https://h/b/reports/u/1 2.jpg?x=|")).contains("reports/u/1 2.jpg");
    }

    @Test
    void keysUnder_keepsOnlyNormalizedValuesOfThePrefix() {
        List<String> raw = Arrays.asList("tracking/b/1.jpg", "https://h/b/tracking/b/2.jpg?sig=1",
                "bids/s/3.jpg", null, "", "https://h/b/package_requests/u/4.jpg");
        assertThat(MediaKeys.keysUnder("tracking/", raw))
                .containsExactlyInAnyOrder("tracking/b/1.jpg", "tracking/b/2.jpg");
        assertThat(MediaKeys.keysUnder("requests/", raw)).isEmpty();
    }

    @Test
    void everyReconciledPrefix_isRecognized() {
        for (String prefix : PhotoReconciliationService.RECONCILED_PREFIXES) {
            assertThat(MediaKeys.normalize(prefix + "x/1.jpg")).as("préfixe %s", prefix).contains(prefix + "x/1.jpg");
            assertThat(MediaKeys.normalize("https://h/b/" + prefix + "x/1.jpg?sig=1")).contains(prefix + "x/1.jpg");
        }
    }
}
