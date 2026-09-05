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
}
