package com.yadony.api.auth;

/**
 * Taux de fiabilité d'un utilisateur affiché sur son profil public (FLUTTER-NV), positif là où
 * le profil montrait des compteurs d'annulations et d'absences.
 *
 * <p><b>Définition.</b> {@code fiabilité = envois menés à bien / envois engagés}, en pourcentage
 * entier, sur toute l'histoire du compte, rôles expéditeur et voyageur confondus :
 * <ul>
 *   <li><b>menés à bien</b> : colis livrés ({@code COMPLETED}) dont il était l'expéditeur ou le
 *       voyageur ({@link UserRepository#countCompletedShipments}) ;</li>
 *   <li><b>échecs qui lui sont imputables</b> : les compteurs de réputation déjà tenus sur le
 *       compte, qui ne retiennent que ce dont il est responsable :
 *       {@code cancellationCount} (voyageur : colis accepté puis annulé par lui, trajet annulé
 *       par lui — une fois par trajet, plus une par colis déjà remis —, retrait d'un expéditeur
 *       après un report qu'il a décidé), {@code noShowCount} (voyageur absent à la remise),
 *       {@code senderCancellationCount} (expéditeur : colis annulé après acceptation, avant
 *       remise), {@code senderHandoverIncidentCount} (expéditeur : absence confirmée au
 *       rendez-vous de remise, annulation après remise) ;</li>
 *   <li><b>engagés</b> = menés à bien + échecs imputables.</li>
 * </ul>
 * Ne comptent donc pas contre lui : une annulation par l'autre partie, une annulation par
 * l'équipe (force majeure, modération) ou système (compte supprimé), l'absence de l'autre
 * partie, une annulation avant toute acceptation, un signalement d'absence encore contesté.
 * Les envois en cours (acceptés, remis, en transit, arrivés) ne comptent ni pour ni contre.
 *
 * <p><b>Affichage.</b> {@code null} sous {@link #MIN_ENGAGED_SHIPMENTS} envois engagés. Arrondi
 * à l'entier le plus proche (demi vers le haut), sans jamais afficher 100 % dès qu'un échec
 * est imputable (99 % au plus).
 */
public final class ReliabilityScore {

    /** En dessous, un pourcentage ne dit rien de la personne. */
    public static final int MIN_ENGAGED_SHIPMENTS = 3;

    private ReliabilityScore() {}

    /** Échecs imputables, lus sur les compteurs de réputation du compte. */
    public static int attributableFailures(UserEntity user) {
        return Math.max(0, user.getCancellationCount())
                + Math.max(0, user.getNoShowCount())
                + Math.max(0, user.getSenderCancellationCount())
                + Math.max(0, user.getSenderHandoverIncidentCount());
    }

    /**
     * @param completed envois menés à bien
     * @param failures  échecs imputables
     * @return pourcentage entier, ou {@code null} sous {@link #MIN_ENGAGED_SHIPMENTS} envois engagés
     */
    public static Integer percent(long completed, long failures) {
        long done = Math.max(0, completed);
        long failed = Math.max(0, failures);
        long engaged = done + failed;
        if (engaged < MIN_ENGAGED_SHIPMENTS) {
            return null;
        }
        long rounded = (done * 200 + engaged) / (engaged * 2);   // demi vers le haut, en entiers
        if (failed > 0 && rounded >= 100) {
            return 99;
        }
        return (int) rounded;
    }
}
