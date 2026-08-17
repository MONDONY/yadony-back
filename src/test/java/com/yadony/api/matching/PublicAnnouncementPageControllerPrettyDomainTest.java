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
 * Classe séparée plutôt qu'un test de plus dans
 * {@link PublicAnnouncementPageControllerIntegrationTest} : {@code
 * app.public-base-url} est lu une seule fois, à l'injection du champ — il ne
 * peut pas varier d'un test à l'autre dans le même contexte Spring. La
 * propriété surchargée par {@code @SpringBootTest(properties = ...)} fait
 * démarrer un contexte dédié, distinct du contexte partagé et mis en cache par
 * les autres tests d'intégration.
 *
 * <p>Reproduit la configuration prod visée par {@code .env.prod.template}
 * ({@code PUBLIC_BASE_URL=https://yadony.com}, domaine nu, sans {@code
 * /api/v1}) : c'est le seul cas où l'URL de l'affiche devient <em>vraiment</em>
 * lisible pour un humain, plutôt que techniquement correcte mais encore
 * adossée à l'origine API. Que nginx porte bien la règle de réécriture
 * correspondante n'est pas vérifiable ici — hors du périmètre d'un test
 * Spring, cf. {@code nginx/nginx.conf}.
 */
@SpringBootTest(properties = "app.public-base-url=https://yadony.com")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementPageControllerPrettyDomainTest {

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
    void shareUrl_isPrettyOnceThePublicDomainIsConfigured() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement();

        mockMvc.perform(get("/public/annonce/" + a.getId())
                        .header("User-Agent", "Mozilla/5.0 test"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("https://yadony.com/annonce/" + a.getId())))
                .andExpect(content().string(not(containsString("/api/v1"))));
    }
}
