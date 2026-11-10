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
}
