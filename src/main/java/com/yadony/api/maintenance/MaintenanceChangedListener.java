package com.yadony.api.maintenance;

import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Après chaque écriture validée : vide le cache du filtre, puis recopie l'état dans le
 * document Firestore {@code config/maintenance}. L'application écoute ce document pour
 * afficher l'écran de maintenance en une ou deux secondes, même sans aucune action de
 * l'utilisateur. La recopie est aussi faite au démarrage, au cas où une écriture aurait
 * échoué côté Firestore.
 *
 * <p>Un échec Firestore n'est jamais bloquant : le 503 du filtre reste la garantie, le
 * document n'est qu'un accélérateur. Les exemptions n'y figurent pas (l'application les
 * apprend par {@code GET /config/maintenance}, authentifié).
 */
@Component
public class MaintenanceChangedListener {

    static final String COLLECTION = "config";
    static final String DOCUMENT = "maintenance";
    private static final Logger log = LoggerFactory.getLogger(MaintenanceChangedListener.class);

    private final MaintenanceCache cache;
    @Nullable
    private final Firestore firestore;

    public MaintenanceChangedListener(MaintenanceCache cache, @Nullable Firestore firestore) {
        this.cache = cache;
        this.firestore = firestore;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(MaintenanceChangedEvent event) {
        cache.evict();
        syncFirestore();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        syncFirestore();
    }

    void syncFirestore() {
        Firestore db = firestore;
        if (db == null) {
            log.debug("Firestore disabled — skipping maintenance sync");
            return;
        }
        try {
            MaintenanceSnapshot snapshot = cache.snapshot();
            Map<String, Object> doc = new HashMap<>();
            doc.put("enabled", snapshot.enabled());
            doc.put("message", snapshot.message());
            doc.put("estimatedEndAt", snapshot.estimatedEndAt() == null ? null : snapshot.estimatedEndAt().toString());
            doc.put("updatedAt", java.time.Instant.now().toString());
            db.collection(COLLECTION).document(DOCUMENT).set(doc).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Maintenance Firestore sync interrupted");
        } catch (Exception e) {
            log.warn("Maintenance Firestore sync failed: {}", e.getMessage());
        }
    }
}
