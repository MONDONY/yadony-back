package com.yadony.api.dac7;

import com.yadony.api.auth.TaxInfoRetentionPort;
import com.yadony.api.auth.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Conservation du NIF à la suppression du compte (relectures n° 1 et 2 du lot 3a).
 * <ul>
 *   <li>DAC7 éteint : rien n'est gardé et rien n'est lu, la suppression se comporte comme avant le lot ;</li>
 *   <li>allumé : NIF gardé si le voyageur est déclarable et a reçu de l'argent depuis le 1er janvier de l'année
 *       courante moins ({@code yadony.dac7.retention-years} − 1), soit par défaut l'année courante et la précédente,
 *       ce qu'exige la déclaration de janvier ;</li>
 *   <li>la lecture se fait dans sa PROPRE transaction (REQUIRES_NEW, lecture seule) : une erreur SQL n'altère jamais
 *       la transaction de suppression (sur PostgreSQL, une requête en échec la rendrait inutilisable). En cas d'erreur,
 *       repli sur « conserver » : une conservation se purge ensuite ({@link Dac7TaxInfoPurgeScheduler}), un
 *       effacement est irréversible.</li>
 * </ul>
 * Durée à confirmer par l'expert-comptable.
 */
@Component
public class Dac7TaxInfoRetention implements TaxInfoRetentionPort {

    private static final Logger log = LoggerFactory.getLogger(Dac7TaxInfoRetention.class);

    private final Dac7ResidencyPolicy residency;
    private final Dac7ReportReadModel readModel;
    private final Dac7Properties properties;
    private final Clock clock;
    private final TransactionOperations ownTransaction;

    @Autowired
    public Dac7TaxInfoRetention(Dac7ResidencyPolicy residency, Dac7ReportReadModel readModel,
                                Dac7Properties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this(residency, readModel, properties, clock, requiresNewReadOnly(transactionManager));
    }

    /** Tests : transaction fournie (ou {@link TransactionOperations#withoutTransaction()}). */
    Dac7TaxInfoRetention(Dac7ResidencyPolicy residency, Dac7ReportReadModel readModel, Dac7Properties properties,
                         Clock clock, TransactionOperations ownTransaction) {
        this.residency = residency;
        this.readModel = readModel;
        this.properties = properties;
        this.clock = clock;
        this.ownTransaction = ownTransaction;
    }

    static TransactionTemplate requiresNewReadOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

    @Override
    public Optional<Retention> retention(UserEntity user) {
        if (!properties.enabledOrDefault() || user.getTaxIdentificationNumber() == null) {
            return Optional.empty();
        }
        String residenceCountry = Dac7ResidencyPolicy.residenceCountryOf(user);
        Retention retention = new Retention(residenceCountry, user.getTravelerStatus());
        try {
            Boolean keep = ownTransaction.execute(status -> shouldRetain(residenceCountry, user.getId()));
            return Boolean.TRUE.equals(keep) ? Optional.of(retention) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("DAC7 : décision de conservation impossible pour le compte {} ({}) : NIF gardé par prudence, "
                    + "purge mensuelle ensuite", user.getId(), e.getClass().getSimpleName());
            return Optional.of(retention);
        }
    }

    /** Règle commune à la suppression et à la purge : résident déclarable payé dans la fenêtre de conservation. */
    boolean shouldRetain(String residenceCountry, UUID userId) {
        return residency.statusOf(residenceCountry) == Dac7ResidencyStatus.DECLARABLE
                && readModel.hasBeenPaidSince(userId, windowStart());
    }

    Instant windowStart() {
        int firstYear = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear()
                - (properties.retentionYearsOrDefault() - 1);
        return LocalDate.of(firstYear, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    int retentionYears() {
        return properties.retentionYearsOrDefault();
    }
}
