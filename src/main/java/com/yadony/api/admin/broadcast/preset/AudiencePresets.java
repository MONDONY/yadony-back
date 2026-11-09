package com.yadony.api.admin.broadcast.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yadony.api.admin.broadcast.preset.AudiencePreset.Message;

import java.util.List;
import java.util.Map;

/**
 * Catalogue des audiences prêtes de l'écran Communications. Chaque entrée est une règle d'audience
 * déjà construite et un modèle de message en français (tutoiement) et en anglais.
 *
 * <p>Les règles n'utilisent que le catalogue fermé de {@code AudienceField} : un test compile chacune
 * d'elles, donc une règle qui cesserait d'être valide fait échouer la construction, pas un envoi.
 * Une langue n'est jamais figée dans la règle : l'écran ajoute la condition « Langue » quand
 * l'administrateur choisit le modèle français ou anglais.
 *
 * <p>Les textes tiennent dans les bornes de {@code BroadcastRequest} (titre 120, texte 500) et ne
 * promettent ni montant ni délai que l'application ne tient pas.
 */
public final class AudiencePresets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<AudiencePreset> ALL = List.of(
            new AudiencePreset("activation", "À activer",
                    "Inscrits depuis moins de 7 jours dont l'identité n'est pas commencée.",
                    and(cond("signed_up", "WITHIN_DAYS", 7), cond("kyc", "IN", list("NOT_STARTED"))),
                    Map.of(
                            "fr", new Message("Bienvenue sur Yadony",
                                    "Ton compte est prêt. Vérifie ton identité en 2 minutes pour publier ton trajet "
                                            + "ou envoyer ton premier colis en toute confiance."),
                            "en", new Message("Welcome to Yadony",
                                    "Your account is ready. Verify your identity in 2 minutes to publish your trip "
                                            + "or send your first parcel with confidence."))),

            new AudiencePreset("identite_abandonnee", "Identité abandonnée",
                    "Ont ouvert la vérification d'identité puis l'ont quittée sans la terminer.",
                    and(cond("kyc_abandoned", "IS", true)),
                    Map.of(
                            "fr", new Message("Ta vérification d'identité n'est pas terminée",
                                    "Tu as interrompu ta vérification en cours de route. Reprends là où tu en étais : "
                                            + "cela prend 2 minutes et te permet de publier ton trajet ou d'envoyer un colis."),
                            "en", new Message("Your identity check isn't finished",
                                    "You stopped halfway. Pick up where you left off: it takes 2 minutes and lets "
                                            + "you publish your trip or send a parcel."))),

            new AudiencePreset("pas_encore_verifies", "Pas encore vérifiés",
                    "Identité non commencée (jamais ouverte ou abandonnée). Le message explique comment se vérifier "
                            + "et rassure sur la pièce d'identité, traitée par Didit et non conservée par Yadony.",
                    and(cond("kyc", "IN", list("NOT_STARTED"))),
                    Map.of(
                            "fr", new Message("Vérifie ton identité en 2 minutes",
                                    "Va dans Moi › Mon compte › Documents d'identité, prends ta pièce d'identité en photo "
                                            + "puis un selfie. Ta pièce est traitée par Didit, notre partenaire de vérification : "
                                            + "Yadony ne la conserve pas et elle est stockée de façon sécurisée chez Didit. "
                                            + "Nous n'y accédons que si un litige avec un utilisateur l'exige."),
                            "en", new Message("Verify your identity in 2 minutes",
                                    "Go to Me › My account › ID documents, take a photo of your ID and a selfie. "
                                            + "Your document is handled by Didit, our verification partner: Yadony does not "
                                            + "keep it and it is stored securely by Didit. We only access it if a dispute "
                                            + "with a user requires it."))),

            new AudiencePreset("identite_refusee", "Identité refusée",
                    "Vérification d'identité refusée : à relancer avec un document lisible.",
                    and(cond("kyc", "IN", list("REJECTED"))),
                    Map.of(
                            "fr", new Message("Ta vérification d'identité a été refusée",
                                    "Cela arrive souvent à cause d'une photo floue ou d'un document coupé. "
                                            + "Réessaie avec une pièce bien lisible et un bon éclairage. "
                                            + "Écris-nous si tu as besoin d'aide."),
                            "en", new Message("Your identity check was declined",
                                    "This often happens with a blurry photo or a cropped document. Try again with "
                                            + "a clear document and good lighting. Write to us if you need help."))),

            new AudiencePreset("verifies_sans_trajet", "Vérifiés sans trajet",
                    "Identité vérifiée mais aucun trajet publié.",
                    and(cond("kyc", "IN", list("VERIFIED")), cond("published_trip", "NEVER")),
                    Map.of(
                            "fr", new Message("Ton identité est vérifiée, publie ton trajet",
                                    "Tu peux maintenant publier ton trajet et proposer ton espace bagage. "
                                            + "Fixe ton prix au kilo et reçois des demandes de colis."),
                            "en", new Message("You're verified, publish your trip",
                                    "You can now publish your trip and offer your luggage space. "
                                            + "Set your price per kilo and start receiving parcel requests."))),

            new AudiencePreset("trajet_bientot", "Trajet qui part bientôt",
                    "Voyageurs dont un trajet encore en vente part dans les 3 jours.",
                    and(cond("trip_departing", "WITHIN_DAYS", 3)),
                    Map.of(
                            "fr", new Message("Ton départ approche",
                                    "Vérifie que ton annonce est à jour (date, kilos disponibles, prix) pour recevoir "
                                            + "des demandes avant de partir."),
                            "en", new Message("Your departure is coming up",
                                    "Check that your listing is up to date (date, available kilos, price) so you "
                                            + "can receive requests before you leave."))),

            new AudiencePreset("versement_a_configurer", "Versement à configurer",
                    "Voyageurs qui ont publié un trajet mais n'ont aucun compte de versement actif.",
                    and(cond("published_trip", "EVER"),
                            cond("stripe_connect", "IN", list("NOT_CREATED", "PENDING_ONBOARDING")),
                            cond("mobile_money", "IN", list("NOT_CONFIGURED", "DISABLED"))),
                    Map.of(
                            "fr", new Message("Configure ton versement pour être payé",
                                    "Pour recevoir le prix de tes colis, ajoute ton compte de versement "
                                            + "(carte bancaire ou mobile money). C'est rapide et tu le fais une seule fois."),
                            "en", new Message("Set up your payout to get paid",
                                    "To receive the price of your parcels, add your payout account "
                                            + "(bank card or mobile money). It's quick and you only do it once."))),

            new AudiencePreset("solde_a_utiliser", "Solde à utiliser",
                    "Solde Yadony positif et pas de visite depuis 14 jours.",
                    and(cond("wallet_balance", "IS", "POSITIVE"), cond("last_seen", "OLDER_THAN_DAYS", 14)),
                    Map.of(
                            "fr", new Message("Il te reste du crédit sur Yadony",
                                    "Ton solde Yadony est disponible pour tes prochains envois. "
                                            + "Passe l'utiliser quand tu veux."),
                            "en", new Message("You still have credit on Yadony",
                                    "Your Yadony balance is available for your next shipments. "
                                            + "Drop by and use it whenever you like."))),

            new AudiencePreset("promo_non_utilise", "Code promo non utilisé",
                    "Inscrits depuis 3 à 14 jours qui n'ont utilisé aucun code promo.",
                    and(cond("promo_used", "NEVER"), cond("signed_up", "WITHIN_DAYS", 14),
                            cond("signed_up", "OLDER_THAN_DAYS", 3)),
                    Map.of(
                            "fr", new Message("Un avantage de bienvenue t'attend",
                                    "Tu as peut-être un code promo à utiliser sur ton premier envoi. "
                                            + "Ajoute-le dans ton portefeuille avant de payer pour en profiter."),
                            "en", new Message("A welcome offer is waiting for you",
                                    "You may have a promo code for your first shipment. "
                                            + "Add it to your wallet before paying to benefit from it."))),

            new AudiencePreset("inactifs_3j", "Pas ouvert l'app depuis 3 jours",
                    "Comptes de plus de 3 jours qui n'ont pas ouvert l'application depuis au moins 3 jours.",
                    and(cond("last_seen", "OLDER_THAN_DAYS", 3), cond("signed_up", "OLDER_THAN_DAYS", 3)),
                    Map.of(
                            "fr", new Message("On ne t'a pas vu depuis quelques jours",
                                    "Il y a du nouveau sur Yadony : de nouveaux trajets et des voyageurs prêts "
                                            + "à emporter ton colis. Reviens y jeter un œil."),
                            "en", new Message("We haven't seen you in a few days",
                                    "There's news on Yadony: new trips and travelers ready to carry your parcel. "
                                            + "Come and take a look."))),

            new AudiencePreset("reactivation_30j", "Réactivation 30 jours",
                    "Déjà actifs (trajet, demande ou colis) mais pas revenus depuis 30 jours.",
                    and(cond("last_seen", "OLDER_THAN_DAYS", 30),
                            or(cond("published_trip", "EVER"), cond("created_request", "EVER"),
                                    cond("completed_shipments", "GTE", 1), cond("completed_trips", "GTE", 1))),
                    Map.of(
                            "fr", new Message("Ça fait un moment",
                                    "De nouveaux trajets sont disponibles depuis ton dernier passage. "
                                            + "Viens voir les voyageurs qui partent vers l'Afrique."),
                            "en", new Message("It's been a while",
                                    "New trips have been added since your last visit. "
                                            + "Come and see the travelers heading to Africa.")))
    );

    private AudiencePresets() {}

    /** Les audiences prêtes, dans l'ordre d'affichage. */
    public static List<AudiencePreset> all() {
        return ALL;
    }

    private static ObjectNode and(JsonNode... children) {
        return group("AND", children);
    }

    private static ObjectNode or(JsonNode... children) {
        return group("OR", children);
    }

    private static ObjectNode group(String op, JsonNode... children) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("op", op);
        ArrayNode kids = node.putArray("children");
        for (JsonNode child : children) {
            kids.add(child);
        }
        return node;
    }

    private static ObjectNode cond(String field, String operator) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("field", field);
        node.put("operator", operator);
        node.putNull("value");
        return node;
    }

    private static ObjectNode cond(String field, String operator, int value) {
        ObjectNode node = cond(field, operator);
        node.put("value", value);
        return node;
    }

    private static ObjectNode cond(String field, String operator, boolean value) {
        ObjectNode node = cond(field, operator);
        node.put("value", value);
        return node;
    }

    private static ObjectNode cond(String field, String operator, String value) {
        ObjectNode node = cond(field, operator);
        node.put("value", value);
        return node;
    }

    private static ObjectNode cond(String field, String operator, ArrayNode value) {
        ObjectNode node = cond(field, operator);
        node.set("value", value);
        return node;
    }

    private static ArrayNode list(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
