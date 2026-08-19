package com.yadony.api.matching;

/**
 * Motifs de retrait d'une annonce, <b>catalogues</b>, tels qu'ils sont annonces au voyageur.
 *
 * <p>Pourquoi un catalogue plutot qu'un champ libre : le motif de retrait servait a la fois
 * de note interne dans {@code audit_log} et de corps de notification. Un moderateur ecrivant
 * « Retrait — signale par Awa Ndiaye, ticket #4821 » envoyait litteralement cette phrase a la
 * personne sanctionnee, qui apprenait ainsi <b>qui l'avait signalee</b>. Le seul moyen de
 * l'eviter etait de s'auto-censurer dans la trace d'audit, c'est-a-dire de degrader l'audit
 * pour proteger le signalant.
 *
 * <p>Le catalogue protege le signalant <b>par construction</b> et non par discipline : quoi
 * que le moderateur ecrive dans sa note interne, seul le libelle ci-dessous part au voyageur.
 * La note libre reste disponible, et complete, pour l'audit.
 */
public enum AnnouncementRemovalReason {

    PROHIBITED_ITEM("Objet interdit au transport"),
    SUSPECTED_FRAUD("Soupçon de fraude"),
    INAPPROPRIATE_CONTENT("Contenu inapproprié"),
    MISLEADING_INFO("Informations trompeuses ou inexactes"),
    DUPLICATE("Annonce en double"),
    /** Filet : reste volontairement vague, il est lu par la personne sanctionnée. */
    OTHER("Non conforme aux conditions d'utilisation");

    private final String publicLabel;

    AnnouncementRemovalReason(String publicLabel) {
        this.publicLabel = publicLabel;
    }

    /** Libellé envoyé au voyageur. Ne doit jamais contenir d'information sur le signalant. */
    public String publicLabel() {
        return publicLabel;
    }
}
