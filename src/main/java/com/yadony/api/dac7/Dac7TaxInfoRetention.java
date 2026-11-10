package com.yadony.api.dac7;

import com.yadony.api.auth.TaxInfoRetentionPort;
import com.yadony.api.auth.UserEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Conservation du NIF à la suppression du compte (décision du contrôleur, relecture n° 1 du lot 3a) : gardé si le
 * voyageur est déclarable et a reçu de l'argent depuis le 1er janvier de l'année courante moins
 * ({@code yadony.dac7.retention-years} − 1), soit par défaut l'année courante et la précédente, ce qu'exige la
 * déclaration de janvier. Sinon effacé comme le reste de l'identité. Durée à confirmer par l'expert-comptable.
 */
@Component
public class Dac7TaxInfoRetention implements TaxInfoRetentionPort {

    private final Dac7ResidencyPolicy residency;
    private final Dac7ReportReadModel readModel;
    private final Dac7Properties properties;
    private final Clock clock;

    public Dac7TaxInfoRetention(Dac7ResidencyPolicy residency, Dac7ReportReadModel readModel,
                                Dac7Properties properties, Clock clock) {
        this.residency = residency;
        this.readModel = readModel;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean mustRetainTaxInfo(UserEntity user) {
        if (user.getTaxIdentificationNumber() == null
                || residency.statusOf(user) != Dac7ResidencyStatus.DECLARABLE) {
            return false;
        }
        int firstYear = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear()
                - (properties.retentionYearsOrDefault() - 1);
        return readModel.hasBeenPaidSince(user.getId(),
                LocalDate.of(firstYear, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
