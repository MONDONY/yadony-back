package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import org.springframework.stereotype.Component;

/**
 * Règle du propriétaire (10/11/2026) : on ne demande le NIF qu'aux voyageurs concernés, c'est-à-dire dont le pays de
 * résidence est connu ET déclarable, déjà payés au moins une fois (tous rails), et qui ne l'ont pas encore fourni.
 * On ne redemande jamais nom, date de naissance, adresse ni pays : seulement NIF, pays d'émission et TVA facultative.
 */
@Component
public class Dac7SolicitationPolicy {

    private final Dac7ResidencyPolicy residency;
    private final Dac7ReportReadModel readModel;

    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public Dac7SolicitationPolicy(Dac7ResidencyPolicy residency, Dac7ReportReadModel readModel) {
        this(residency, readModel, java.time.Clock.systemUTC());
    }

    Dac7SolicitationPolicy(Dac7ResidencyPolicy residency, Dac7ReportReadModel readModel, java.time.Clock clock) {
        this.residency = residency;
        this.readModel = readModel;
        this.clock = clock;
    }

    /** Ne lit les paiements que si la réponse en dépend (résident déclarable sans NIF). */
    public Dac7SolicitationStatus statusOf(UserEntity user) {
        if (user.getTaxIdentificationNumber() != null) {
            return Dac7SolicitationStatus.DEJA_FOURNI;
        }
        Dac7ResidencyStatus status = residency.statusOf(user);
        if (status != Dac7ResidencyStatus.DECLARABLE) {
            return Dac7SolicitationStatus.NE_PAS_SOLLICITER;
        }
        // Lot 3b : un résident d'un État partenaire configuré que la DGFiP ne transmet pas cette année (annexe 6.1 :
        // UE27 seulement en 2026) ne serait jamais déclaré : on ne lui demande rien.
        if (!Dac7DgfipRules.transmitted(clock.instant().atZone(java.time.ZoneOffset.UTC).getYear(),
                Dac7ResidencyPolicy.residenceCountryOf(user))) {
            return Dac7SolicitationStatus.NE_PAS_SOLLICITER;
        }
        return decide(status, readModel.hasBeenPaid(user.getId()), false);
    }

    static Dac7SolicitationStatus decide(Dac7ResidencyStatus residency, boolean paidAtLeastOnce,
                                         boolean taxInfoProvided) {
        if (taxInfoProvided) {
            return Dac7SolicitationStatus.DEJA_FOURNI;
        }
        return residency == Dac7ResidencyStatus.DECLARABLE && paidAtLeastOnce
                ? Dac7SolicitationStatus.SOLLICITER
                : Dac7SolicitationStatus.NE_PAS_SOLLICITER;
    }
}
