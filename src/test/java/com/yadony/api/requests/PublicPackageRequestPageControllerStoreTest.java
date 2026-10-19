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

/**
 * Page « Cette demande n'est plus disponible » avec les stores configurés. Mêmes propriétés que
 * {@code PublicAnnouncementPageControllerStoreRedirectTest} : le contexte Spring est partagé.
 */
@SpringBootTest(properties = {
        "app.store.os-redirect-enabled=true",
        "app.store.android=https://play.google.com/store/apps/details?id=com.yadony.yadony",
        "app.store.ios=https://apps.apple.com/app/yadony/id0000000000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicPackageRequestPageControllerStoreTest {

    private static final String ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    @Autowired MockMvc mockMvc;
    @Autowired PackageRequestRepository packageRequestRepository;

    private PackageRequestEntity persistClosedRequest() {
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
        e.setStatus(PackageRequestStatus.COMPLETED);
        e.setCurrency("EUR");
        e.setRecipientName("Awa Diop");
        e.setRecipientPhone("+221771234567");
        e.setRecipientCity("Dakar");
        return packageRequestRepository.saveAndFlush(e);
    }

    @Test
    void unavailablePage_androidVisitor_getsOpenAppAndPlayStoreButtons() throws Exception {
        PackageRequestEntity r = persistClosedRequest();

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", ANDROID_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")))
                .andExpect(content().string(containsString("yadony://demande/" + r.getId())))
                .andExpect(content().string(
                        containsString("href=\"https://play.google.com/store/apps/details?id=com.yadony.yadony\"")))
                .andExpect(content().string(containsString("Télécharger l'app")));
    }

    @Test
    void unavailablePage_desktopVisitor_hasNoStoreButton() throws Exception {
        PackageRequestEntity r = persistClosedRequest();

        mockMvc.perform(get("/public/demande/" + r.getId()).header("User-Agent", DESKTOP_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("yadony://demande/" + r.getId())))
                .andExpect(content().string(not(containsString("Télécharger l'app"))));
    }
}
