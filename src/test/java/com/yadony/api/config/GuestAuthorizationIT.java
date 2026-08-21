package com.yadony.api.config;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
     * succes fonctionnel : la ligne « users » de l'invite n'est provisionnee qu'a la
     * tache suivante, donc le service repond encore 404 a ce stade.
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
     * Amendement A4 : le 200 ne peut pas etre exige a ce stade. Le provisionnement de la
     * ligne invitee arrive a la tache suivante ; ici {@code AlertService} fait encore un
     * {@code orElseThrow}. Ce que cette tache verrouille est la chaine de securite.
     */
    @Test
    @DisplayName("un invite n'est pas refuse par la securite sur ses alertes corridor")
    void guestCanListCorridorAlerts() throws Exception {
        MvcResult result = mockMvc.perform(get("/me/corridor-alerts").with(authentication(guest())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("la chaine de securite doit laisser passer un invite sur ses alertes")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
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
