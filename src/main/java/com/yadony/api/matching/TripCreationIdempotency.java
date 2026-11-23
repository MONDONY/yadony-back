package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRequest;
import com.yadony.api.matching.dto.TripResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Création de trajet idempotente : en-tête {@code Idempotency-Key} de {@code POST /announcements} et de
 * {@code POST /announcements/trips}. Quand le trajet est créé mais que la réponse se perd (réseau mobile,
 * délai dépassé, 502), l'app renvoie le formulaire avec la même clé et reçoit le trajet déjà créé au lieu
 * d'un second.
 *
 * <ul>
 *   <li>Sans en-tête (ou vide) : création historique, strictement inchangée (anciennes versions de l'app).</li>
 *   <li>Clé mal formée : 400 {@code idempotency-key-invalid}.</li>
 *   <li>Clé déjà vue pour ce voyageur : rejeu. Le trajet existant est relu AVANT tout contrôle (limite
 *       mensuelle, brouillons, capacité, date…), qui refuserait à tort la répétition ; rien n'est créé,
 *       aucun audit ni événement n'est rejoué.</li>
 *   <li>Clé neuve : création ordinaire, la clé gardée avec le trajet (première étape d'un voyage).</li>
 * </ul>
 *
 * <p>Deux requêtes simultanées de même clé : l'index unique {@code (traveler_id, idempotency_key)} (V400)
 * n'en laisse passer qu'une ; la perdante échoue (violation d'unicité, ou refus d'une limite que la gagnante
 * vient d'atteindre), puis relit dans une transaction NEUVE (la sienne est condamnée) le trajet de la
 * gagnante et le rend comme un rejeu. Aucun cache mémoire : la base seule fait foi, sur plusieurs instances.
 *
 * <p>À appeler hors transaction (le contrôleur) : chaque création a ainsi sa propre transaction.
 * La clé n'apparaît jamais dans une réponse ni dans les journaux.
 */
@Service
public class TripCreationIdempotency {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** 8 à 64 caractères sûrs : un UUID convient. */
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final Logger log = LoggerFactory.getLogger(TripCreationIdempotency.class);

    private final AnnouncementService announcementService;
    private final TripGroupService tripGroupService;
    private final TransactionTemplate freshReadOnly;

    public TripCreationIdempotency(AnnouncementService announcementService,
                                   TripGroupService tripGroupService,
                                   PlatformTransactionManager transactionManager) {
        this.announcementService = announcementService;
        this.tripGroupService = tripGroupService;
        this.freshReadOnly = new TransactionTemplate(transactionManager);
        this.freshReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshReadOnly.setReadOnly(true);
    }

    /** Résultat d'une création : le corps à rendre, et s'il s'agit d'un rejeu. */
    public record Outcome<T>(T body, boolean replayed) {}

    /**
     * @return la clé validée, ou {@code null} quand l'en-tête est absent ou vide
     * @throws YadonyBusinessException 400 {@code idempotency-key-invalid} si la clé est mal formée
     */
    public static String parseKey(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        if (!KEY_FORMAT.matcher(header).matches()) {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "idempotency-key-invalid",
                    "problem.trip-idempotency-key-invalid");
        }
        return header;
    }

    /** Clé déjà prise par une création de l'autre route (trajet direct contre voyage à étapes). */
    static YadonyBusinessException keyReused() {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "idempotency-key-reused",
                "problem.trip-idempotency-key-reused");
    }

    /** {@code POST /announcements}. */
    public Outcome<AnnouncementResponse> createAnnouncement(String firebaseUid, AnnouncementRequest request,
                                                            String keyHeader) {
        String key = parseKey(keyHeader);
        if (key == null) {
            return new Outcome<>(announcementService.createAnnouncement(firebaseUid, request), false);
        }
        return create(() -> announcementService.findIdempotentAnnouncement(firebaseUid, key),
                () -> announcementService.createIdempotentAnnouncement(firebaseUid, request, key));
    }

    /** {@code POST /announcements/trips}. */
    public Outcome<TripResponse> createTrip(String firebaseUid, TripRequest request, String keyHeader) {
        String key = parseKey(keyHeader);
        if (key == null) {
            return new Outcome<>(tripGroupService.createTrip(firebaseUid, request), false);
        }
        return create(() -> tripGroupService.findIdempotentTrip(firebaseUid, key),
                () -> tripGroupService.createIdempotentTrip(firebaseUid, request, key));
    }

    private <T> Outcome<T> create(Supplier<Optional<T>> existing, Supplier<T> creation) {
        Optional<T> replay = existing.get();
        if (replay.isPresent()) {
            log.info("Création de trajet rejouée (Idempotency-Key déjà vue) : rien n'est recréé");
            return new Outcome<>(replay.get(), true);
        }
        try {
            return new Outcome<>(creation.get(), false);
        } catch (RuntimeException failure) {
            Optional<T> raced = readAfterFailure(existing, failure);
            if (raced.isEmpty()) {
                throw failure;
            }
            log.info("Création de trajet concurrente de même Idempotency-Key ({}) : trajet de l'autre requête rendu",
                    failure.getClass().getSimpleName());
            return new Outcome<>(raced.get(), true);
        }
    }

    /**
     * Relit, dans une transaction neuve, le trajet qu'une requête concurrente de même clé a pu créer. Une
     * relecture qui échoue n'efface pas l'échec d'origine : elle s'y ajoute (suppressed) et c'est lui qui
     * remonte, sauf refus métier de la relecture (409 clé prise par l'autre route), plus parlant.
     */
    private <T> Optional<T> readAfterFailure(Supplier<Optional<T>> existing, RuntimeException failure) {
        try {
            Optional<T> found = freshReadOnly.execute(status -> existing.get());
            return found != null ? found : Optional.empty();
        } catch (YadonyBusinessException refusal) {
            throw refusal;
        } catch (RuntimeException readFailure) {
            failure.addSuppressed(readFailure);
            return Optional.empty();
        }
    }
}
