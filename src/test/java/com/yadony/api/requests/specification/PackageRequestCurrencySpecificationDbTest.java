package com.yadony.api.requests.specification;

import com.yadony.api.requests.entity.PackageRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PackageRequestCurrencySpecificationDbTest {

    @Test
    void packageRequestCurrency_defaultsToEur() {
        PackageRequestEntity request = new PackageRequestEntity();

        assertThat(request.getCurrency()).isEqualTo("EUR");
    }
}
