package com.yadony.api.config;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verrou du modele « ferme par defaut » pour les sessions Firebase anonymes.
 *
 * <p>Si quelqu'un ajoute demain un controleur d'engagement sans y penser, ce test
 * doit casser la CI plutot que de laisser un visiteur payer ou publier.
 *
 * <p>Pourquoi {@code @SpringBootTest} + MockMvc et jamais Cucumber : le harnais E2E
 * ({@code e2e/config/TestFirebaseTokenFilter}) surcharge {@code doFilterInternal} sans
 * appeler {@code super} et derive ses autorites d'un en-tete de test. Aucun scenario
 * Cucumber ne peut donc exercer la vraie branche invite. Ici la chaine de filtres
 * reelle tourne, donc {@code SecurityConfig} lui-meme.
 *
 * <p>Pourquoi {@code authentication(...)} et pas {@code @WithMockUser} : le filtre reel
 * pose le Firebase UID (une {@code String}) comme principal, et les controleurs font
 * {@code (String) auth.getPrincipal()}. {@code @WithMockUser} poserait un
 * {@code UserDetails} et ferait echouer ce cast avec un 500 sans rapport avec la
 * securite.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Autorisation des invites (ROLE_GUEST)")
class GuestAuthorizationIT {

    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private UserRepository userRepository;

