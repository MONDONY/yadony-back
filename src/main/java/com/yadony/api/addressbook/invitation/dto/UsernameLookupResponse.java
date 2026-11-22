package com.yadony.api.addressbook.invitation.dto;

/**
 * Aperçu minimal d'un compte trouvé par identifiant : rien d'autre ne doit en sortir.
 *
 * <p>{@code parcelDesignation} : signal de capacité. Ce back sait désigner un compte comme destinataire d'un colis
 * (recipientUsername sur la création et le changement de destinataire) ; un ancien back ne renvoie pas le champ et
 * l'app n'offre alors pas « Un utilisateur Yadony » dans un colis.
 */
public record UsernameLookupResponse(String username, String initial, boolean parcelDesignation) {

    /** Forme d'avant le signal : ce back désigne toujours un destinataire par identifiant. */
    public UsernameLookupResponse(String username, String initial) {
        this(username, initial, true);
    }
}
