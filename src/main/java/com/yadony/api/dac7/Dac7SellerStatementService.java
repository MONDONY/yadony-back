package com.yadony.api.dac7;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.dto.Dac7StatementsResponse;
import com.yadony.api.dac7.events.Dac7TaxStatementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Récapitulatifs annuels DAC7 (art. 1649 ter D, III du CGI : chaque vendeur déclaré est informé de ce qui est transmis),
 * envoyés SEULEMENT à la demande de l'équipe, jamais automatiquement. Un par voyageur et par année : la ligne de
 * {@code dac7_seller_statements} est réservée avant l'envoi, dans la même transaction que l'événement
 * ({@link Dac7TaxStatementEvent}) ; un second appel ne renvoie rien. Seuls les voyageurs qui partent dans le fichier
 * (aucune exclusion) le reçoivent : aucun message pour un voyageur non déclaré. Montants tels que transmis (euros
 * entiers), aucune donnée d'identité.
 */
@Service
public class Dac7SellerStatementService {

    private static final Logger log = LoggerFactory.getLogger(Dac7SellerStatementService.class);

    private final Dac7DeclarationService declarations;
    private final Dac7AdminReadModel reads;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public Dac7SellerStatementService(Dac7DeclarationService declarations, Dac7AdminReadModel reads,
                                      ApplicationEventPublisher events, AuditService audit,
                                      PlatformTransactionManager transactionManager) {
        this(declarations, reads, events, audit, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    Dac7SellerStatementService(Dac7DeclarationService declarations, Dac7AdminReadModel reads,
                               ApplicationEventPublisher events, AuditService audit, TransactionOperations transactions,
                               Clock clock) {
        this.declarations = declarations;
        this.reads = reads;
        this.events = events;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Dac7StatementsResponse send(Integer yearValue, UUID adminId) {
        int year = declarations.requireYear(yearValue);
        if (!declarations.collectionEnabled()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-collection-disabled", "DAC7 Disabled",
                    "La collecte DAC7 est éteinte : aucun récapitulatif n'est envoyé aux voyageurs.");
        }
        Declaration declaration = declarations.declaration(year, true);
        int sent = 0;
        int skipped = 0;
        Instant now = clock.instant();
        for (Seller seller : declaration.sellers()) {
            if (!seller.exclusions().isEmpty()) {
                skipped++;
                continue;
            }
            try {
                Boolean reserved = transactions.execute(status -> {
                    if (!reads.reserveStatement(seller.userId(), year, adminId, now)) {
                        return false;
                    }
                    events.publishEvent(new Dac7TaxStatementEvent(seller.userId(), year, quarters(seller)));
                    return true;
                });
                if (Boolean.TRUE.equals(reserved)) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Récapitulatif DAC7 {} non réservé (utilisateur {}) : {}", year, seller.userId(),
                        e.getClass().getSimpleName());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("sent", sent);
        payload.put("skipped", skipped);
        audit.log("DAC7_STATEMENTS", UUID.randomUUID(), "DAC7_STATEMENTS_SENT", adminId, payload);
        return new Dac7StatementsResponse(sent, skipped);
    }

    private static List<Dac7TaxStatementEvent.Quarter> quarters(Seller seller) {
        return seller.report().quarters().stream()
                .map(q -> new Dac7TaxStatementEvent.Quarter(q.quarter(), Dac7DeclarationService.roundEuro(q.netEur()),
                        q.operations(), Dac7DeclarationService.roundEuro(q.commissionEur())))
                .toList();
    }
}
