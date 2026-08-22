package com.yadony.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.PricingMode;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Verrou de non-fuite : un invite ne doit jamais recevoir de donnee reservee.
 *
 * <p>Depuis l'ouverture des endpoints authentifies aux sessions Firebase anonymes, un
 * visiteur recoit exactement les memes DTO qu'un inscrit sur quatre surfaces : recherche
 * de trajets, detail d'un trajet, recherche de colis, detail d'un colis. Ce test verrouille
 * ce qui sort par ces quatre portes.
 *
 * <h2>Trois filets, et pourquoi il en faut trois</h2>
 *
 * <ol>
 *   <li><b>Liste blanche de cles</b> ({@code CLES_AUTORISEES}) : toute cle JSON, a n'importe
 *       quelle profondeur, doit y figurer. C'est le seul filet qui attrape un <em>elargissement
 *       futur</em> d'un DTO : une liste de noms interdits, par construction, ne connait pas le
 *       nom qui n'existe pas encore.</li>
 *   <li><b>Liste noire de noms</b> ({@code CHAMPS_INTERDITS}) : des noms de champ <em>reels</em>
 *       du depot, verifies absents de ces quatre charges. Redondant avec la liste blanche pour
 *       les cles de premier niveau, mais il nomme explicitement ce qui ne doit jamais passer,
 *       et fait echouer le test avec le bon message.</li>
 *   <li><b>Valeurs sentinelles</b> ({@code VALEURS_SENTINELLES}) : des donnees reservees
 *       reellement ecrites en base par ce test. Une fuite sous un nom de cle inattendu, ou
 *       recopiee dans un champ texte libre, se voit ici et nulle part ailleurs.</li>
 * </ol>
 *
 * <h2>Pourquoi les fixtures ne sont pas negociables</h2>
 *
 * <p>Un test qui interroge une base vide est decoratif : la recherche rend zero resultat, aucun
 * champ ne peut apparaitre, et le test est vert sans avoir rien verifie. Chaque cas commence
 * donc par asserter que la reponse porte au moins un element.
 *
 * <h2>Pourquoi {@code authentication(...)} et pas {@code @WithMockUser}</h2>
 *
 * <p>Meme raison que {@link GuestAuthorizationIT} : le filtre reel pose le UID Firebase (une
 * {@code String}) comme principal et les controleurs font {@code (String) auth.getPrincipal()}.
 * {@code @WithMockUser} poserait un {@code UserDetails} et produirait un 500 sans rapport avec
 * la securite. Cucumber est exclu pour la meme raison qu'ailleurs : son harnais surcharge le
 * filtre d'authentification et n'exerce jamais la vraie branche invite.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Non-fuite de donnees vers les invites")
class GuestDataExposureIT {

    // ── Donnees reservees reellement ecrites en base ──────────────────────────
    // Aucune de ces valeurs ne doit ressortir dans une reponse servie a un invite.

    /** Colonne {@code package_requests.recipient_phone}. */
    private static final String TEL_DESTINATAIRE = "+33612345678";
    /** Colonne {@code package_requests.recipient_name}. */
    private static final String NOM_DESTINATAIRE = "Fatou Sentinelle";
    /** Colonne {@code package_requests.recipient_city}. */
    private static final String VILLE_DESTINATAIRE = "Pikine-Sentinelle";
    /** Colonne {@code package_requests.pickup_address_label} : adresse exacte, post-acceptation. */
    private static final String ADRESSE_RETRAIT_EXACTE = "12 rue Sentinelle Batiment C, 75010 Paris";
    /** Colonne {@code package_requests.delivery_address_label} : adresse exacte, post-acceptation. */
    private static final String ADRESSE_LIVRAISON_EXACTE = "Villa 4231 Sentinelle, Sacre-Coeur 3, Dakar";
    /** Colonne {@code package_requests.disclaimer_signed_ip}. */
    private static final String IP_SIGNATURE = "203.0.113.42";
    /** Colonne {@code package_requests.promo_code} : code a usages comptes, reserve au proprietaire. */
    private static final String CODE_PROMO = "SENTINELLE10";
    /** Colonne {@code users.stripe_account_id} du voyageur. */
    private static final String COMPTE_STRIPE = "acct_sentinelle123";
    /** Colonne {@code announcements.arrival_instructions} : point de rendez-vous physique. */
    private static final String INSTRUCTIONS_ARRIVEE = "Sonner interphone 42B, code portail Sentinelle1974";

