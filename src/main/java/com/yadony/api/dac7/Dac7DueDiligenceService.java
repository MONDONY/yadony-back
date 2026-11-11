package com.yadony.api.dac7;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Écriture et lecture du registre des diligences DAC7 (annexe III au CGI, art. 344 G quindecies). Point d'entrée
 * unique : le lot 3a y inscrit la réponse du voyageur ({@code PROVIDED}), le lot 3b les demandes et rappels, le lot 3c
 * les décisions de l'équipe. La note est libre et bornée ; elle ne doit jamais contenir de NIF, de numéro de TVA ni
 * d'adresse (règle d'usage, rappelée aux appelants : le texte n'est pas analysé).
 */
@Service
public class Dac7DueDiligenceService {

    static final int MAX_NOTE_LENGTH = 500;

    private final Dac7DueDiligenceLogRepository repository;

    public Dac7DueDiligenceService(Dac7DueDiligenceLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Ajoute une démarche au registre, dans la transaction de l'appelant s'il en a une.
     *
     * @param actorId administrateur auteur d'une décision ; {@code null} pour le système ou le voyageur lui-même
     */
    @Transactional
    public Dac7DueDiligenceLogEntity record(UUID userId, Dac7DueDiligenceAction action, Dac7DueDiligenceChannel channel,
                                            UUID actorId, String note) {
        if (userId == null || action == null || channel == null) {
            throw new IllegalArgumentException("Registre DAC7 : utilisateur, action et canal sont obligatoires");
        }
        String cleaned = note == null || note.isBlank() ? null : note.strip();
        if (cleaned != null && cleaned.length() > MAX_NOTE_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-note-invalid",
                    "Invalid Note", "La note ne doit pas dépasser " + MAX_NOTE_LENGTH + " caractères.");
        }
        return repository.save(new Dac7DueDiligenceLogEntity(userId, action, channel, actorId, cleaned));
    }

    /**
     * Informations fournies par le voyageur dans l'app (source {@link Dac7DiligenceSource#USER}).
     *
     * @param fields codes des champs fournis (jamais une valeur), ex. {@code ADDRESS,BIRTH_DATE}
     */
    @Transactional
    public Dac7DueDiligenceLogEntity recordProvidedByUser(UUID userId, String fields) {
        return repository.save(new Dac7DueDiligenceLogEntity(userId, Dac7DueDiligenceAction.PROVIDED,
                Dac7DueDiligenceChannel.IN_APP, null, note(fields), Dac7DiligenceSource.USER, null));
    }

    /**
     * Preuve de fiabilité (CGI 1649 ter D) : des informations retenues viennent de la pièce vérifiée par Didit. Une
     * seule ligne par session de vérification : un même voyageur revu plusieurs fois n'encombre pas le registre.
     *
     * @return {@code true} si la ligne a été inscrite, {@code false} si cette session y figurait déjà ou est inconnue
     */
    @Transactional
    public boolean recordVerifiedSourceOnce(UUID userId, String verificationRef, String fields) {
        if (userId == null || verificationRef == null || verificationRef.isBlank()
                || repository.existsByUserIdAndSourceAndVerificationRef(userId, Dac7DiligenceSource.DIDIT,
                verificationRef)) {
            return false;
        }
        repository.save(new Dac7DueDiligenceLogEntity(userId, Dac7DueDiligenceAction.PROVIDED,
                Dac7DueDiligenceChannel.IN_APP, null, note(fields), Dac7DiligenceSource.DIDIT, verificationRef));
        return true;
    }

    /**
     * Preuve de fiabilité pour une évaluation de la collecte : si des valeurs retenues viennent de la pièce vérifiée,
     * inscrit (une fois par session) l'identifiant de vérification et les codes des champs concernés.
     */
    @Transactional
    public boolean recordVerifiedSource(UUID userId, Dac7Collection evaluated) {
        if (evaluated == null || evaluated.verificationRef() == null || evaluated.readOnlyFields().isEmpty()) {
            return false;
        }
        return recordVerifiedSourceOnce(userId, evaluated.verificationRef(),
                String.join(",", evaluated.readOnlyFields().stream().map(Enum::name).toList()));
    }

    /** Une démarche avec cette note exacte figure-t-elle déjà au registre ? */
    @Transactional(readOnly = true)
    public boolean alreadyRecorded(UUID userId, Dac7DueDiligenceAction action, String note) {
        return repository.existsByUserIdAndActionAndNote(userId, action, note);
    }

    private static String note(String fields) {
        if (fields == null || fields.isBlank()) {
            return null;
        }
        String cleaned = fields.strip();
        return cleaned.length() > MAX_NOTE_LENGTH ? cleaned.substring(0, MAX_NOTE_LENGTH) : cleaned;
    }
}
