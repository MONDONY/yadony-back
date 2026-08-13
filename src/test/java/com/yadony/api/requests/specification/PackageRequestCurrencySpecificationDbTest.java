package com.yadony.api.requests.specification;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PackageRequestCurrencySpecificationDbTest {

    @Autowired
    private PackageRequestRepository repository;

    @Test
    void packageRequestCurrency_defaultsToEur() {
        PackageRequestEntity request = new PackageRequestEntity();

        assertThat(request.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void hasCurrency_filtersByExactCurrencyWithoutMixingEurAndCad() {
        PackageRequestEntity eurRequest = buildRequest("EUR");
        PackageRequestEntity cadRequest = buildRequest("CAD");
        repository.saveAndFlush(eurRequest);
        repository.saveAndFlush(cadRequest);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.hasCurrency("CAD");
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).containsExactly(cadRequest.getId());
        assertThat(results).extracting(PackageRequestEntity::getCurrency).containsExactly("CAD");
    }

    private PackageRequestEntity buildRequest(String currency) {
        PackageRequestEntity request = new PackageRequestEntity();
        request.setSenderId(UUID.randomUUID());
        request.setDepartureCity("Paris");
        request.setArrivalCity("Dakar");
        request.setDesiredDate(LocalDate.now().plusDays(10));
        request.setDateToleranceDays((short) 2);
        request.setWeightKg(new BigDecimal("5.00"));
        request.setParcelSize(ParcelSize.SMALL);
        request.setTransportMode(TransportMode.PLANE);
        request.setContentCategory("vetements");
        request.setStatus(PackageRequestStatus.OPEN);
        request.setCurrency(currency);
        return request;
    }
}
