package com.yadony.api.payments.pawapay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PawapayReturnControllerIT {

    @Autowired MockMvc mockMvc;

    @Test
    void return_redirectsToTheAwaitingDeepLink_withoutAuth() throws Exception {
        UUID bidId = UUID.randomUUID();
        mockMvc.perform(get("/pawapay/return/{bidId}", bidId).param("outcome", "success"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://bids/" + bidId + "/mobile-money/awaiting"));
        mockMvc.perform(get("/pawapay/return/{bidId}", bidId).param("outcome", "failed"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://bids/" + bidId + "/mobile-money/awaiting"));
    }

    @Test
    void return_withGarbageId_redirectsToAppRoot() throws Exception {
        mockMvc.perform(get("/pawapay/return/{bidId}", "not-a-uuid"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://"));
    }

    /**
     * Revue ronde 1, point 4. {@code "not-a-uuid"} ne prouve que le repli, pas la sécurité.
     * L'id ici contient une sous-chaîne « en-tête forgé » ({@code X-Injected:1}) : si le
     * contrôleur renvoyait un jour {@code bidId} tel quel dans le cas d'erreur (au lieu du
     * repli constant {@code "yadony://"}), ce texte se retrouverait dans l'en-tête
     * {@code Location}. La résistance vient de la structure du code — le bloc {@code catch}
     * ne lit jamais la variable {@code bidId}, il pose toujours la même constante — pas d'un
     * filtrage explicite : ce test l'épingle noir sur blanc plutôt que de laisser ce fait
     * reposer sur la seule lecture du code.
     *
     * <p>Une vraie découpe de réponse HTTP (CRLF littéral ou {@code %0d%0a} dans le segment
     * d'id) a été essayée en premier : elle n'atteint jamais ce contrôleur, {@code
     * StrictHttpFirewall} (bean par défaut de Spring Security, aucun bean {@code HttpFirewall}
     * custom dans ce dépôt) rejette la requête en 400 avant tout dispatch. C'est une couche de
     * défense supplémentaire, en amont de ce test — qui porte donc sur ce que le contrôleur
     * ferait d'un segment mal formé mais recevable, pas sur la résistance au CRLF elle-même
     * (déjà assurée par le framework).
     */
    @Test
    @DisplayName("id contenant un en-tête forgé → repli constant, rien du payload ne fuit dans Location")
    void return_withHeaderInjectionAttemptInId_redirectsToAppRoot_withoutLeakingPayload() throws Exception {
        String injection = "not-a-uuid-X-Injected:1";
        mockMvc.perform(get("/pawapay/return/{bidId}", injection))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://"));
    }

    /**
     * Preuve de bord pour la modification de {@code SecurityConfig} : les deux nouveaux
     * motifs ({@code /pawapay/callbacks/**}, {@code /pawapay/return/**}) ne doivent élargir
     * l'accès public à rien d'autre. {@code /payments/connect/account} est le voisin le
     * plus proche par le nom (paYments vs paWapay) et par le préfixe (/payments/**) : s'il
     * fallait un jour un motif trop large (ex. copier/coller fautif en {@code /payments/**}),
     * c'est cette route qui basculerait en accès public la première. Elle doit donc rester
     * refusée sans jeton, avec le même rejet (401, « Authentication required ») que celui
     * posé par l'entry point de {@code SecurityConfig} — la preuve que le refus vient bien
     * de la chaîne de sécurité, pas d'une garde applicative.
     */
    @Test
    @DisplayName("/payments/connect/account (route protégée voisine) reste refusé sans jeton")
    void neighbourProtectedRoute_stillRequiresAuth() throws Exception {
        mockMvc.perform(get("/payments/connect/account"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    /**
     * Revue ronde 1, point 5. Le mode de défaillance le plus probable d'une insertion dans
     * une liste de varargs n'est pas une faute de préfixe (déjà couverte par
     * {@link #neighbourProtectedRoute_stillRequiresAuth}) mais l'écrasement de l'entrée
     * voisine — ici {@code "/payments/onboarding/return",}, l'entrée immédiatement au-dessus
     * du point d'insertion dans {@code SecurityConfig}, qui n'était couverte par aucun test
     * du dépôt avant cette ronde. Elle doit rester joignable sans jeton.
     */
    @Test
    @DisplayName("/payments/onboarding/return (entrée juste au-dessus de l'insertion) reste public")
    void entryAboveInsertionPoint_remainsPublic() throws Exception {
        mockMvc.perform(get("/payments/onboarding/return"))
                .andExpect(status().isFound());
    }

    /**
     * Tâche 8 : un paiement keyé sur un fil de négociation (pas de bidId) renvoie sur le
     * même /pawapay/return, mais sous /thread/{threadId} — même leçon que {@link #back},
     * l'écran d'attente relit le statut, cette page ne décide de rien.
     */
    @Test
    void back_thread_redirectsToNegotiationAwaitingDeepLink() throws Exception {
        UUID threadId = UUID.randomUUID();
        mockMvc.perform(get("/pawapay/return/thread/{threadId}", threadId).param("outcome", "success"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://negotiations/" + threadId + "/mobile-money/awaiting"));
    }

    @Test
    void back_thread_withGarbageId_redirectsToAppRoot() throws Exception {
        mockMvc.perform(get("/pawapay/return/thread/{threadId}", "not-a-uuid"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "yadony://"));
    }
}