    private static final List<String> VALEURS_SENTINELLES = List.of(
            TEL_DESTINATAIRE, NOM_DESTINATAIRE, VILLE_DESTINATAIRE,
            ADRESSE_RETRAIT_EXACTE, ADRESSE_LIVRAISON_EXACTE,
            IP_SIGNATURE, CODE_PROMO, COMPTE_STRIPE, INSTRUCTIONS_ARRIVEE);

    /**
     * Noms de champ REELS du depot, tous absents des quatre charges auditees.
     *
     * <p>Aucun n'est invente : un nom invente ne protege de rien. Chacun existe ailleurs dans
     * le code (DTO de bid, de negociation, de paiement, entites), et c'est precisement pour ca
     * qu'il est ici : le jour ou quelqu'un elargit un DTO de recherche en recopiant un champ
     * depuis l'un de ces voisins, ce test tombe.
     *
     * <p>{@code promoCode} y figure a un titre particulier : il FUYAIT reellement sur
     * {@code GET /package-requests/{id}} avant cette tache. Voir
     * {@code PackageRequestService.toResponse(..., boolean isOwner)}.
     *
     * <p>{@code arrivalInstructions} y figure aussi a un titre particulier : c'est un champ
     * LEGITIME de {@code AnnouncementDetailResponse}, mais masque pour qui n'est ni le voyageur
     * proprietaire ni un expediteur engage sur le trajet. Comme la serialisation est en
     * {@code NON_NULL}, la cle disparait quand la valeur est masquee : sa presence signifierait
     * que le masquage a saute.
     */
    private static final List<String> CHAMPS_INTERDITS = List.of(
            // Identite et contact
            "phoneNumber", "recipientPhone", "recipientName", "recipientCity", "email",
            // Adresses exactes et traces
            "pickupAddressLabel", "deliveryAddressLabel", "arrivalInstructions", "disclaimerSignedIp",
            // Argent et secrets de paiement
            "stripeAccountId", "paymentIntentId", "paymentIntentClientSecret", "clientSecret",
            "totalNetEur", "netEur", "commissionRate",
            // Reserve au proprietaire
            "promoCode");

