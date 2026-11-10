package com.yadony.api.videomodal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=10",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "  https://www.youtube.com/embed/dQw4w9WgXcQ/  "
    })
    void reconnaitLesFormesAdmises(String url) {
        assertThat(YoutubeUrls.videoId(url)).contains("dQw4w9WgXcQ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "https://vimeo.com/123456789",
            "https://youtu.be/court",
            "https://youtu.be/dQw4w9WgXcQ12",
            "https://www.youtube.com/watch?list=abc",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/channel/dQw4w9WgXcQ",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "ftp://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9W$XcQ",
            "https://youtu.be/ dQw4w9WgXcQ",
            "javascript:alert(1)"
    })
    void refuseLeReste(String url) {
        assertThat(YoutubeUrls.videoId(url)).isEmpty();
    }
}
