package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Mode « recette » des comptes testeurs (FLUTTER-FA, FLUTTER-FB), réservé à la staging.
 *
 * <p>Un contournement ne s'applique que si les deux verrous sont ouverts :
 * <ol>
 *   <li>la propriété {@code yadony.recette.enabled}, fausse par défaut, vraie seulement dans
 *       {@code application-staging.yml} ;</li>
 *   <li>l'indicateur {@link UserEntity#isRecetteTester()} du compte, posé par un administrateur.</li>
 * </ol>
 * Le profil {@code prod} ferme le mode quelle que soit la propriété : une variable
 * d'environnement mal recopiée ne peut pas l'allumer en production.
 *
 * <p>Contournements couverts : livraison validée avant le départ du trajet
 * ({@code TrackingService}) et expéditeur rattaché comme destinataire de son propre colis
 * ({@code ReceptionLinker}). Chacun écrit une entrée {@code audit_log} dédiée via
 * {@link #recordBypass}.
 */
@Component
public class RecetteMode {

    private static final Logger log = LoggerFactory.getLogger(RecetteMode.class);

    public static final String AUDIT_ENTITY_TYPE = "RECETTE";
    /** Livraison confirmée avant le départ du trajet (FLUTTER-FA). */
    public static final String ACTION_DELIVERY_BEFORE_DEPARTURE = "RECETTE_DELIVERY_BEFORE_DEPARTURE";
    /** Expéditeur rattaché comme destinataire de son propre colis (FLUTTER-FB). */
    public static final String ACTION_SELF_RECIPIENT_LINKED = "RECETTE_SELF_RECIPIENT_LINKED";

    private static final RecetteMode DISABLED = new RecetteMode();

    private final boolean enabled;
    private final AuditService auditService;

    @Autowired
    public RecetteMode(@Value("${yadony.recette.enabled:false}") boolean configured,
                       Environment environment,
                       AuditService auditService) {
        boolean prod = environment.matchesProfiles("prod");
        this.enabled = configured && !prod;
        this.auditService = auditService;
        if (configured && prod) {
            log.error("yadony.recette.enabled=true ignoré : le mode recette est interdit sous le profil prod");
        } else if (enabled) {
            log.warn("Mode recette ACTIF : les comptes testeurs contournent la date de départ et "
                    + "l'interdiction d'être son propre destinataire");
        }
    }

    private RecetteMode() {
        this.enabled = false;
        this.auditService = null;
    }

    /** Mode fermé, pour les services instanciés hors de Spring (tests unitaires). */
    public static RecetteMode disabled() {
        return DISABLED;
    }

    /** Le mode est ouvert dans cet environnement (propriété vraie, profil non prod). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Le mode est ouvert ET {@code user} est un compte testeur. */
    public boolean appliesTo(UserEntity user) {
        return enabled && user != null && user.isRecetteTester();
    }

    /** Trace un contournement dans {@code audit_log}. */
    public void recordBypass(String action, UUID entityId, UUID actorId, Map<String, Object> payload) {
        if (auditService == null) {
            return;
        }
        auditService.log(AUDIT_ENTITY_TYPE, entityId, action, actorId, payload);
    }
}
