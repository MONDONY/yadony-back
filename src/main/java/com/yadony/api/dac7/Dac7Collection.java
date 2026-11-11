package com.yadony.api.dac7;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Résultat de {@link Dac7CollectionPolicy} : statut de collecte, champs manquants dans l'ordre d'affichage et valeurs
 * déjà connues avec leur source. Jamais persisté. {@link #toString()} ne montre aucune valeur.
 *
 * @param identityKnown      vrai quand les champs d'identité ont été évalués (résident de l'Union, personne physique)
 * @param identityUnavailable le prestataire de vérification n'a pas répondu : seul le profil a été lu
 * @param verificationRef    session Didit dont viennent les valeurs {@link Dac7FieldSource#DIDIT} (jamais exposée)
 */
public record Dac7Collection(Dac7CollectionStatus status, List<Dac7CollectionField> missing, String residenceCountry,
                             boolean identityKnown, String firstName, String lastName, Dac7FieldSource nameSource,
                             LocalDate birthDate, Dac7FieldSource birthDateSource, Dac7Address address,
                             Dac7FieldSource addressSource, boolean identityUnavailable, String verificationRef) {

    public Dac7Collection {
        missing = List.copyOf(missing);
    }

    static Dac7Collection withoutIdentity(Dac7CollectionStatus status, List<Dac7CollectionField> missing,
                                          String residenceCountry) {
        return new Dac7Collection(status, missing, residenceCountry, false, null, null, null, null, null, null, null,
                false, null);
    }

    /**
     * Invite utile : pays inconnu, ou au moins un champ manquant que le voyageur peut fournir lui-même. Un professionnel
     * dont seule l'identité d'entreprise manque ({@link Dac7CollectionField#BUSINESS_IDENTITY}) n'a rien à faire.
     */
    public boolean actionRequired() {
        return status == Dac7CollectionStatus.RESIDENCE_INCONNUE
                || status == Dac7CollectionStatus.INFO_REQUIRED
                && missing.stream().anyMatch(Dac7CollectionField::fillableByTraveler);
    }

    /** Champs repris de la pièce vérifiée, en lecture seule pour le voyageur. */
    public List<Dac7CollectionField> readOnlyFields() {
        List<Dac7CollectionField> fields = new ArrayList<>();
        if (nameSource == Dac7FieldSource.DIDIT) fields.add(Dac7CollectionField.NAME);
        if (birthDateSource == Dac7FieldSource.DIDIT) fields.add(Dac7CollectionField.BIRTH_DATE);
        if (addressSource == Dac7FieldSource.DIDIT) fields.add(Dac7CollectionField.ADDRESS);
        return List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "Dac7Collection[" + status + ", manque " + missing + "]";
    }
}
