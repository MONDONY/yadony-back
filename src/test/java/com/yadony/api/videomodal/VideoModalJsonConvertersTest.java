package com.yadony.api.videomodal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoModalJsonConvertersTest {

    @Test
    void allerRetour() {
        var h = new VideoModalJsonConverters.Highlights();
        var list = List.of(new VideoModalHighlight(VideoModalIcon.WALLET, "Payer", null));
        String json = h.convertToDatabaseColumn(list);
        assertThat(json).contains("\"icon\":\"WALLET\"");
        assertThat(h.convertToEntityAttribute(json)).isEqualTo(list);

        var t = new VideoModalJsonConverters.Triggers();
        assertThat(t.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(t.convertToEntityAttribute("[\"APP_OPEN\"]")).containsExactly(VideoModalTrigger.APP_OPEN);
        assertThat(t.convertToEntityAttribute(null)).isEmpty();
        assertThat(t.convertToEntityAttribute(" ")).isEmpty();
    }

    @Test
    void colonneIllisible() {
        assertThatThrownBy(() -> new VideoModalJsonConverters.Triggers().convertToEntityAttribute("{pas du json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
