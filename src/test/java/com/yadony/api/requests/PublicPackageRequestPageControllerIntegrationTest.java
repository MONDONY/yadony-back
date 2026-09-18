package com.yadony.api.requests;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * La page publique d'une demande de colis est le point d'arrivée du lien qu'un
 * expéditeur poste sur ses propres canaux (WhatsApp, Facebook). Calqué sur
 * {@code PublicAnnouncementPageControllerIntegrationTest}, son équivalent côté trajet.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicPackageRequestPageControllerIntegrationTest {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15";

    @Autowired MockMvc mockMvc;
    @Autowired PackageRequestRepository packageRequestRepository;

    private PackageRequestEntity persistRequest(PackageRequestStatus status) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(15));
        e.setDateToleranceDays((short) 3);
        e.setWeightKg(new BigDecimal("10.00"));
        e.setParcelSize(ParcelSize.MEDIUM);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("Vêtements & tissus");
        e.setTargetPriceEur(new BigDecimal("20.00"));
        e.setPickupNeighborhood("18e arrondissement");
        e.setDeliveryNeighborhood("Plateau");
        e.setStatus(status);
        e.setCurrency("EUR");
        e.setRecipientName("Awa Diop");
        e.setRecipientPhone("+221771234567");
        e.setRecipientCity("Dakar");
        return packageRequestRepository.saveAndFlush(e);
    }

    @Test
    void openRequest_isServedWithoutAuthentication() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(view().name("public/demande"))
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("Dakar")));
    }

    @Test
    void negotiatingRequest_isAlsoServed() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.NEGOTIATING);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dakar")));
    }

    @Test
    void draftRequest_isNotExposedPublicly() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.DRAFT);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void cancelledRequest_rendersUnavailableState() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.CANCELLED);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void acceptedRequest_isNotExposedPublicly() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.ACCEPTED);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void completedRequest_isNotExposedPublicly() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.COMPLETED);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void expiredRequest_isNotExposedPublicly() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.EXPIRED);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void unknownRequest_rendersUnavailableState() throws Exception {
        mockMvc.perform(get("/public/demande/" + UUID.randomUUID()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void malformedIdentifier_doesNotFailTheRequest() throws Exception {
        mockMvc.perform(get("/public/demande/pas-un-uuid").header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")));
    }

    @Test
    void newAliasPath_alsoServesTheSamePage() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(view().name("public/demande"))
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("Dakar")));
    }

    @Test
    void openRequest_exposesDeepLinkAndCanonicalShareUrl() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("yadony://demande/" + r.getId())))
                .andExpect(content().string(containsString("/demande/" + r.getId())));
    }

    @Test
    void openRequest_exposesOpenGraphTags() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("og:title")))
                .andExpect(content().string(containsString("og:description")))
                .andExpect(content().string(containsString("og:url")))
                .andExpect(content().string(containsString("Paris vers Dakar")));
    }

    /**
     * Le prix affiché doit être le brut expéditeur (commission comprise), jamais le net
     * de 20,00 saisi par l'expéditeur pour le voyageur : afficher ce dernier annoncerait
     * un montant que personne ne paiera réellement.
     */
    @Test
    void publicPage_showsGrossPriceNotTravelerNet() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(">20<"))));
    }

    @Test
    void requestWithoutBudget_showsNoPriceBlockAtAll() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);
        r.setTargetPriceEur(null);
        packageRequestRepository.saveAndFlush(r);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("budget proposé"))));
    }

    @Test
    void publicPage_showsContentCategoryWeightAndSize() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vêtements &amp; tissus")))
                .andExpect(content().string(containsString("10 kg")))
                .andExpect(content().string(containsString("Colis moyen")));
    }

    @Test
    void publicPage_showsNeighborhoodsOnly() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("18e arrondissement")))
                .andExpect(content().string(containsString("Plateau")));
    }

    /**
     * Aucune donnée personnelle du destinataire ne doit fuiter : ni son identité, ni son
     * numéro, ni une adresse précise. Seuls les quartiers, déjà publics dans le feed de
     * recherche de l'application, apparaissent.
     */
    @Test
    void publicPage_neverLeaksRecipientPersonalData() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Awa Diop"))))
                .andExpect(content().string(not(containsString("+221771234567"))));
    }

    @Test
    void publicPage_showsTheRealLogo() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"/logo.png\"")));
    }

    @Test
    void primaryCta_staysOnDeepLinkWhenStoreRedirectFlagIsOff() throws Exception {
        PackageRequestEntity r = persistRequest(PackageRequestStatus.OPEN);

        mockMvc.perform(get("/public/demande/" + r.getId())
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("yadony://demande/" + r.getId())))
                .andExpect(content().string(containsString("Proposer mon trajet")));
    }
}
