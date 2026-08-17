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
 * Contexte Spring séparé, {@code app.store.os-redirect-enabled=true} : c'est
 * la configuration visée le jour où l'application est publiée sur les deux
 * stores. Tant que ce flag est faux (le défaut, testé dans
 * {@link PublicAnnouncementPageControllerIntegrationTest}), rien de tout ceci
 * ne s'exécute — cette classe documente le comportement <em>futur</em>.
 */
@SpringBootTest(properties = {
        "app.store.os-redirect-enabled=true",
        "app.store.android=https://play.google.com/store/apps/details?id=com.yadony.yadony",
        "app.store.ios=https://apps.apple.com/app/yadony/id0000000000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementPageControllerStoreRedirectTest {

    private static final String ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36";
    private static final String IOS_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

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
    void androidVisitor_getsThePlayStoreAsThePrimaryButton() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", ANDROID_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("href=\"https://play.google.com/store/apps/details?id=com.yadony.yadony\"")))
                .andExpect(content().string(containsString("Télécharger l'application")))
                // Le bouton générique disparaît : montrer AUSSI le lien App Store
                // à un visiteur Android serait pire qu'un bouton en moins.
                .andExpect(content().string(not(containsString("Télécharger Yadony"))));
    }

    @Test
    void iosVisitor_getsTheAppStoreAsThePrimaryButton() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", IOS_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("href=\"https://apps.apple.com/app/yadony/id0000000000\"")))
                .andExpect(content().string(containsString("Télécharger l'application")))
                .andExpect(content().string(not(containsString("Télécharger Yadony"))));
    }

    /**
     * Un ordinateur n'a pas de store mobile à ouvrir : le bouton retombe sur le
     * lien {@code dony://} plutôt que d'envoyer quelqu'un vers un store au
     * hasard.
     */
    @Test
    void desktopVisitor_fallsBackToTheDeepLink() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", DESKTOP_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("dony://annonce/" + a.getId())))
                .andExpect(content().string(containsString("Ouvrir dans l'application")));
    }
}