    /** Exactement ce que pose FirebaseTokenFilter pour une session anonyme. */
    private static UsernamePasswordAuthenticationToken guest() {
        return new UsernamePasswordAuthenticationToken(
                "uid-invite-001", null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    private static UsernamePasswordAuthenticationToken sender() {
        return new UsernamePasswordAuthenticationToken(
                "uid-expediteur-001", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    /** Session Firebase valide sans ligne « users » : le tunnel d'inscription. */
    private static UsernamePasswordAuthenticationToken registering() {
        return new UsernamePasswordAuthenticationToken("uid-en-cours-001", null, List.of());
    }

    // ── Ce qu'un invite PEUT atteindre ────────────────────────────────────────

    @Test
    @DisplayName("un invite peut chercher des trajets")
    void guestCanSearchTrips() throws Exception {
        mockMvc.perform(get("/announcements").param("page", "0").param("size", "5")
                        .with(authentication(guest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un invite peut chercher des colis")
    void guestCanSearchPackageRequests() throws Exception {
        mockMvc.perform(get("/package-requests").param("page", "0").param("size", "5")
                        .with(authentication(guest())))
                .andExpect(status().isOk());
    }

    /**
     * Amendement A7 : le detail d'un colis resout une ligne « users » via
     * {@code requireUserId()}. Un invite n'en a pas (materialisation paresseuse) et
     * recevait donc « user/not-found ». Le code d'erreur atteste que la resolution est
     * devenue tolerante : on echoue desormais sur la ressource absente, pas sur
     * l'identite de l'appelant.
     */
    @Test
    @DisplayName("un invite peut consulter le detail d'un colis (echec sur la ressource, pas sur son identite)")
    void guestCanReadPackageRequestDetail() throws Exception {
        mockMvc.perform(get("/package-requests/" + UNKNOWN_ID).with(authentication(guest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("request/not-found"));
    }

    /**
     * Amendement A3 : l'ajout d'un favori est un PUT, pas un POST.
     *
     * <p>Ce qui est verrouille ici est le passage de la chaine de securite, pas le
     * provisionnement : la cible {@code UNKNOWN_ID} n'existe pas, donc
     * {@code FavoriteService.addFavorite} leve {@code YadonyNotFoundException} juste
     * apres avoir resolu (et provisionne) la ligne invitee — l'exception, non checked,
     * fait annuler toute la transaction @Transactional, provisionnement inclus. Ce test
     * ne prouve donc PAS qu'une ligne est creee en base : seulement que la securite ne
     * bloque pas l'invite avant d'atteindre le service. Voir
     * {@code guestFavoritingRealTripProvisionsGuestRowWithoutRoles} pour la preuve du
     * provisionnement effectif, sur une cible reelle.
     */
    @Test
    @DisplayName("un invite n'est pas refuse par la securite quand il pose un favori")
    void guestCanFavorite() throws Exception {
        MvcResult result = mockMvc.perform(put("/favorites/trip/" + UNKNOWN_ID)
                        .with(authentication(guest())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("la chaine de securite doit laisser passer un invite sur les favoris")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Le detail d'un trajet est une capacite explicite de la doctrine invite. Le
     * verrouiller sur un identifiant inexistant prouve que la chaine de securite laisse
     * passer ET que la resolution ne bute pas sur l'identite de l'appelant : l'echec
     * porte sur la ressource. Sans ce test, un futur {@code orElseThrow} sur la ligne
     * « users » fermerait ce parcours en silence.
     */
    @Test
    @DisplayName("un invite peut consulter le detail d'un trajet")
    void guestCanReadAnnouncementDetail() throws Exception {
        mockMvc.perform(get("/announcements/" + UNKNOWN_ID).with(authentication(guest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("announcement-not-found"));
    }

    /**
     * Un invite doit pouvoir alimenter la liste de favoris qu'il peut lire. Le garde
     * prive {@code enforcePackageRequestRequiresTraveler} n'exigeait que ROLE_TRAVELER
     * et le refusait donc, alors que la recherche de colis lui est ouverte.
     */
    @Test
    @DisplayName("un invite peut poser un favori sur un colis")
    void guestCanFavoritePackageRequest() throws Exception {
        MvcResult result = mockMvc.perform(put("/favorites/package-request/" + UNKNOWN_ID)
                        .with(authentication(guest())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("un invite parcourt les colis, il doit pouvoir en mettre un de cote")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Ronde de correction 1, constat 5 : {@code guestCanFavorite} ne prouve pas le
     * provisionnement (cible inexistante -> transaction annulee avant tout INSERT). Ce
     * test utilise une cible reelle pour que l'ajout reussisse effectivement, et verifie
     * EN BASE que la ligne invitee a ete creee, active, et SANS AUCUN ROLE (A6 : le statut
     * invite reste porte par le token, jamais par la base).
     */
    @Test
    @DisplayName("poser un favori sur une cible reelle provisionne la ligne invitee, sans role")
    void guestFavoritingRealTripProvisionsGuestRowWithoutRoles() throws Exception {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTravelerId(UUID.randomUUID()); // pas l'invite lui-meme
        trip.setDepartureCity("Paris");
        trip.setArrivalCity("Dakar");
        trip.setDepartureDate(LocalDate.now().plusDays(3));
        trip.setTransportMode(TransportMode.PLANE);
        trip.setPickupAddressLabel("Gare du Nord, Paris");
        trip.setPickupLat(new BigDecimal("48.880756"));
        trip.setPickupLng(new BigDecimal("2.354987"));
        trip.setDeliveryAddressLabel("Aeroport Blaise Diagne, Dakar");
        trip.setDeliveryLat(new BigDecimal("14.740"));
        trip.setDeliveryLng(new BigDecimal("-17.490"));
        trip.setAvailableKg(new BigDecimal("20.00"));
        trip.setTotalKg(new BigDecimal("23.00"));
        trip.setPricePerKg(new BigDecimal("8.00"));
        trip.setTimezone("Europe/Paris");
        trip.setStatus(AnnouncementStatus.ACTIVE);
        trip = announcementRepository.saveAndFlush(trip);

        String guestUid = "uid-invite-favori-reel-001";
        UsernamePasswordAuthenticationToken realGuest = new UsernamePasswordAuthenticationToken(
                guestUid, null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));

        mockMvc.perform(put("/favorites/trip/" + trip.getId())
                        .with(authentication(realGuest)))
                .andExpect(status().isOk());

        UserEntity created = userRepository.findByFirebaseUid(guestUid).orElseThrow(
                () -> new AssertionError("La ligne invitee aurait du etre provisionnee"));
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getRoles()).isEmpty();
    }

    // ── Ce qu'un invite ne peut PAS atteindre ─────────────────────────────────

    @Test
    @DisplayName("un invite ne peut PAS ouvrir un paiement")
    void guestCannotPay() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Le verrou central. {@code AnnouncementController} ne porte aucun
     * {@code @PreAuthorize} : sans la regle {@code anyRequest()} de SecurityConfig, un
     * invite atteindrait la publication et se ferait refuser par la validation du corps
     * (400), pas par la securite. C'est ce test qui prouve reellement le refus par defaut.
     */
    @Test
    @DisplayName("un invite ne peut PAS publier un trajet")
    void guestCannotPublishAnnouncement() throws Exception {
        mockMvc.perform(post("/announcements")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /** Meme demonstration que ci-dessus : {@code BidController} n'a pas d'annotation non plus. */
    @Test
    @DisplayName("un invite ne peut PAS faire d'offre")
    void guestCannotBid() throws Exception {
        mockMvc.perform(post("/announcements/" + UNKNOWN_ID + "/bids")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un invite ne peut PAS acceder a la messagerie")
    void guestCannotMessage() throws Exception {
        mockMvc.perform(get("/conversations").with(authentication(guest())))
                .andExpect(status().isForbidden());
    }

    /**
     * Verrou d'une DECISION PRODUIT, pas d'une limite technique : les alertes corridor
     * sortent du perimetre invite.
     *
     * <p>{@code AlertService.validateDirection} n'est pas une barriere de permission mais
     * un controle semantique : {@code SENDER_WANTS_TRIPS} exige le role SENDER,
     * {@code TRAVELER_WANTS_PACKAGES} exige TRAVELER. Un invite n'etant ni l'un ni
     * l'autre, aucune direction ne lui convient. La regle retenue est : si une action
     * exige un role, l'invite ne la fait pas, et on ne contourne pas ce controle.
     *
     * <p>Ne pas « reparer » ce test en rouvrant les alertes : ce serait revenir sur la
     * decision. Les favoris, eux, restent ouverts, leur controle de role ne signifiant
     * que « sois un vrai compte ».
     */
    @Test
    @DisplayName("un invite ne peut PAS utiliser les alertes corridor (decision produit)")
    void guestCannotUseCorridorAlerts() throws Exception {
        mockMvc.perform(get("/me/corridor-alerts").with(authentication(guest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Capacite nommee par la doctrine comme devant rester fermee. {@code KycController}
     * ne porte aucun {@code @PreAuthorize} : seule la regle centrale peut produire ce
     * 403, ce qui rend le test d'autant plus necessaire.
     */
    @Test
    @DisplayName("un invite ne peut PAS demarrer une verification d'identite")
    void guestCannotStartKyc() throws Exception {
        mockMvc.perform(post("/kyc/session").with(authentication(guest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un invite ne peut PAS publier un colis")
    void guestCannotPublishPackageRequest() throws Exception {
        mockMvc.perform(post("/package-requests")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Correctif du trou critique : ces deux endpoints d'ecriture sont declares AVANT la
     * regle invite et etaient en {@code authenticated()}, qu'un ROLE_GUEST satisfait. Ils
     * RATTACHENT une identite au compte : un visiteur ne doit jamais les atteindre.
     */
    @Test
    @DisplayName("un invite ne peut PAS rattacher une adresse e-mail a un compte")
    void guestCannotAttachEmail() throws Exception {
        mockMvc.perform(post("/auth/email-otp/attach")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"awa@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un invite ne peut PAS rattacher un numero de telephone a un compte")
    void guestCannotAttachPhone() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/attach")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+33612000001\",\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Meme motif que les deux precedents, pour l'endpoint de reclamation des donnees d'une
     * session anonyme (Task 5). Il MUTE deux comptes : il transfere les favoris d'une session
     * invitee vers le compte appelant, puis supprime la ligne invitee. L'appelant doit donc
     * etre un VRAI compte. Un invite qui l'atteindrait pourrait, en presentant le token
     * anonyme d'un autre visiteur, s'approprier ses favoris sans jamais s'inscrire.
     *
     * <p>{@code /auth/**} etant en {@code permitAll}, la regle explicite doit etre declaree
     * AVANT ce bloc : c'est ce que ce test verrouille.
     */
    @Test
    @DisplayName("un invite ne peut PAS reclamer les donnees d'une session anonyme")
    void guestCannotClaimGuestData() throws Exception {
        mockMvc.perform(post("/auth/guest/claim")
                        .with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestIdToken\":\"un-token\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Amendement A5 : {@code /auth/**} est en {@code permitAll}, la regle invite ne
     * s'applique donc jamais a ce chemin. Le refus vient de la resolution de la ligne
     * « users », absente pour un invite : 404, pas 403. Ne pas « corriger » SecurityConfig
     * pour forcer un 403 ici, cela fermerait aussi le tunnel d'inscription.
     *
     * <p>L'intention verrouillee est « un invite n'obtient pas de profil », pas un code
     * HTTP particulier.
     */
    @Test
    @DisplayName("un invite n'obtient pas de profil (404 et non 403, /auth/** etant permitAll)")
    void guestCannotReadProfile() throws Exception {
        mockMvc.perform(get("/auth/me").with(authentication(guest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    // ── Non-regression : la regle ne doit rien fermer aux autres ──────────────

    @Test
    @DisplayName("un inscrit garde l'acces au paiement (non-regression)")
    void registeredUserStillReachesPayments() throws Exception {
        // Corps vide -> 400 attendu (validation). Ce qui compte est que la chaine de
        // securite ne renvoie PAS 403 : la nouvelle regle ne doit rien fermer aux
        // comptes reels.
        MvcResult result = mockMvc.perform(post("/payments")
                        .with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("un inscrit ne doit jamais etre refuse par la regle invite")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("un utilisateur authentifie SANS role garde l'acces (tunnel d'inscription)")
    void userBeingRegisteredIsNotTreatedAsGuest() throws Exception {
        // Regression majeure a eviter : la regle doit discriminer sur « est invite »,
        // jamais sur « a des roles ». Un compte en cours d'inscription a une liste
        // d'autorites vide et doit continuer a passer.
        MvcResult result = mockMvc.perform(get("/auth/me").with(authentication(registering())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("l'inscription en cours ne doit pas etre confondue avec un invite")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Contrepartie du correctif sur les deux endpoints de rattachement : les fermer aux
     * invites ne doit pas les fermer au tunnel d'inscription, qui est precisement ce
     * qu'ils servent. C'est pourquoi ils sont en « authentifie et non-invite » et jamais
     * en liste de roles : un compte en cours d'inscription n'a aucun role.
     */
    @Test
    @DisplayName("un compte sans role atteint toujours le rattachement d'adresse e-mail")
    void registrationTunnelStillReachesEmailAttach() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/email-otp/attach")
                        .with(authentication(registering()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"awa@example.com\",\"code\":\"123456\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("le rattachement doit rester ouvert au tunnel d'inscription")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Les chemins a identifiant de la liste blanche sont contraints a la forme d'un UUID.
     * Un segment nomme ne matche donc aucune regle de la liste blanche et retombe sur la
     * regle finale, c'est-a-dire ferme. Sans cette contrainte, tout endpoint ajoute demain
     * sous ces prefixes serait ouvert aux invites par defaut : exactement l'inverse de la
     * promesse du modele ferme par defaut.
     */
    @Test
    @DisplayName("un segment non-UUID sous un prefixe de la liste blanche reste ferme a un invite")
    void guestIsNotLetInByNonUuidSegments() throws Exception {
        // Ces chemins n'existent pas aujourd'hui : ce qui est verrouille est qu'un
        // homologue ajoute demain serait refuse, et non ouvert par le joker.
        mockMvc.perform(get("/announcements/export").with(authentication(guest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/package-requests/export").with(authentication(guest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/favorites/bulk/import").with(authentication(guest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/favorites/bulk/import").with(authentication(guest())))
                .andExpect(status().isForbidden());
    }

    /**
     * Le meme piege, mais sur la liste blanche elle-meme : elle enumere des chemins que
     * n'importe quel authentifie atteignait deja. La rediger en {@code hasAnyRole(...)}
     * les fermerait aux comptes a autorites vides. Ce test l'interdit.
     */
    @Test
    @DisplayName("la liste blanche ne ferme pas la recherche a un compte en cours d'inscription")
    void whitelistDoesNotNarrowRegistrationTunnel() throws Exception {
        MvcResult result = mockMvc.perform(get("/announcements").param("page", "0").param("size", "5")
                        .with(authentication(registering())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("la liste blanche ne doit fermer aucun chemin deja ouvert aux authentifies")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }
}
