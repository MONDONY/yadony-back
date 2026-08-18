package com.yadony.api.matching;

import com.yadony.api.auth.BlockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NEGOTIATING ne doit fuiter dans aucun ensemble de statuts existant")
class BidNegotiationLeakTest {

    @SuppressWarnings("unchecked")
    private static Collection<BidStatus> readStatusSet(Class<?> owner, String fieldName) throws Exception {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Collection<BidStatus>) f.get(null);
    }

    @Test
    @DisplayName("le statut existe")
    void negotiatingExists() {
        assertThat(BidStatus.valueOf("NEGOTIATING")).isEqualTo(BidStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("un bid en négociation n'est ni accepté, ni en route, ni en vol")
    void negotiatingIsNeverAcceptedOrInFlight() {
        assertThat(BidStatus.ACCEPTED_OR_BEYOND).doesNotContain(BidStatus.NEGOTIATING);
        assertThat(BidStatus.EN_ROUTE).doesNotContain(BidStatus.NEGOTIATING);
        assertThat(BidStatus.IN_FLIGHT).doesNotContain(BidStatus.NEGOTIATING);
        assertThat(BidStatus.PHONE_VISIBLE_STATUSES).doesNotContain(BidStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("un bid en négociation n'est jamais compté comme un colis envoyé")
    void negotiatingIsNotAShipment() throws Exception {
        assertThat(readStatusSet(TripsSummaryService.class, "NON_SHIPMENT_STATUSES"))
                .contains(BidStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("une négociation en cours empêche de bloquer la contrepartie")
    void negotiatingCountsAsActiveTransaction() throws Exception {
        assertThat(readStatusSet(BlockService.class, "ACTIVE_STATUSES"))
                .contains(BidStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("la suppression de compte annule aussi les négociations en cours")
    void negotiatingIsCancellableOnAccountDeletion() {
        assertThat(BidService.CANCELLABLE_BID_STATUSES).contains(BidStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("NEGOTIATION_ACTIVE est la source unique du filtre d'invisibilité")
    void negotiationActiveSetIsExposed() {
        assertThat(BidStatus.NEGOTIATION_ACTIVE).containsExactly(BidStatus.NEGOTIATING);
    }

    /**
     * Décision verrouillée ici plutôt que laissée implicite dans le service.
     *
     * <p>Le plan envisageait d'exclure NEGOTIATING localement au seul appel de
     * {@code markArrived}, en supposant que le troisième usage de la constante
     * (« ce spectateur a-t-il déjà une demande sur ce trajet ? ») devait, lui,
     * répondre oui pour un fil ouvert. La lecture du code montre que cet usage
     * n'existe pas : les TROIS références à {@code INACTIVE_BID_STATUSES}
     * ({@code canSeeArrivalInstructions}, {@code markArrived},
     * {@code updateArrivalInstructions}) posent la même question — « ce colis
     * est-il pris en charge par le voyageur ? » — à laquelle un fil de
     * négociation répond non dans les trois cas :
     * <ul>
     *   <li>accès au point de retrait : un négociateur n'a pas de colis à retirer ;</li>
     *   <li>bascule groupée IN_TRANSIT → ARRIVED : un bid NEGOTIATING inclus
     *       deviendrait ARRIVED sans avoir jamais été payé, et sa seule présence
     *       ferait échouer la garde « tous les colis doivent être en transit »,
     *       interdisant au voyageur de marquer son arrivée ;</li>
     *   <li>test « trajet soldé » : un fil ouvert ne doit pas prolonger un trajet
     *       dont tous les colis sont livrés.</li>
     * </ul>
     * La garde anti-doublon de demande vit ailleurs
     * ({@code existsBySenderIdAndAnnouncementIdAndStatusIn} dans {@code BidService}
     * et {@code BidCheckoutService}), où NEGOTIATING est listé explicitement.
     * Les deux sémantiques restent séparées sans dupliquer d'ensemble local.
     */
    @Test
    @DisplayName("un bid en négociation n'est pas un colis pris en charge sur le trajet")
    void negotiatingIsNotAnActiveParcelOnTheTrip() throws Exception {
        assertThat(readStatusSet(AnnouncementService.class, "INACTIVE_BID_STATUSES"))
                .contains(BidStatus.NEGOTIATING);
    }

    /**
     * Verrou du raisonnement ci-dessus : tant que
     * {@code existsByAnnouncementIdAndSenderIdAndStatusNotIn} n'a qu'un seul appelant
     * — la lecture de {@code arrivalInstructions} — la présence de NEGOTIATING dans
     * {@code INACTIVE_BID_STATUSES} ne peut pas déborder sur une autre décision. Un
     * second appelant, lui, devrait être relu avec cette sémantique en tête.
     */
    @Test
    @DisplayName("le filtre INACTIVE ne pilote qu'une seule décision : le point de retrait")
    void inactiveStatusesDriveOnlyTheArrivalInstructionsGate() throws Exception {
        Path service = Path.of("src/main/java/com/yadony/api/matching/AnnouncementService.java");
        long callSites = Files.readAllLines(service).stream()
                .map(String::strip)
                // La constante est documentée en Javadoc, qui cite la requête par son
                // nom : on ne compte que le code, pas les commentaires.
                .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                .filter(l -> l.contains("existsByAnnouncementIdAndSenderIdAndStatusNotIn"))
                .count();

        assertThat(callSites)
                .describedAs("un nouvel appelant de cette requête change la portée de "
                        + "INACTIVE_BID_STATUSES : relire la Javadoc de la constante")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("la garde anti-doublon de demande, elle, voit bien le fil ouvert")
    void negotiatingBlocksASecondBidOnTheSameTrip() {
        // Sémantique inverse de INACTIVE_BID_STATUSES : ici « oui, cet expéditeur
        // a déjà quelque chose en cours sur ce trajet » — un fil de négociation
        // en fait partie, sinon on pourrait négocier ET réserver en parallèle.
        assertThat(BidService.CANCELLABLE_BID_STATUSES).contains(BidStatus.NEGOTIATING);
        assertThat(BidStatus.NEGOTIATION_ACTIVE).contains(BidStatus.NEGOTIATING);
    }
}
