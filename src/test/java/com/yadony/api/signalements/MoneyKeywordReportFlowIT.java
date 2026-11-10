package com.yadony.api.signalements;

import com.yadony.api.common.StorageService;
import com.yadony.api.matching.events.BidDescriptionSubmittedEvent;
import com.yadony.api.requests.event.PackageRequestDescriptionSubmittedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bout en bout à travers les vrais beans (proxys transactionnels compris) : l'événement publié
 * dans la transaction de l'envoi ne crée le signalement qu'après le commit, une seule fois par
 * cible, et rien si l'envoi est annulé. Même configuration que {@link ReportPhotoPurgeTransactionIT}
 * pour partager son contexte Spring.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Détection automatique d'envoi d'argent : du colis envoyé au signalement")
class MoneyKeywordReportFlowIT {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StorageService storage;

    @Test
    void colisAvecDesLiasses_unSignalementSystemeApresLeCommit_uneSeuleFois() {
        UUID bidId = UUID.randomUUID();
        BidDescriptionSubmittedEvent event =
                new BidDescriptionSubmittedEvent(bidId, UUID.randomUUID(), "Deux liasses et des vêtements");

        tx.executeWithoutResult(s -> publisher.publishEvent(event));
        tx.executeWithoutResult(s -> publisher.publishEvent(event));

        List<Map<String, Object>> rows = reportsOf(bidId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("SOURCE")).isEqualTo("SYSTEM");
        assertThat(row.get("REASON")).isEqualTo("PROHIBITED_ITEM");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("BID");
        assertThat(row.get("STATUS")).isEqualTo("OPEN");
        assertThat(row.get("REPORTER_ID")).isNull();
        assertThat((String) row.get("DESCRIPTION")).contains("« liasses »");
    }

    @Test
    void demandePublieeAvecDuCash_signaleLaDemande() {
        UUID requestId = UUID.randomUUID();

        tx.executeWithoutResult(s -> publisher.publishEvent(
                new PackageRequestDescriptionSubmittedEvent(requestId, UUID.randomUUID(), "cash pour la famille")));

        assertThat(reportsOf(requestId)).singleElement()
                .satisfies(row -> assertThat(row.get("TARGET_TYPE")).isEqualTo("PACKAGE_REQUEST"));
    }

    @Test
    void envoiAnnule_aucunSignalement() {
        UUID bidId = UUID.randomUUID();

        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new BidDescriptionSubmittedEvent(bidId, UUID.randomUUID(), "cash"));
            s.setRollbackOnly();
        });

        assertThat(reportsOf(bidId)).isEmpty();
    }

    @Test
    void descriptionOrdinaire_aucunSignalement() {
        UUID bidId = UUID.randomUUID();

        tx.executeWithoutResult(s -> publisher.publishEvent(
                new BidDescriptionSubmittedEvent(bidId, UUID.randomUUID(), "Chaussures et livres")));

        assertThat(reportsOf(bidId)).isEmpty();
    }

    private List<Map<String, Object>> reportsOf(UUID targetId) {
        return jdbc.queryForList(
                "SELECT source, reason, target_type, status, reporter_id, description FROM reports WHERE target_id = ?",
                targetId);
    }
}