    /**
     * Toute cle JSON servie a un invite, a n'importe quelle profondeur.
     *
     * <p>Construite en lisant les enregistrements reellement serialises par les quatre
     * endpoints, pas devinee. Le test compare en <em>inclusion</em> et non en egalite : la
     * serialisation est en {@code NON_NULL}, une cle nulle disparait de la charge sans que ce
     * soit une anomalie.
     *
     * <p><b>Que faire quand ce test echoue sur une cle inconnue :</b> ne pas ajouter la cle par
     * reflexe. Se demander d'abord si un visiteur non inscrit a une raison de recevoir cette
     * donnee. Si oui, l'ajouter ici avec le motif ; si non, la rendre conditionnelle au role
     * dans le mapper.
     */
    private static final Set<String> CLES_AUTORISEES = new LinkedHashSet<>(List.of(
            // ── Enveloppes de pagination ──────────────────────────────────────
            // PageResponse (/announcements)
            "content", "page", "size", "totalElements", "totalPages", "last",
            // Page<T> serialise directement (/package-requests)
            "pageable", "number", "numberOfElements", "first", "empty", "sort",
            "pageNumber", "pageSize", "offset", "paged", "unpaged", "sorted", "unsorted",

            // ── AnnouncementSearchResponse + AnnouncementDetailResponse ───────
            "id", "travelerId", "departureCity", "arrivalCity", "departureDate",
            "departureTime", "arrivalTime", "pickupAddress", "deliveryAddress",
            "availableKg", "totalKg",
            // pricePerKg est le NET voyageur, pricePerKgDisplay le brut paye par l'expediteur.
            // Les deux sont servis a tout appelant authentifie depuis l'origine, invite compris.
            // Non retire ici : ce n'est pas propre a l'invite (tout inscrit le recoit), la valeur
            // se deduit du brut et du taux de commission, et le retirer changerait le contrat de
            // l'API pour tout le monde. Signale dans le rapport de la tache comme decision
            // produit a trancher, et verrouille ici pour qu'il ne se propage pas en silence.
            "pricePerKg", "pricePerKgDisplay", "convertedPricePerKg", "convertedCurrency",
            "transportMode", "status", "bidsCount", "confirmedParcelCount", "traveler",
            "description", "acceptedContentTypes", "refusedTypes", "acceptedPaymentMethods",
            "capacityUnit", "cashAccepted", "createdAt", "updatedAt", "pricingMode",
            "priceGridItems", "reservedKg", "surplusEligible", "surplusPublished",
            "handoverDeadline", "isFavorite", "urgent", "currency", "negotiable",
            "availablePaymentMethods",
            // AddressDto : le point de retrait d'un TRAJET est public par construction, c'est
            // sur lui qu'un expediteur decide. A ne pas confondre avec pickupAddressLabel,
            // l'adresse exacte d'un COLIS, qui n'est renseignee qu'apres acceptation.
            "label", "lat", "lng",
            // TravelerProfileDto
            "displayName", "averageRating", "totalTrips", "kiloPro", "isProAccount",
            "kycVerified", "avatarUrl", "acceptsUnverified",
            // AnnouncementPriceGridItemResponse : meme couple net/brut que pricePerKg,
            // meme motif de conservation.
            "unitPriceNet", "unitPriceDisplay",

            // ── PackageRequestSearchResponse + PackageRequestResponse ─────────
            "senderId", "departureLat", "departureLng", "arrivalLat", "arrivalLng",
            "desiredDate", "dateToleranceDays", "weightKg", "parcelSize", "contentCategory",
            "targetPriceEur", "grossPriceEur", "photoUrl",
            // Quartier, pas adresse : c'est la granularite volontairement grossiere servie
            // avant accord.
            "pickupNeighborhood", "deliveryNeighborhood",
            "sender", "totalRatings",
            "photos", "objectKey", "url",
            "matchScore", "matchedTripId", "matchedTripDepartureDate",
            // Portee au lecteur : null pour un invite, qui n'a aucun fil.
            "viewerThreadId", "viewerThreadStatus"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private AnnouncementPriceGridItemRepository gridItemRepository;
    @Autowired private PackageRequestRepository packageRequestRepository;

    private UUID trajetId;
    private UUID colisId;

    /**
     * Une session anonyme telle que la pose {@code FirebaseTokenFilter} : le UID en principal,
     * la seule autorite {@code ROLE_GUEST}, et aucune ligne {@code users} en base.
     *
     * <p>Le UID varie d'un cas a l'autre parce que {@code searchAnnouncements} est
     * {@code @Cacheable} sur une cle qui inclut le UID du lecteur : un UID partage ferait
     * resservir a un cas le resultat calcule pour le precedent, avant que ce cas n'ait seede
     * ses propres donnees.
     */
    private static UsernamePasswordAuthenticationToken invite(String suffixe) {
        return new UsernamePasswordAuthenticationToken(
                "uid-invite-exposition-" + suffixe, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    @BeforeEach
    void seed() {
        // Ordre impose par les dependances : les demandes et trajets referencent users(id).
        packageRequestRepository.deleteAll();
        gridItemRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity voyageur = new UserEntity();
        voyageur.setFirebaseUid("uid-voyageur-exposition");
        voyageur.setFirstName("Moussa");
        voyageur.setLastName("Diallo");
        voyageur.setStatus(UserStatus.ACTIVE);
        voyageur.setKycStatus(KycStatus.VERIFIED);
        voyageur.setRoles(new java.util.HashSet<>(List.of(Role.TRAVELER)));
        voyageur.setStripeAccountId(COMPTE_STRIPE);
        voyageur = userRepository.save(voyageur);

        UserEntity expediteur = new UserEntity();
        expediteur.setFirebaseUid("uid-expediteur-exposition");
        expediteur.setFirstName("Awa");
        expediteur.setLastName("Ndiaye");
        expediteur.setStatus(UserStatus.ACTIVE);
        expediteur.setKycStatus(KycStatus.VERIFIED);
        expediteur.setRoles(new java.util.HashSet<>(List.of(Role.SENDER)));
        expediteur = userRepository.save(expediteur);

        AnnouncementEntity trajet = new AnnouncementEntity();
        trajet.setTravelerId(voyageur.getId());
        trajet.setDepartureCity("Paris");
        trajet.setArrivalCity("Dakar");
        trajet.setDepartureDate(LocalDate.now().plusDays(12));
        trajet.setDepartureTime(LocalTime.of(10, 30));
        trajet.setArrivalTime(LocalTime.of(15, 45));
        trajet.setTransportMode(TransportMode.PLANE);
        trajet.setPickupAddressLabel("Gare du Nord, Paris");
        trajet.setPickupLat(new BigDecimal("48.880756"));
        trajet.setPickupLng(new BigDecimal("2.354987"));
        trajet.setDeliveryAddressLabel("Aeroport Blaise Diagne, Dakar");
        trajet.setDeliveryLat(new BigDecimal("14.740000"));
        trajet.setDeliveryLng(new BigDecimal("-17.490000"));
        trajet.setAvailableKg(new BigDecimal("18.00"));
        trajet.setTotalKg(new BigDecimal("23.00"));
        trajet.setPricePerKg(new BigDecimal("8.00"));
        trajet.setCurrency("EUR");
        trajet.setTimezone("Europe/Paris");
        trajet.setStatus(AnnouncementStatus.ACTIVE);
        trajet.setDescription("Vol direct, bagage en soute disponible.");
        trajet.setCapacityUnit(CapacityUnit.KG_EXACT);
        // MIXED : c'est le seul mode qui remplit priceGridItems, donc le seul qui expose
        // unitPriceNet. Sans lui, l'audit de ce couple de champs ne serait jamais exerce.
        trajet.setPricingMode(PricingMode.MIXED);
        trajet.setNegotiable(true);
        trajet.setHandoverDeadline(LocalDateTime.now().plusDays(10));
        trajet.setAcceptedContentTypes(List.of("vetements", "documents"));
        trajet.setRefusedTypes(List.of("liquides"));
        trajet.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        // Point de rendez-vous physique : reserve aux parties du trajet.
        trajet.setArrivalInstructions(INSTRUCTIONS_ARRIVEE);
        trajet = announcementRepository.save(trajet);
        trajetId = trajet.getId();

        AnnouncementPriceGridItemEntity ligne = new AnnouncementPriceGridItemEntity();
        ligne.setAnnouncementId(trajet.getId());
        ligne.setLabel("Valise cabine");
        ligne.setUnitPriceNet(new BigDecimal("45.00"));
        ligne.setPosition(0);
        gridItemRepository.save(ligne);

        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(expediteur.getId());
        colis.setDepartureCity("Paris");
        colis.setArrivalCity("Dakar");
        colis.setDesiredDate(LocalDate.now().plusDays(14));
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(new BigDecimal("4.50"));
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setDescription("Deux pulls et une paire de chaussures.");
        colis.setTargetPriceEur(new BigDecimal("40.00"));
        colis.setPickupNeighborhood("10e arrondissement");
        colis.setDeliveryNeighborhood("Sacre-Coeur");
        colis.setStatus(PackageRequestStatus.OPEN);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        // Donnees reservees : le code promo appartient a l'expediteur, les adresses exactes
        // et le destinataire ne se revelent qu'apres accord, l'IP est une trace de conformite.
        colis.setPromoCode(CODE_PROMO);
        colis.setPickupAddressLabel(ADRESSE_RETRAIT_EXACTE);
        colis.setPickupLat(new BigDecimal("48.874500"));
        colis.setPickupLng(new BigDecimal("2.357800"));
        colis.setDeliveryAddressLabel(ADRESSE_LIVRAISON_EXACTE);
        colis.setDeliveryLat(new BigDecimal("14.712000"));
        colis.setDeliveryLng(new BigDecimal("-17.463000"));
        colis.setRecipientName(NOM_DESTINATAIRE);
        colis.setRecipientPhone(TEL_DESTINATAIRE);
        colis.setRecipientCity(VILLE_DESTINATAIRE);
        colis.setDisclaimerSignedAt(LocalDateTime.now().minusDays(1));
        colis.setDisclaimerSignedIp(IP_SIGNATURE);
        colis = packageRequestRepository.save(colis);
        colisId = colis.getId();
    }

    // ── Les quatre surfaces ouvertes a un invite ──────────────────────────────

    @Test
    @DisplayName("recherche de trajets : aucune donnee reservee")
    void rechercheDeTrajetsNExposeRien() throws Exception {
        JsonNode corps = lire(get("/announcements").param("page", "0").param("size", "20"), "trajets-recherche");

        assertThat(corps.path("content")).as(
                "sans resultat, aucun champ ne peut apparaitre et l'audit ne verifie rien")
                .isNotEmpty();
        auditer(corps, "GET /announcements");
    }

    @Test
    @DisplayName("detail d'un trajet : aucune donnee reservee")
    void detailDUnTrajetNExposeRien() throws Exception {
        JsonNode corps = lire(get("/announcements/" + trajetId), "trajet-detail");

        assertThat(corps.path("id").asText()).isEqualTo(trajetId.toString());
        assertThat(corps.path("priceGridItems")).as(
                "la grille tarifaire doit etre servie, sinon unitPriceNet n'est jamais audite")
                .isNotEmpty();
        auditer(corps, "GET /announcements/{id}");
    }

    @Test
    @DisplayName("recherche de colis : aucune donnee reservee")
    void rechercheDeColisNExposeRien() throws Exception {
        JsonNode corps = lire(get("/package-requests").param("page", "0").param("size", "20"), "colis-recherche");

        assertThat(corps.path("content")).as(
                "sans resultat, aucun champ ne peut apparaitre et l'audit ne verifie rien")
                .isNotEmpty();
        auditer(corps, "GET /package-requests");
    }

    @Test
    @DisplayName("detail d'un colis : aucune donnee reservee")
    void detailDUnColisNExposeRien() throws Exception {
        JsonNode corps = lire(get("/package-requests/" + colisId), "colis-detail");

        assertThat(corps.path("id").asText()).isEqualTo(colisId.toString());
        auditer(corps, "GET /package-requests/{id}");
    }

    /**
     * Regression nommee de cette tache : le code promo d'une demande fuyait vers tout lecteur.
     *
     * <p>Il est verifie ici en plus de l'audit generique parce que la serialisation est en
     * {@code NON_NULL} : le jour ou quelqu'un remet {@code e.getPromoCode()} sans condition,
     * l'audit generique ne le verrait que si la fixture porte un code promo. Ce cas rend cette
     * dependance explicite plutot qu'accidentelle.
     */
    @Test
    @DisplayName("le code promo d'une demande ne suit jamais le detail servi a un invite")
    void leCodePromoNeSuitPasLeDetailDUnColis() throws Exception {
        assertThat(packageRequestRepository.findById(colisId).orElseThrow().getPromoCode())
                .as("la fixture doit vraiment porter un code promo, sinon ce cas ne prouve rien")
                .isEqualTo(CODE_PROMO);

        JsonNode corps = lire(get("/package-requests/" + colisId), "colis-detail-promo");

        assertThat(corps.has("promoCode"))
                .as("le code promo appartient a l'expediteur, jamais a qui consulte sa demande")
                .isFalse();
    }

    // ── Outillage d'audit ─────────────────────────────────────────────────────

    private JsonNode lire(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requete,
                          String suffixeUid) throws Exception {
        var reponse = mockMvc.perform(requete.with(authentication(invite(suffixeUid))))
                .andReturn().getResponse();
        assertThat(reponse.getStatus())
                .as("un invite doit atteindre cette surface, sinon l'audit porte sur une erreur")
                .isEqualTo(200);
        String brut = reponse.getContentAsString(StandardCharsets.UTF_8);
        assertThat(brut).as("corps vide : rien a auditer").isNotBlank();
        return MAPPER.readTree(brut);
    }

    private static void auditer(JsonNode corps, String surface) {
        Set<String> cles = new TreeSet<>();
        collecterCles(corps, cles);

        for (String interdit : CHAMPS_INTERDITS) {
            assertThat(cles)
                    .as("%s : le champ « %s » ne doit jamais etre servi a un invite", surface, interdit)
                    .doesNotContain(interdit);
        }

        Set<String> inconnues = new TreeSet<>(cles);
        inconnues.removeAll(CLES_AUTORISEES);
        assertThat(inconnues).as(
                "%s : cle(s) non auditee(s) dans la charge servie a un invite. "
                        + "Verifier qu'un visiteur non inscrit a une raison de recevoir cette donnee, "
                        + "puis l'ajouter a CLES_AUTORISEES avec son motif, ou la conditionner au role "
                        + "dans le mapper.", surface)
                .isEmpty();

        String brut = corps.toString();
        for (String sentinelle : VALEURS_SENTINELLES) {
            assertThat(brut).as(
                    "%s : la valeur reservee « %s » est ressortie dans la charge servie a un invite",
                    surface, sentinelle)
                    .doesNotContain(sentinelle);
        }
        // Le numero sans indicatif, au cas ou un mapper le normaliserait avant de le servir.
        assertThat(brut.toLowerCase(Locale.ROOT))
                .as("%s : fragment de numero de telephone dans la charge servie a un invite", surface)
                .doesNotContain(TEL_DESTINATAIRE.substring(1));
    }

    private static void collecterCles(JsonNode noeud, Set<String> accumulateur) {
        if (noeud.isObject()) {
            for (Map.Entry<String, JsonNode> propriete : noeud.properties()) {
                accumulateur.add(propriete.getKey());
                collecterCles(propriete.getValue(), accumulateur);
            }
        } else if (noeud.isArray()) {
            noeud.forEach(enfant -> collecterCles(enfant, accumulateur));
        }
    }
}
