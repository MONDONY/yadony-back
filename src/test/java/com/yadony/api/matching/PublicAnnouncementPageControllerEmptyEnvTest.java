package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Variables d'environnement <em>définies mais vides</em> — l'état exact du
 * serveur entre le moment où le workflow de déploiement référence une variable
 * GitHub et celui où elle est réellement créée dans Settings > Environments.
 *
 * <p>Le défaut YAML ({@code ${VAR:repli}}) ne couvre <strong>que</strong> la
 * variable absente. Définie et vide, elle écrase le repli. Deux conséquences,
 * toutes deux reproduites ici avant d'avoir été corrigées :
 *
 * <ul>
 *   <li>{@code PUBLIC_BASE_URL=} produisait un {@code og:url} vide sur toutes
 *       les affiches déjà publiées, donc une carte de partage Facebook cassée ;</li>
 *   <li>{@code STORE_OS_REDIRECT_ENABLED=} faisait échouer la conversion en
 *       {@code boolean} et l'application <strong>refusait de démarrer</strong>
 *       ({@code Invalid boolean value []}). Le simple fait que ce contexte
 *       Spring se charge prouve la correction.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.public-base-url=",
        "app.store.os-redirect-enabled=",
        "app.store.android=",
        "app.store.ios="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementPageControllerEmptyEnvTest {

    private static final String ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;

    private AnnouncementEntity persistMinimalAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris 18e");
        a.setPickupLat(new BigDecimal("48.8920"));
        a.setPickupLng(new BigDecimal("2.3550"));
        a.setDeliveryAddressLabel("Dakar Plateau");
        a.setDeliveryLat(new BigDecimal("14.6930"));
        a.setDeliveryLng(new BigDecimal("-17.4470"));
        a.setAvailableKg(new BigDecimal("12.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a);
    }

    @Test
    void emptyPublicBaseUrl_stillProducesAnAbsoluteShareUrl() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", ANDROID_UA))
                .andExpect(status().isOk())
                // Repli sur app.base-url + le context-path, jamais une URL nue.
                .andExpect(content().string(containsString("http")))
                .andExpect(content().string(containsString("/annonce/" + a.getId())))
                // Le symptôme du bug : og:url réduit au seul chemin.
                .andExpect(content().string(not(containsString("content=\"/annonce/"))));
    }

    /**
     * Le contexte se charge malgré {@code os-redirect-enabled} vide, et le
     * bouton retombe sur le lien applicatif plutôt que sur un store non
     * configuré.
     */
    @Test
    void emptyStoreRedirectFlag_bootsAndFallsBackToTheDeepLink() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", ANDROID_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dony://annonce/" + a.getId())))
                .andExpect(content().string(containsString("Ouvrir dans l'application")));
    }
}
