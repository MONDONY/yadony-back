package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AnnouncementSpecificationTest {

    @Test
    void announcementCurrency_defaultsToEur() {
        AnnouncementEntity announcement = new AnnouncementEntity();

        assertThat(announcement.getCurrency()).isEqualTo("EUR");
    }
}
