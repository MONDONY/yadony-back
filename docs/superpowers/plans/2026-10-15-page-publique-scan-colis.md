# Page publique du QR code colis — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** scanner le QR d'un colis avec l'appareil photo d'un téléphone ouvre une page publique (contenu, trajet, personnes en « Prénom N. », étapes, contact Yadony, bouton « Ouvrir dans Yadony »), au lieu d'un 401.

**Architecture:** Partie A (yadony-back, PR 1) : l'URL déjà encodée dans les QR, `/api/v1/tracking/{bidId}/scan`, devient publique et rend un gabarit Thymeleaf depuis un nouveau `ParcelScanController` ; un endpoint authentifié `GET /receptions/role/{bidId}` dit à l'app le rôle de l'utilisateur sur le colis. Partie B (dony_app, PR 2, jumelle fusionnée après la PR 1) : lien profond `yadony://colis/{bidId}` vers un écran de transition qui appelle cet endpoint et redirige.

**Tech Stack:** Spring Boot 3 / Thymeleaf / Spring Security / Micrometer / JUnit 5 + MockMvc (H2) ; Flutter / flutter_bloc (Cubit) / GoRouter / get_it / bloc_test + mocktail.

**Spec:** `docs/superpowers/specs/2026-10-15-page-publique-scan-colis-design.md` (même worktree). Maquette validée : https://claude.ai/artifact/66GCzHAKTz7jK8JTGAkqie

## Global Constraints

- Jamais sur la page publique : numéro de téléphone, nom de famille complet, valeur déclarée, prix, code de remise (même public), adresse/instructions de retrait.
- Identités : « Prénom N. ». Expéditeur et voyageur via `UserEntity.publicDisplayName()` ; destinataire (texte libre `bid.recipientName`) via `PublicNameFormatter.shortName`.
- Statuts affichés : `ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED, COMPLETED`. Tout autre statut, bid supprimé ou annonce introuvable → page « Ce colis n'est plus actif » (HTTP 200) avec seulement le contact. UUID inconnu ou non UUID → même page, HTTP 404.
- Textes UI en français, **aucun tiret cadratin (—)**, couleurs unies, **aucun dégradé**. `<meta name="robots" content="noindex, nofollow">`.
- Description affichée avec `th:text` uniquement, jamais `th:utext`.
- Configuration : `yadony.support.whatsapp-number` (`SUPPORT_WHATSAPP_NUMBER`, vide par défaut → bouton masqué), `yadony.support.email` (`SUPPORT_EMAIL`, défaut `support@yadony.com`). Liens stores : clés existantes `app.store.ios` / `app.store.android` (masqués si vides).
- Le numéro WhatsApp réel n'est **jamais** committé : seulement dans l'env du serveur.
- Back : packages par feature, pas d'injection de service d'un autre package ; `./mvnw test` vert ; couverture ≥ 90 % sur le code ajouté. Pas de `mvnw` concurrent.
- App : BLoC/Cubit seulement (pas de `setState`), GoRouter seulement, chaînes via `context.l10n`, `flutter analyze` + `flutter test` verts, `tool/check_hardcoded_strings.dart` à 0. Pas de Flutter en parallèle.
- Commits : pas de `Co-Authored-By`, finir par `Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv`.

## Review Focus

1. Description saisie avec du HTML (`<script>alert(1)</script>`) → affichée en texte, jamais interprétée. Test : Task A3.
2. Statut annulé / NO_SHOW / PARCEL_REFUSED → aucune ville ni aucun nom dans le HTML. Test : Task A3.
3. `bid.recipientName` nul, vide, un seul mot, nom composé, espaces multiples → jamais d'exception, jamais de nom complet. Test : Task A1.
4. Ouverture trop large de la sécurité : `/tracking/{id}/qr-code` doit rester 401 sans jeton. Test : Task A3.
5. Lien profond forgé (`yadony://colis/../admin`, `yadony://colis/42`, segment en trop) → ignoré. Test : Task B1.

---

# Partie A — yadony-back (PR 1)

Worktree : `dony-back/.claude/worktrees/page-scan-colis`, branche `feature/page-publique-scan-colis`.
`B` = `src/main/java/com/yadony/api`, `T` = `src/test/java/com/yadony/api`.

### Task A1: `PublicNameFormatter`

**Files:**
- Create: `B/common/PublicNameFormatter.java`
- Test: `T/common/PublicNameFormatterTest.java`

**Interfaces:**
- Produces: `public static String PublicNameFormatter.shortName(String fullName)` ; `public static final String PublicNameFormatter.UNKNOWN = "Non précisé"`.

- [ ] **Step 1: Write the failing test**

```java
package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicNameFormatterTest {

    @Test
    void prenomEtNom_donnePrenomEtInitiale() {
        assertThat(PublicNameFormatter.shortName("Fatou Diop")).isEqualTo("Fatou D.");
    }

    @Test
    void nomCompose_garderPremierMotEtInitialeDuDernier() {
        assertThat(PublicNameFormatter.shortName("Mamadou Lamine Diop")).isEqualTo("Mamadou D.");
    }

    @Test
    void espacesMultiplesEtBords_ignores() {
        assertThat(PublicNameFormatter.shortName("  Awa    ndiaye ")).isEqualTo("Awa N.");
    }

    @Test
    void initialeAccentuee_conservee() {
        assertThat(PublicNameFormatter.shortName("Éric Élégbédé")).isEqualTo("Éric É.");
    }

    @Test
    void unSeulMot_renduTelQuel() {
        assertThat(PublicNameFormatter.shortName("Moussa")).isEqualTo("Moussa");
    }

    @Test
    void nulOuVide_donneNonPrecise() {
        assertThat(PublicNameFormatter.shortName(null)).isEqualTo(PublicNameFormatter.UNKNOWN);
        assertThat(PublicNameFormatter.shortName("   ")).isEqualTo(PublicNameFormatter.UNKNOWN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PublicNameFormatterTest`
Expected: FAIL, compilation error `cannot find symbol PublicNameFormatter`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.yadony.api.common;

import java.util.Locale;

/**
 * Nom affichable par n'importe qui (page publique du QR colis) : prénom et
 * initiale du dernier mot. Un inconnu qui photographie un colis identifie
 * ainsi les personnes sans obtenir leur nom complet.
 */
public final class PublicNameFormatter {

    public static final String UNKNOWN = "Non précisé";

    private PublicNameFormatter() {
    }

    public static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return UNKNOWN;
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        String initial = last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        return parts[0] + " " + initial + ".";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PublicNameFormatterTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/common/PublicNameFormatter.java src/test/java/com/yadony/api/common/PublicNameFormatterTest.java
git commit -m "feat(common): nom public prénom + initiale

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task A2: `ParcelSteps`, étapes de suivi partagées

Extrait de `RecipientController` les libellés d'événement et le calcul de l'étape en cours, pour que la page de suivi et la page du QR disent la même chose. Profite-en pour retirer le tiret cadratin du libellé « en route ».

**Files:**
- Create: `B/tracking/ParcelSteps.java`
- Modify: `B/tracking/RecipientController.java` (méthodes `labelFor` et `resolveCurrentStep`, lignes ~171-193)
- Test: `T/tracking/ParcelStepsTest.java`
- Modify si besoin : tout test qui attend « Colis remis au voyageur — en route » (`grep -rn "en route" src/test`)

**Interfaces:**
- Produces: `static String ParcelSteps.label(TrackingEventType type)` ; `static String ParcelSteps.currentStep(List<TrackingEventEntity> events)` (package-private, classe `final`).

- [ ] **Step 1: Write the failing test**

```java
package com.yadony.api.tracking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParcelStepsTest {

    private static TrackingEventEntity event(TrackingEventType type) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setEventType(type);
        return e;
    }

    @Test
    void aucunEvenement_attenteDuDepart() {
        assertThat(ParcelSteps.currentStep(List.of())).isEqualTo("En attente du scan de départ");
    }

    @Test
    void depart_seul_enRoute_sansTiretCadratin() {
        String step = ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART)));
        assertThat(step).isEqualTo("Remis au voyageur, en route");
        assertThat(step).doesNotContain("—");
    }

    @Test
    void transit_lEmporteSurDepart() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART), event(TrackingEventType.TRANSIT))))
                .isEqualTo("En transit");
    }

    @Test
    void arrivee_lEmporteSurTout() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.TRANSIT), event(TrackingEventType.ARRIVEE))))
                .isEqualTo("Livraison confirmée");
    }

    @Test
    void libelles() {
        assertThat(ParcelSteps.label(TrackingEventType.DEPART)).isEqualTo("Colis remis au voyageur");
        assertThat(ParcelSteps.label(TrackingEventType.TRANSIT)).isEqualTo("En transit");
        assertThat(ParcelSteps.label(TrackingEventType.ARRIVEE)).isEqualTo("Arrivée confirmée");
    }
}
```

Si `TrackingEventEntity` n'a pas de constructeur sans argument public ou de `setEventType`, construire l'événement comme le fait `RecipientControllerIntegrationTest` et adapter seulement le helper `event(...)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ParcelStepsTest`
Expected: FAIL, `cannot find symbol ParcelSteps`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.yadony.api.tracking;

import java.util.List;

/** Libellés publics des étapes d'un colis, partagés par la page de suivi et la page du QR. */
final class ParcelSteps {

    private ParcelSteps() {
    }

    static String label(TrackingEventType type) {
        return switch (type) {
            case DEPART -> "Colis remis au voyageur";
            case TRANSIT -> "En transit";
            case ARRIVEE -> "Arrivée confirmée";
        };
    }

    static String currentStep(List<TrackingEventEntity> events) {
        boolean hasArrivee = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean hasTransit = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
        boolean hasDepart = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);
        if (hasArrivee) return "Livraison confirmée";
        if (hasTransit) return "En transit";
        if (hasDepart) return "Remis au voyageur, en route";
        return "En attente du scan de départ";
    }
}
```

Dans `RecipientController` : supprimer `labelFor` et `resolveCurrentStep` ; remplacer les appels `labelFor(e.getEventType())` par `ParcelSteps.label(e.getEventType())` et `resolveCurrentStep(bid, rawEvents)` par `ParcelSteps.currentStep(rawEvents)`. Dans `templates/recipient/tracking.html`, chercher `en route` et aligner tout texte en dur sur « Remis au voyageur, en route ».

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest='ParcelStepsTest,RecipientController*'`
Expected: PASS. Si un test attend l'ancien texte avec « — », le mettre à jour vers « Remis au voyageur, en route ».

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/tracking/ParcelSteps.java src/main/java/com/yadony/api/tracking/RecipientController.java src/test/java/com/yadony/api/tracking/ src/main/resources/templates/recipient/tracking.html
git commit -m "refactor(tracking): étapes de suivi partagées dans ParcelSteps

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task A3: page publique `/tracking/{bidId}/scan`

**Files:**
- Create: `B/tracking/ParcelScanController.java`
- Create: `B/tracking/ParcelScanView.java`
- Create: `src/main/resources/templates/parcel/scan.html`
- Modify: `B/config/SecurityConfig.java` (liste `permitAll`, ~ligne 148-225, à côté de `"/tracking/public/**"`)
- Modify: `src/main/resources/application.yml` (bloc `yadony:` ligne ~173)
- Modify: `.env.prod.template`, `docker-compose.staging.yml` (variables `SUPPORT_WHATSAPP_NUMBER`, `SUPPORT_EMAIL`, sans valeur réelle)
- Test: `T/tracking/ParcelScanControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `PublicNameFormatter.shortName(String)`, `PublicNameFormatter.UNKNOWN` (A1) ; `ParcelSteps.label`, `ParcelSteps.currentStep` (A2) ; `RecipientController.RecipientEventDto(String label, String scannedAt, String gpsLabel, String photoUrl, boolean offline)` (existant).
- Produces: route publique `GET /tracking/{bidId}/scan` (HTML) ; vue `parcel/scan` ; compteur `yadony.parcel.public_scan{outcome=shown|inactive|not_found}`.

- [ ] **Step 1: Write the failing integration test**

```java
package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yadony.support.whatsapp-number=+33 6 00 00 00 00",
        "yadony.support.email=support@yadony.com",
        "app.store.ios=https://apps.apple.com/app/yadony/id0000000000"
})
class ParcelScanControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired UserRepository userRepository;

    private BidEntity bid;

    @BeforeEach
    void seed() {
        trackingEventRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();

        UserEntity traveler = persistUser("Ibrahima", "Sow");
        UserEntity sender = persistUser("Awa", "Diallo");

        AnnouncementEntity announcement = new AnnouncementEntity();
        // Compléter avec les mêmes setters obligatoires que RecipientControllerIntegrationTest.seed()
        // (TransportMode.PLANE, pickup/delivery label/lat/lng, Available/TotalKg, PricePerKg, status ACTIVE).
        announcement.setTravelerId(traveler.getId());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.of(2026, 10, 18));
        announcement.setArrivalInstructions("12 rue Secrète, Dakar");
        announcement = announcementRepository.save(announcement);

        bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("4.50"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("YD7K4M2Q");
        bid.setDescription("2 boubous <script>alert(1)</script>");
        bid.setContentCategory("Vêtements & tissus,Chaussures");
        bid.setRecipientName("Moussa Keita");
        bid.setRecipientPhone("+221770000000");
        bid.setConfirmationCode("123456");
        bid = bidRepository.save(bid);
    }

    private UserEntity persistUser(String first, String last) {
        // Copier persistUser(...) de ReceptionControllerIntegrationTest (FirebaseUid unique,
        // UserStatus.ACTIVE, KycStatus.PENDING, rôles, TotalTrips 0) avec ces prénom et nom.
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-scan-" + UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        return userRepository.save(u);
    }

    @Test
    void colisEnCours_pagePubliqueSansJeton() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("YD7K4M2Q")))
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("Dakar")))
                .andExpect(content().string(containsString("Vêtements &amp; tissus")))
                .andExpect(content().string(containsString("Chaussures")))
                .andExpect(content().string(containsString("4,5 kg")))
                .andExpect(content().string(containsString("Awa D.")))
                .andExpect(content().string(containsString("Moussa K.")))
                .andExpect(content().string(containsString("Ibrahima S.")))
                .andExpect(content().string(containsString("yadony://colis/" + bid.getId())))
                .andExpect(content().string(containsString("https://wa.me/33600000000")))
                .andExpect(content().string(containsString("support@yadony.com")))
                .andExpect(content().string(containsString("noindex")));
    }

    @Test
    void jamaisDeDonneesSensibles() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Diallo"))))
                .andExpect(content().string(not(containsString("Keita"))))
                .andExpect(content().string(not(containsString("Sow"))))
                .andExpect(content().string(not(containsString("221770000000"))))
                .andExpect(content().string(not(containsString("123456"))))
                .andExpect(content().string(not(containsString("rue Secrète"))))
                .andExpect(content().string(not(containsString("—"))));
    }

    @Test
    void descriptionHtml_echappee() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void colisAnnule_pageInactiveSansDetail() throws Exception {
        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce colis n'est plus actif")))
                .andExpect(content().string(not(containsString("Dakar"))))
                .andExpect(content().string(not(containsString("Awa D."))))
                .andExpect(content().string(containsString("support@yadony.com")));
    }

    @Test
    void noShowEtRefuse_inactifs() throws Exception {
        for (BidStatus s : new BidStatus[]{BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED, BidStatus.NEGOTIATING}) {
            bid.setStatus(s);
            bidRepository.save(bid);
            mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                    .andExpect(content().string(containsString("Ce colis n'est plus actif")));
        }
    }

    @Test
    void colisLivre_afficheLivre() throws Exception {
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livré")));
    }

    @Test
    void uuidInconnu_404Html() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Ce colis n'est plus actif")));
    }

    @Test
    void identifiantNonUuid_404Html() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", "pas-un-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void qrCode_resteProtege() throws Exception {
        mockMvc.perform(get("/tracking/{id}/qr-code", bid.getId()))
                .andExpect(status().isUnauthorized());
    }
}

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.support.whatsapp-number=")
class ParcelScanControllerSansWhatsappIntegrationTest {
    // Dans son propre fichier T/tracking/ParcelScanControllerSansWhatsappIntegrationTest.java
    // (mêmes imports). Pas de seed : la page inactive d'un UUID inconnu porte déjà le bloc contact.
    @Autowired MockMvc mockMvc;

    @Test
    void numeroVide_boutonWhatsappMasque() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", UUID.randomUUID()))
                .andExpect(content().string(not(containsString("wa.me"))))
                .andExpect(content().string(containsString("support@yadony.com")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest='ParcelScanController*'`
Expected: FAIL, 401 sur `/tracking/{id}/scan` (route ni publique ni existante).

- [ ] **Step 3: Configuration**

Dans `application.yml`, sous le bloc `yadony:` existant :

```yaml
  support:
    # Numéro WhatsApp du support affiché sur la page publique du QR colis.
    # Format libre (les non-chiffres sont retirés). Vide = bouton masqué.
    whatsapp-number: ${SUPPORT_WHATSAPP_NUMBER:}
    email: ${SUPPORT_EMAIL:support@yadony.com}
```

Dans `.env.prod.template` : ajouter `SUPPORT_WHATSAPP_NUMBER=` et `SUPPORT_EMAIL=support@yadony.com` avec un commentaire « numéro WhatsApp du support, page publique du QR colis ». Dans `docker-compose.staging.yml` : ajouter `SUPPORT_WHATSAPP_NUMBER: ${SUPPORT_WHATSAPP_NUMBER:-}` et `SUPPORT_EMAIL: ${SUPPORT_EMAIL:-support@yadony.com}` dans l'`environment` du service backend, à côté de `APP_BASE_URL`.

- [ ] **Step 4: Sécurité**

Dans `SecurityConfig`, dans la liste `.requestMatchers(...).permitAll()`, juste après `"/tracking/public/**",` :

```java
                    // Page publique du QR collé sur le colis (scan hors app). Un seul segment
                    // `*` : /tracking/{id}/qr-code et le reste de /tracking restent authentifiés.
                    "/tracking/*/scan",
```

- [ ] **Step 5: Vue**

`B/tracking/ParcelScanView.java` :

```java
package com.yadony.api.tracking;

import java.util.List;

/** Ce que la page publique du QR montre d'un colis actif. Aucun champ sensible. */
public record ParcelScanView(
        String trackingNumber,
        String statusLabel,
        boolean delivered,
        String departureCity,
        String arrivalCity,
        String departureDate,
        String arrivalDate,
        List<String> categories,
        String description,
        String weight,
        String senderName,
        String recipientName,
        String travelerName,
        List<RecipientController.RecipientEventDto> events
) {
}
```

- [ ] **Step 6: Contrôleur**

`B/tracking/ParcelScanController.java` :

```java
package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.PublicNameFormatter;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Page publique ouverte quand on scanne le QR collé sur un colis avec l'appareil photo
 * d'un téléphone. L'URL est celle déjà encodée dans les QR (TrackingService#scanUrl) :
 * les QR imprimés avant cette page marchent sans réimpression.
 */
@Controller
@RequestMapping("/tracking")
public class ParcelScanController {

    static final Set<BidStatus> VISIBLE = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
            BidStatus.ARRIVED, BidStatus.COMPLETED);

    private static final DateTimeFormatter EVENT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);
    private static final String VIEW = "parcel/scan";

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final MeterRegistry meterRegistry;
    private final String whatsappNumber;
    private final String supportEmail;
    private final String storeIos;
    private final String storeAndroid;

    public ParcelScanController(BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                TrackingEventRepository trackingEventRepository,
                                UserRepository userRepository,
                                StorageService storageService,
                                MeterRegistry meterRegistry,
                                @Value("${yadony.support.whatsapp-number:}") String whatsappNumber,
                                @Value("${yadony.support.email:support@yadony.com}") String supportEmail,
                                @Value("${app.store.ios:}") String storeIos,
                                @Value("${app.store.android:}") String storeAndroid) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.meterRegistry = meterRegistry;
        this.whatsappNumber = whatsappNumber == null ? "" : whatsappNumber.replaceAll("\\D", "");
        this.supportEmail = supportEmail;
        this.storeIos = storeIos;
        this.storeAndroid = storeAndroid;
    }

    @GetMapping("/{bidId}/scan")
    public String scan(@PathVariable String bidId, Model model, HttpServletResponse response) {
        Optional<BidEntity> bidOpt = parseUuid(bidId).flatMap(bidRepository::findById);
        if (bidOpt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return inactive(model, null, "not_found");
        }
        BidEntity bid = bidOpt.get();
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        if (!VISIBLE.contains(bid.getStatus()) || announcementOpt.isEmpty()) {
            return inactive(model, bid.getTrackingNumber(), "inactive");
        }
        AnnouncementEntity announcement = announcementOpt.get();
        List<TrackingEventEntity> rawEvents = trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());
        boolean delivered = bid.getStatus() == BidStatus.COMPLETED;

        ParcelScanView parcel = new ParcelScanView(
                bid.getTrackingNumber(),
                delivered ? "Livré" : ParcelSteps.currentStep(rawEvents),
                delivered,
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                formatDate(announcement.getDepartureDate()),
                formatDate(announcement.getArrivalDate()),
                splitCategories(bid.getContentCategory()),
                bid.getDescription() == null ? "" : bid.getDescription(),
                formatWeight(bid.getWeightKg()),
                publicUserName(bid.getSenderId()),
                PublicNameFormatter.shortName(bid.getRecipientName()),
                publicUserName(announcement.getTravelerId()),
                rawEvents.stream().map(e -> new RecipientController.RecipientEventDto(
                        ParcelSteps.label(e.getEventType()),
                        e.getScannedAt().format(EVENT_FMT),
                        e.getGpsLabel(),
                        resolvePhoto(e.getPhotoUrl()),
                        e.getOfflineTimestamp() != null)).toList());

        model.addAttribute("inactive", false);
        model.addAttribute("parcel", parcel);
        model.addAttribute("appLink", "yadony://colis/" + bid.getId());
        addContact(model, bid.getTrackingNumber());
        count("shown");
        return VIEW;
    }

    private String inactive(Model model, String trackingNumber, String outcome) {
        model.addAttribute("inactive", true);
        addContact(model, trackingNumber);
        count(outcome);
        return VIEW;
    }

    private void addContact(Model model, String trackingNumber) {
        String subject = trackingNumber == null ? "Colis Yadony" : "Colis " + trackingNumber;
        String text = trackingNumber == null
                ? "Bonjour, j'ai une question sur un colis Yadony."
                : "Bonjour, j'ai une question sur le colis " + trackingNumber + ".";
        model.addAttribute("whatsappUrl", whatsappNumber.isEmpty()
                ? null
                : "https://wa.me/" + whatsappNumber + "?text=" + encode(text));
        model.addAttribute("supportEmail", supportEmail);
        model.addAttribute("mailtoUrl", "mailto:" + supportEmail + "?subject=" + encode(subject));
        model.addAttribute("storeIos", blankToNull(storeIos));
        model.addAttribute("storeAndroid", blankToNull(storeAndroid));
    }

    private String publicUserName(UUID userId) {
        if (userId == null) return PublicNameFormatter.UNKNOWN;
        return userRepository.findById(userId)
                .map(UserEntity::publicDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(PublicNameFormatter.UNKNOWN);
    }

    private String resolvePhoto(String key) {
        if (key == null || key.startsWith("http")) return key;
        return storageService.generatePresignedUrl(key, Duration.ofHours(1));
    }

    private void count(String outcome) {
        meterRegistry.counter("yadony.parcel.public_scan", "outcome", outcome).increment();
    }

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> splitCategories(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String formatWeight(BigDecimal kg) {
        if (kg == null) return "";
        return kg.stripTrailingZeros().toPlainString().replace('.', ',') + " kg";
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DATE_FMT);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
```

Note : `UUID.fromString` accepte des formes non canoniques (`1-1-1-1-1`) ; ce n'est pas un problème, `findById` renvoie vide → 404.

- [ ] **Step 7: Gabarit**

`src/main/resources/templates/parcel/scan.html` (même famille visuelle que `recipient/tracking.html`, couleurs unies, système de polices) :

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex, nofollow">
  <title th:text="${inactive} ? 'Colis Yadony' : 'Colis ' + ${parcel.trackingNumber} + ' - Yadony'">Colis Yadony</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; }
    html { -webkit-font-smoothing: antialiased; }
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
           background: #f7f9fc; color: #10243b; line-height: 1.45; }
    .page { width: min(100%, 560px); margin: 0 auto; padding: 20px 16px 36px; display: grid; gap: 12px; }
    .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .brand { font-size: 20px; font-weight: 850; color: #1769ff; }
    .tn { color: #6b7788; font-size: 13px; font-variant-numeric: tabular-nums; letter-spacing: .04em; }
    .panel { background: #fff; border-radius: 16px; box-shadow: inset 0 0 0 1px #e4eaf2; padding: 16px; display: grid; gap: 10px; }
    .label { margin: 0; font-size: 11px; text-transform: uppercase; font-weight: 800; letter-spacing: .08em; color: #6b7788; }
    .status { background: #e7efff; box-shadow: none; }
    .status.done { background: #e3f4ec; }
    .status strong { font-size: 17px; }
    .route { display: grid; grid-template-columns: 1fr auto 1fr; gap: 8px; align-items: center; }
    .route .end { text-align: right; }
    .city { font-size: 20px; font-weight: 750; display: block; }
    .muted { color: #6b7788; font-size: 13px; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { font-size: 13px; padding: 3px 10px; border-radius: 999px; background: #f7f9fc; box-shadow: inset 0 0 0 1px #dfe6ef; }
    .desc { margin: 0; white-space: pre-line; overflow-wrap: anywhere; }
    .row { display: flex; justify-content: space-between; gap: 8px; }
    .person { display: grid; }
    .event { display: grid; gap: 4px; padding-left: 14px; border-left: 2px solid #dfe6ef; }
    .event img { width: 100%; max-height: 220px; object-fit: cover; border-radius: 10px; }
    .btn { display: block; text-align: center; text-decoration: none; font-weight: 700; border-radius: 12px; padding: 13px 14px; font-size: 15px; }
    .btn.wa { background: #128c4a; color: #fff; }
    .btn.mail { background: #fff; color: #10243b; box-shadow: inset 0 0 0 1px #dfe6ef; }
    .btn.primary { background: #1769ff; color: #fff; }
    .stores { text-align: center; font-size: 13px; color: #6b7788; }
    .stores a { color: #1769ff; font-weight: 700; text-decoration: none; }
    .empty { text-align: center; padding: 24px 8px; display: grid; gap: 8px; }
    .empty h1 { margin: 0; font-size: 20px; }
    .foot { text-align: center; font-size: 12px; color: #6b7788; }
  </style>
</head>
<body>
<main class="page">
  <div class="topbar">
    <span class="brand">Yadony</span>
    <span class="tn" th:if="${!inactive}" th:text="${parcel.trackingNumber}">YD7K4M2Q</span>
  </div>

  <th:block th:if="${inactive}">
    <section class="panel empty">
      <h1>Ce colis n'est plus actif</h1>
      <p class="muted">L'envoi a été annulé ou ce QR code n'est pas reconnu. Si vous avez ce colis entre les mains, contactez Yadony.</p>
    </section>
  </th:block>

  <th:block th:unless="${inactive}">
    <section class="panel status" th:classappend="${parcel.delivered} ? 'done'">
      <strong th:text="${parcel.statusLabel}">En transit</strong>
    </section>

    <section class="panel">
      <p class="label">Trajet</p>
      <div class="route">
        <div><span class="city" th:text="${parcel.departureCity}">Paris</span>
          <span class="muted" th:if="${!#strings.isEmpty(parcel.departureDate)}" th:text="'Départ ' + ${parcel.departureDate}">Départ</span></div>
        <span aria-hidden="true">✈</span>
        <div class="end"><span class="city" th:text="${parcel.arrivalCity}">Dakar</span>
          <span class="muted" th:if="${!#strings.isEmpty(parcel.arrivalDate)}" th:text="'Arrivée ' + ${parcel.arrivalDate}">Arrivée</span></div>
      </div>
    </section>

    <section class="panel">
      <p class="label">Contenu</p>
      <div class="chips" th:if="${!parcel.categories.isEmpty()}">
        <span class="chip" th:each="c : ${parcel.categories}" th:text="${c}">Vêtements</span>
      </div>
      <p class="desc" th:if="${!#strings.isEmpty(parcel.description)}" th:text="${parcel.description}">Description</p>
      <div class="row" th:if="${!#strings.isEmpty(parcel.weight)}"><span class="muted">Poids</span><strong th:text="${parcel.weight}">4,5 kg</strong></div>
    </section>

    <section class="panel">
      <p class="label">Personnes</p>
      <div class="person"><span class="muted">Envoyé par</span><strong th:text="${parcel.senderName}">Awa D.</strong></div>
      <div class="person"><span class="muted">À récupérer par</span><strong th:text="${parcel.recipientName}">Moussa K.</strong></div>
      <div class="person"><span class="muted">Transporté par</span><strong th:text="${parcel.travelerName}">Ibrahima S.</strong></div>
    </section>

    <section class="panel" th:if="${!parcel.events.isEmpty()}">
      <p class="label">Étapes</p>
      <div class="event" th:each="event : ${parcel.events}">
        <strong th:text="${event.label}">Colis remis au voyageur</strong>
        <span class="muted">
          <span th:text="${event.scannedAt}">18/10/2026 09:12</span>
          <span th:if="${event.gpsLabel != null and !#strings.isEmpty(event.gpsLabel)}" th:text="' · ' + ${event.gpsLabel}"></span>
        </span>
        <img th:if="${event.photoUrl != null and !#strings.isEmpty(event.photoUrl)}" th:src="${event.photoUrl}" alt="Photo du colis à cette étape" loading="lazy">
      </div>
    </section>
  </th:block>

  <section class="panel">
    <p class="label">Une question sur ce colis ?</p>
    <p class="muted" style="margin:0">Contactez Yadony. Nous faisons le lien avec l'expéditeur et le voyageur.</p>
    <a class="btn wa" th:if="${whatsappUrl != null}" th:href="${whatsappUrl}" rel="noopener">Écrire à Yadony sur WhatsApp</a>
    <a class="btn mail" th:href="${mailtoUrl}" th:text="${supportEmail}">support@yadony.com</a>
  </section>

  <th:block th:unless="${inactive}">
    <a class="btn primary" th:href="${appLink}">Ouvrir dans Yadony</a>
    <p class="stores" th:if="${storeIos != null or storeAndroid != null}">Pas encore l'application ?
      <a th:if="${storeIos != null}" th:href="${storeIos}" rel="noopener">App Store</a>
      <th:block th:if="${storeIos != null and storeAndroid != null}"> · </th:block>
      <a th:if="${storeAndroid != null}" th:href="${storeAndroid}" rel="noopener">Google Play</a>
    </p>
  </th:block>
  <p class="foot">Les numéros de téléphone ne sont jamais affichés sur cette page.</p>
</main>
</body>
</html>
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./mvnw test -Dtest='ParcelScanController*,RecipientController*,SecurityConfig*'`
Expected: PASS. Si `contentTypeCompatibleWith("text/html")` échoue sur la réponse 404, vérifier que `response.setStatus` est bien appelé avant le rendu (pas de `ResponseStatusException`, qui passerait par `GlobalExceptionHandler` en JSON).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/yadony/api/tracking/ParcelScanController.java src/main/java/com/yadony/api/tracking/ParcelScanView.java src/main/resources/templates/parcel/scan.html src/main/java/com/yadony/api/config/SecurityConfig.java src/main/resources/application.yml .env.prod.template docker-compose.staging.yml src/test/java/com/yadony/api/tracking/
git commit -m "feat(tracking): page publique du QR colis

Le QR d'un colis encodait /tracking/{bidId}/scan, route ni publique ni
existante : 401 au scan hors app. La route rend maintenant une page
publique (contenu, trajet, noms en prénom + initiale, étapes, contact
Yadony, lien yadony://colis/{id}), sans téléphone ni code de remise.

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task A4: rôle de l'utilisateur sur un colis

**Files:**
- Create: `B/matching/reception/dto/ParcelRoleResponse.java`
- Modify: `B/matching/reception/ReceptionService.java` (nouvelle méthode `role`)
- Modify: `B/matching/reception/ReceptionController.java` (nouvel endpoint)
- Test: `T/matching/reception/ReceptionControllerIntegrationTest.java` (ajouter des tests, réutiliser `persistUser`, `persistBid`, `link`, `as`)

**Interfaces:**
- Produces: `GET /receptions/role/{bidId}` → `200 {"role":"SENDER"|"TRAVELER"|"RECIPIENT"|"NONE"}`. Consommé par Task B2.

- [ ] **Step 1: Write the failing tests** (dans `ReceptionControllerIntegrationTest`)

```java
    @Test
    void role_expediteur() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "+221770000001");
        UserEntity sender = userRepository.findById(bid.getSenderId()).orElseThrow();
        mockMvc.perform(get("/receptions/role/{id}", bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SENDER"));
    }

    @Test
    void role_voyageur() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "+221770000002");
        AnnouncementEntity a = announcementRepository.findById(bid.getAnnouncementId()).orElseThrow();
        UserEntity traveler = userRepository.findById(a.getTravelerId()).orElseThrow();
        mockMvc.perform(get("/receptions/role/{id}", bid.getId()).with(authentication(as(traveler))))
                .andExpect(jsonPath("$.role").value("TRAVELER"));
    }

    @Test
    void role_destinataireLie() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "+221770000003");
        link(bid, ReceptionLinkStatus.PENDING);
        mockMvc.perform(get("/receptions/role/{id}", bid.getId()).with(authentication(as(recipient))))
                .andExpect(jsonPath("$.role").value("RECIPIENT"));
    }

    @Test
    void role_destinataireAyantRefuse_none() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "+221770000004");
        link(bid, ReceptionLinkStatus.DECLINED);
        mockMvc.perform(get("/receptions/role/{id}", bid.getId()).with(authentication(as(recipient))))
                .andExpect(jsonPath("$.role").value("NONE"));
    }

    @Test
    void role_inconnu_none() throws Exception {
        mockMvc.perform(get("/receptions/role/{id}", UUID.randomUUID()).with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("NONE"));
    }

    @Test
    void role_sansJeton_401() throws Exception {
        mockMvc.perform(get("/receptions/role/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
```

Si `persistBid` n'enregistre pas d'expéditeur ou de voyageur réels (UUID aléatoires sans `UserEntity`), l'étendre : créer l'expéditeur avec `persistUser("Awa", Role.SENDER)`, le voyageur avec `persistUser("Ibrahima", Role.TRAVELER)`, et une `AnnouncementEntity` dont `travelerId` est ce voyageur (setters de `RecipientControllerIntegrationTest.seed()`). Injecter `AnnouncementRepository` et `UserRepository` dans la classe de test s'ils n'y sont pas.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=ReceptionControllerIntegrationTest`
Expected: FAIL sur les nouveaux tests (404/400 : route `/receptions/role/{id}` inconnue).

- [ ] **Step 3: Implementation**

`dto/ParcelRoleResponse.java` :

```java
package com.yadony.api.matching.reception.dto;

/** Lien entre l'utilisateur connecté et un colis, pour router le lien yadony://colis/{id}. */
public record ParcelRoleResponse(Role role) {

    public enum Role { SENDER, TRAVELER, RECIPIENT, NONE }
}
```

Dans `ReceptionService` (injecter `AnnouncementRepository` par le constructeur s'il ne l'est pas déjà ; il est dans le même package `matching`) :

```java
    /**
     * Rôle de l'utilisateur sur un colis, pour le lien yadony://colis/{id} de la page
     * publique du QR. Jamais d'erreur sur un colis inconnu : NONE, sans dire s'il existe.
     * Rattrape d'abord par numéro, pour qu'un destinataire qui n'a jamais ouvert
     * Réceptions soit reconnu dès son premier scan.
     */
    public ParcelRoleResponse role(UUID bidId, String firebaseUid) {
        UserEntity user = currentUser(firebaseUid);
        Optional<BidEntity> bidOpt = bidRepository.findById(bidId);
        if (bidOpt.isEmpty()) {
            return new ParcelRoleResponse(ParcelRoleResponse.Role.NONE);
        }
        BidEntity bid = bidOpt.get();
        if (user.getId().equals(bid.getSenderId())) {
            return new ParcelRoleResponse(ParcelRoleResponse.Role.SENDER);
        }
        boolean traveler = announcementRepository.findById(bid.getAnnouncementId())
                .map(a -> user.getId().equals(a.getTravelerId()))
                .orElse(false);
        if (traveler) {
            return new ParcelRoleResponse(ParcelRoleResponse.Role.TRAVELER);
        }
        try {
            linker.catchUp(user);
        } catch (Exception e) {
            log.warn("Rattrapage des réceptions impossible pour {} : {}", user.getId(), e.toString());
        }
        boolean recipient = linkRepository.findByBidIdAndRecipientUserId(bidId, user.getId())
                .filter(l -> l.getStatus() != ReceptionLinkStatus.DECLINED)
                .isPresent();
        return new ParcelRoleResponse(recipient ? ParcelRoleResponse.Role.RECIPIENT : ParcelRoleResponse.Role.NONE);
    }
```

Dans `ReceptionController`, avant `@GetMapping("/{bidId}")` :

```java
    @GetMapping("/role/{bidId}")
    public ParcelRoleResponse role(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId) {
        return service.role(bidId, firebaseUid);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ReceptionControllerIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/matching/reception/ src/test/java/com/yadony/api/matching/reception/
git commit -m "feat(receptions): rôle de l'utilisateur sur un colis

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task A5: suite complète, couverture, PR

- [ ] **Step 1:** `./mvnw test jacoco:report` (seul `mvnw` en cours sur la machine). Expected : 0 rouge. Ouvrir `target/site/jacoco/index.html` : `ParcelScanController`, `ParcelSteps`, `PublicNameFormatter`, `ReceptionService.role` ≥ 90 %.
- [ ] **Step 2:** Mettre à jour `CLAUDE.md` du back, section « Endpoints publics » : ajouter `/tracking/{bidId}/scan` (page HTML du QR colis).
- [ ] **Step 3:** Créer `docs/stories-done/story-qr-colis-page-publique.md` au format imposé par le `CLAUDE.md` du back, avec le % de couverture.
- [ ] **Step 4:** Commit, `git push -u origin feature/page-publique-scan-colis`, `gh pr create` (description : problème, ce qui est affiché/jamais affiché, variable `SUPPORT_WHATSAPP_NUMBER` à poser en staging/prod, lien maquette ; terminer par `https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv`).
- [ ] **Step 5:** CI verte (tous les checks « pass ») → `gh pr merge <n> --squash --repo MONDONY/yadony-back`. Déploiement staging manuel seulement si le propriétaire le demande, avec `SUPPORT_WHATSAPP_NUMBER` posé dans l'env du serveur.

---

# Partie B — dony_app (PR 2, jumelle de la PR 1)

Worktree : `dony_app-worktrees/scan-colis`, branche `feature/lien-colis-qr`, créé depuis `origin/main` (copier `env.dev.json` / `env.staging.json` et lancer `flutter pub get` dans le worktree neuf).

### Task B1: lien profond `yadony://colis/{bidId}`

**Files:**
- Create: `lib/app/parcel_deep_link.dart`
- Modify: `lib/app/app.dart` (liste `_parameterizedResolvers`, ~ligne 216)
- Modify: `android/app/src/main/AndroidManifest.xml` (nouvel intent-filter après celui de `bids`, ~ligne 144)
- Test: `test/app/parcel_deep_link_test.dart`

**Interfaces:**
- Produces: `String? resolveParcelDeepLink(Uri uri)` → `'/colis/<uuid>'` ou `null`.

- [ ] **Step 1: Write the failing test**

```dart
import 'package:dony/app/parcel_deep_link.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const uuid = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';

  group('resolveParcelDeepLink', () {
    test('résout un UUID valide vers l\'écran de transition', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis/$uuid')), '/colis/$uuid');
    });

    test('accepte un UUID en majuscules', () {
      final upper = uuid.toUpperCase();
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis/$upper')), '/colis/$upper');
    });

    test('ignore un autre schéma', () {
      expect(resolveParcelDeepLink(Uri.parse('https://colis/$uuid')), isNull);
    });

    test('ignore un autre hôte', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://annonce/$uuid')), isNull);
    });

    test('ignore un identifiant non UUID', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis/42')), isNull);
    });

    test('ignore un segment en trop', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis/$uuid/edit')), isNull);
    });

    test('ignore l\'absence de segment', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis')), isNull);
    });

    test('ignore une tentative de remontée', () {
      expect(resolveParcelDeepLink(Uri.parse('yadony://colis/../admin')), isNull);
    });
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `flutter test test/app/parcel_deep_link_test.dart`
Expected: FAIL, fichier `parcel_deep_link.dart` introuvable.

- [ ] **Step 3: Implementation**

`lib/app/parcel_deep_link.dart` :

```dart
final RegExp _uuidPattern = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-'
  r'[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
);

/// `yadony://colis/{bidId}` : bouton « Ouvrir dans Yadony » de la page publique
/// du QR colis. Mène à l'écran de transition qui choisit la fiche selon le rôle
/// de l'utilisateur sur le colis.
String? resolveParcelDeepLink(Uri uri) {
  if (uri.scheme != 'yadony' || uri.host != 'colis') {
    return null;
  }
  final segments = uri.pathSegments;
  if (segments.length != 1 || !_uuidPattern.hasMatch(segments.first)) {
    return null;
  }
  return '/colis/${segments.first}';
}
```

Dans `app.dart` : importer `parcel_deep_link.dart` et ajouter `resolveParcelDeepLink,` à la fin de `_parameterizedResolvers`.

Dans `AndroidManifest.xml`, après l'intent-filter `bids` :

```xml
            <!-- Page publique du QR colis : bouton « Ouvrir dans Yadony ». -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="yadony" android:host="colis"/>
            </intent-filter>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `flutter test test/app/parcel_deep_link_test.dart`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/app/parcel_deep_link.dart lib/app/app.dart android/app/src/main/AndroidManifest.xml test/app/parcel_deep_link_test.dart
git commit -m "feat(deeplink): yadony://colis/{id} depuis la page publique du QR

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task B2: données + `ParcelLinkCubit`

**Files:**
- Create: `lib/features/receptions/data/models/parcel_role.dart`
- Modify: `lib/features/receptions/data/datasources/reception_remote_datasource.dart`
- Modify: `lib/features/receptions/data/repositories/reception_repository.dart`
- Create: `lib/features/receptions/bloc/parcel_link_cubit.dart`
- Modify: `lib/core/di/injection.dart` (~ligne 382-400)
- Modify: `lib/core/services/analytics_events.dart` + tableau des événements du `CLAUDE.md` de l'app
- Test: `test/features/receptions/bloc/parcel_link_cubit_test.dart`, `test/features/receptions/data/parcel_role_test.dart`

**Interfaces:**
- Consumes: `GET /receptions/role/{bidId}` (A4).
- Produces: `enum ParcelRole { sender, traveler, recipient, none }` + `ParcelRole parcelRoleFromJson(Object? raw)` ; `Future<ParcelRole> ReceptionRepository.getParcelRole(String bidId)` ; `ParcelLinkCubit(ReceptionRepository, AnalyticsService)` avec `Future<void> load(String bidId)` ; états `ParcelLinkLoading`, `ParcelLinkResolved(ParcelRole role)`, `ParcelLinkError(AppException error)` ; `AnalyticsEvents.parcelDeepLinkResolved`.

- [ ] **Step 1: Write the failing tests**

`test/features/receptions/data/parcel_role_test.dart` :

```dart
import 'package:dony/features/receptions/data/models/parcel_role.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('lit les quatre rôles', () {
    expect(parcelRoleFromJson('SENDER'), ParcelRole.sender);
    expect(parcelRoleFromJson('TRAVELER'), ParcelRole.traveler);
    expect(parcelRoleFromJson('RECIPIENT'), ParcelRole.recipient);
    expect(parcelRoleFromJson('NONE'), ParcelRole.none);
  });

  test('valeur inconnue ou absente : none', () {
    expect(parcelRoleFromJson('ADMIN'), ParcelRole.none);
    expect(parcelRoleFromJson(null), ParcelRole.none);
  });
}
```

`test/features/receptions/bloc/parcel_link_cubit_test.dart` :

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:dio/dio.dart';
import 'package:dony/core/error/app_exception.dart';
import 'package:dony/core/services/analytics_events.dart';
import 'package:dony/core/services/analytics_service.dart';
import 'package:dony/features/receptions/bloc/parcel_link_cubit.dart';
import 'package:dony/features/receptions/data/models/parcel_role.dart';
import 'package:dony/features/receptions/data/repositories/reception_repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class MockReceptionRepository extends Mock implements ReceptionRepository {}

class MockAnalyticsService extends Mock implements AnalyticsService {}

void main() {
  const bidId = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
  late MockReceptionRepository repository;
  late MockAnalyticsService analytics;

  setUp(() {
    repository = MockReceptionRepository();
    analytics = MockAnalyticsService();
    when(() => analytics.logEvent(any(), properties: any(named: 'properties')))
        .thenAnswer((_) async {});
  });

  for (final role in ParcelRole.values) {
    blocTest<ParcelLinkCubit, ParcelLinkState>(
      'rôle ${role.name} : résolu',
      build: () {
        when(() => repository.getParcelRole(bidId)).thenAnswer((_) async => role);
        return ParcelLinkCubit(repository, analytics);
      },
      act: (cubit) => cubit.load(bidId),
      expect: () => [
        isA<ParcelLinkResolved>().having((s) => s.role, 'role', role),
      ],
      verify: (_) => verify(
        () => analytics.logEvent(
          AnalyticsEvents.parcelDeepLinkResolved,
          properties: {'role': role.name},
        ),
      ).called(1),
    );
  }

  blocTest<ParcelLinkCubit, ParcelLinkState>(
    'erreur réseau : état erreur',
    build: () {
      when(() => repository.getParcelRole(bidId)).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/receptions/role/$bidId'),
          type: DioExceptionType.connectionError,
        ),
      );
      return ParcelLinkCubit(repository, analytics);
    },
    act: (cubit) => cubit.load(bidId),
    expect: () => [isA<ParcelLinkError>()],
  );

  blocTest<ParcelLinkCubit, ParcelLinkState>(
    'réessayer repasse par le chargement',
    build: () {
      when(() => repository.getParcelRole(bidId)).thenAnswer((_) async => ParcelRole.none);
      return ParcelLinkCubit(repository, analytics);
    },
    seed: () => ParcelLinkError(const NetworkException()),
    act: (cubit) => cubit.load(bidId),
    expect: () => [
      const ParcelLinkLoading(),
      isA<ParcelLinkResolved>(),
    ],
  );
}
```

Ajuster le chemin d'import d'`AnalyticsEvents` et le constructeur de `NetworkException` à ce que montrent `lib/core/services/analytics_events.dart` et `lib/core/error/app_exception.dart`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `flutter test test/features/receptions/bloc/parcel_link_cubit_test.dart test/features/receptions/data/parcel_role_test.dart`
Expected: FAIL, fichiers introuvables.

- [ ] **Step 3: Implementation**

`lib/features/receptions/data/models/parcel_role.dart` :

```dart
/// Lien entre l'utilisateur connecté et un colis (`GET /receptions/role/{id}`).
enum ParcelRole { sender, traveler, recipient, none }

ParcelRole parcelRoleFromJson(Object? raw) => switch (raw) {
      'SENDER' => ParcelRole.sender,
      'TRAVELER' => ParcelRole.traveler,
      'RECIPIENT' => ParcelRole.recipient,
      _ => ParcelRole.none,
    };
```

Datasource :

```dart
  /// Rôle de l'utilisateur sur un colis ; NONE pour un colis inconnu.
  Future<ParcelRole> fetchParcelRole(String bidId) async {
    final response = await _apiClient.dio.get('/receptions/role/$bidId');
    final data = response.data as Map<String, dynamic>;
    return parcelRoleFromJson(data['role']);
  }
```

Repository : `Future<ParcelRole> getParcelRole(String bidId) => _datasource.fetchParcelRole(bidId);`

`lib/features/receptions/bloc/parcel_link_cubit.dart` :

```dart
import 'dart:async';

import 'package:dony/core/error/app_exception.dart';
import 'package:dony/core/services/analytics_events.dart';
import 'package:dony/core/services/analytics_service.dart';
import 'package:dony/features/receptions/data/models/parcel_role.dart';
import 'package:dony/features/receptions/data/repositories/reception_repository.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

sealed class ParcelLinkState {
  const ParcelLinkState();
}

class ParcelLinkLoading extends ParcelLinkState {
  const ParcelLinkLoading();
}

class ParcelLinkResolved extends ParcelLinkState {
  const ParcelLinkResolved(this.role);
  final ParcelRole role;
}

class ParcelLinkError extends ParcelLinkState {
  const ParcelLinkError(this.error);
  final AppException error;
}

/// Lien `yadony://colis/{id}` : demande au back le rôle de l'utilisateur
/// sur le colis, l'écran choisit ensuite la fiche à ouvrir.
class ParcelLinkCubit extends Cubit<ParcelLinkState> {
  ParcelLinkCubit(this._repository, this._analytics) : super(const ParcelLinkLoading());

  final ReceptionRepository _repository;
  final AnalyticsService _analytics;

  Future<void> load(String bidId) async {
    if (state is! ParcelLinkLoading) emit(const ParcelLinkLoading());
    try {
      final role = await _repository.getParcelRole(bidId);
      if (isClosed) return;
      emit(ParcelLinkResolved(role));
      unawaited(
        _analytics.logEvent(
          AnalyticsEvents.parcelDeepLinkResolved,
          properties: {'role': role.name},
        ),
      );
    } catch (e) {
      if (isClosed) return;
      emit(ParcelLinkError(unwrapDioError(e)));
    }
  }
}
```

`AnalyticsEvents` : `static const parcelDeepLinkResolved = 'parcel_deep_link_resolved'; // i18n-ignore` (suivre le style du fichier). Ligne dans le tableau d'événements du `CLAUDE.md` de l'app : `parcel_deep_link_resolved | ParcelLinkCubit | role (sender/traveler/recipient/none) | ouverture du colis depuis la page publique du QR`.

`injection.dart`, après `ReceptionDetailCubit` :

```dart
  getIt.registerFactory<ParcelLinkCubit>(
    () => ParcelLinkCubit(
      getIt<ReceptionRepository>(),
      getIt<AnalyticsService>(),
    ),
  );
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `flutter test test/features/receptions/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/features/receptions/ lib/core/di/injection.dart lib/core/services/analytics_events.dart CLAUDE.md test/features/receptions/
git commit -m "feat(receptions): rôle de l'utilisateur sur un colis (ParcelLinkCubit)

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task B3: écran de transition `/colis/:bidId`

**Files:**
- Create: `lib/features/receptions/presentation/screens/parcel_link_screen.dart`
- Modify: `lib/app/router.dart` (à côté de `/receptions/:bidId`, ~ligne 1196)
- Modify: `lib/l10n/app_fr.arb`, `lib/l10n/app_en.arb`
- Test: `test/features/receptions/presentation/parcel_link_screen_test.dart`

**Interfaces:**
- Consumes: `ParcelLinkCubit`, `ParcelLinkState`, `ParcelRole` (B2) ; routes existantes `/receptions/:bidId`, `/bids/:bidId`, `/support`.
- Produces: route `/colis/:bidId`.

- [ ] **Step 1: l10n**

`app_fr.arb` :

```json
  "parcelLinkTitle": "Colis",
  "@parcelLinkTitle": { "description": "Titre de l'écran ouvert par le lien yadony://colis/{id}" },
  "parcelLinkNotLinkedTitle": "Ce colis n'est pas lié à votre compte",
  "@parcelLinkNotLinkedTitle": { "description": "Lien colis : l'utilisateur n'est ni expéditeur, ni voyageur, ni destinataire" },
  "parcelLinkNotLinkedBody": "Si vous devez le récupérer, demandez à l'expéditeur d'indiquer votre numéro, ou écrivez au support.",
  "@parcelLinkNotLinkedBody": { "description": "Explication sous le titre parcelLinkNotLinkedTitle" },
  "parcelLinkRetry": "Réessayer",
  "@parcelLinkRetry": { "description": "Bouton pour relancer la vérification du lien colis après une erreur" }
```

`app_en.arb` : `"parcelLinkTitle": "Parcel"`, `"parcelLinkNotLinkedTitle": "This parcel isn't linked to your account"`, `"parcelLinkNotLinkedBody": "If you need to collect it, ask the sender to add your phone number, or contact support."`, `"parcelLinkRetry": "Try again"`.

Puis : `flutter gen-l10n && dart run tool/gen_l10n_catalog.dart && dart format test/l10n/`.

- [ ] **Step 2: Write the failing widget test**

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:dony/core/error/app_exception.dart';
import 'package:dony/features/receptions/bloc/parcel_link_cubit.dart';
import 'package:dony/features/receptions/data/models/parcel_role.dart';
import 'package:dony/features/receptions/presentation/screens/parcel_link_screen.dart';
import 'package:dony/l10n/generated/app_localizations.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

class MockParcelLinkCubit extends MockCubit<ParcelLinkState> implements ParcelLinkCubit {}

void main() {
  const bidId = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
  late MockParcelLinkCubit cubit;
  late List<String> visited;

  setUp(() {
    cubit = MockParcelLinkCubit();
    visited = [];
  });

  Widget harness() {
    final router = GoRouter(
      initialLocation: '/colis/$bidId',
      routes: [
        GoRoute(
          path: '/colis/:bidId',
          builder: (_, state) => BlocProvider<ParcelLinkCubit>.value(
            value: cubit,
            child: ParcelLinkScreen(bidId: state.pathParameters['bidId']!),
          ),
        ),
        GoRoute(path: '/receptions/:bidId', builder: (_, s) { visited.add(s.uri.path); return const SizedBox(); }),
        GoRoute(path: '/bids/:bidId', builder: (_, s) { visited.add(s.uri.path); return const SizedBox(); }),
        GoRoute(path: '/support', builder: (_, s) { visited.add(s.uri.path); return const SizedBox(); }),
      ],
    );
    return MaterialApp.router(
      routerConfig: router,
      locale: const Locale('fr'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    );
  }

  testWidgets('destinataire : ouvre Réceptions', (tester) async {
    whenListen(cubit, Stream.value(const ParcelLinkResolved(ParcelRole.recipient)),
        initialState: const ParcelLinkLoading());
    await tester.pumpWidget(harness());
    await tester.pumpAndSettle();
    expect(visited, ['/receptions/$bidId']);
  });

  testWidgets('expéditeur : ouvre la fiche du colis', (tester) async {
    whenListen(cubit, Stream.value(const ParcelLinkResolved(ParcelRole.sender)),
        initialState: const ParcelLinkLoading());
    await tester.pumpWidget(harness());
    await tester.pumpAndSettle();
    expect(visited, ['/bids/$bidId']);
  });

  testWidgets('voyageur : ouvre la fiche du colis', (tester) async {
    whenListen(cubit, Stream.value(const ParcelLinkResolved(ParcelRole.traveler)),
        initialState: const ParcelLinkLoading());
    await tester.pumpWidget(harness());
    await tester.pumpAndSettle();
    expect(visited, ['/bids/$bidId']);
  });

  testWidgets('aucun lien : message et bouton support', (tester) async {
    when(() => cubit.state).thenReturn(const ParcelLinkResolved(ParcelRole.none));
    await tester.pumpWidget(harness());
    await tester.pumpAndSettle();
    expect(find.text("Ce colis n'est pas lié à votre compte"), findsOneWidget);
    await tester.tap(find.text('Contacter le support'));
    await tester.pumpAndSettle();
    expect(visited, ['/support']);
  });

  testWidgets('erreur : bouton réessayer relance le chargement', (tester) async {
    when(() => cubit.state).thenReturn(const ParcelLinkError(NetworkException()));
    when(() => cubit.load(bidId)).thenAnswer((_) async {});
    await tester.pumpWidget(harness());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Réessayer'));
    verify(() => cubit.load(bidId)).called(1);
  });
}
```

Si l'app exige un wrapper de test (thème `DonyTheme`, `getIt` initialisé pour `DonyButton`), reprendre le harnais d'un test d'écran existant de `test/features/receptions/presentation/`.

- [ ] **Step 3: Run test to verify it fails**

Run: `flutter test test/features/receptions/presentation/parcel_link_screen_test.dart`
Expected: FAIL, `parcel_link_screen.dart` introuvable.

- [ ] **Step 4: Implementation**

`parcel_link_screen.dart` :

```dart
import 'package:dony/core/error/error_presenter.dart';
import 'package:dony/core/widgets/dony_button.dart';
import 'package:dony/features/receptions/bloc/parcel_link_cubit.dart';
import 'package:dony/features/receptions/data/models/parcel_role.dart';
import 'package:dony/l10n/l10n.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

/// Ouvert par `yadony://colis/{id}` (page publique du QR). Remplace l'écran
/// par la fiche qui correspond au rôle de l'utilisateur sur le colis.
class ParcelLinkScreen extends StatelessWidget {
  const ParcelLinkScreen({required this.bidId, super.key});

  final String bidId;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l.parcelLinkTitle)),
      body: BlocConsumer<ParcelLinkCubit, ParcelLinkState>(
        listener: (context, state) {
          if (state is! ParcelLinkResolved) return;
          switch (state.role) {
            case ParcelRole.recipient:
              context.pushReplacement('/receptions/$bidId');
            case ParcelRole.sender:
            case ParcelRole.traveler:
              context.pushReplacement('/bids/$bidId');
            case ParcelRole.none:
              break;
          }
        },
        builder: (context, state) => switch (state) {
          ParcelLinkResolved(role: ParcelRole.none) => _Message(
              title: l.parcelLinkNotLinkedTitle,
              body: l.parcelLinkNotLinkedBody,
              actionLabel: l.profileHelpContactSupport,
              onAction: () => context.pushReplacement('/support'),
            ),
          ParcelLinkError(:final error) => _Message(
              title: ErrorPresenter.resolve(error, l10n: l),
              actionLabel: l.parcelLinkRetry,
              onAction: () => context.read<ParcelLinkCubit>().load(bidId),
            ),
          _ => const Center(child: CircularProgressIndicator()),
        },
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.title, required this.actionLabel, required this.onAction, this.body});

  final String title;
  final String? body;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(title, style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
          if (body != null) ...[
            const SizedBox(height: 8),
            Text(body!, style: theme.textTheme.bodyMedium, textAlign: TextAlign.center),
          ],
          const SizedBox(height: 24),
          DonyButton(label: actionLabel, onPressed: onAction),
        ],
      ),
    );
  }
}
```

Vérifier la signature réelle de `ErrorPresenter.resolve` (`lib/core/error/error_presenter.dart`) et le type retourné (si c'est un objet, prendre son champ titre/message). Vérifier aussi les paramètres de `DonyButton`.

`router.dart`, juste après la route `/receptions/:bidId` :

```dart
    // ── Lien colis depuis la page publique du QR (yadony://colis/{id}) ─────
    // Écran de transition : choisit Réceptions ou la fiche du colis selon le
    // rôle de l'utilisateur. Hors shell, derrière l'authentification.
    GoRoute(
      path: '/colis/:bidId',
      builder: (_, state) {
        final bidId = state.pathParameters['bidId']!;
        return BlocProvider(
          create: (_) => getIt<ParcelLinkCubit>()..load(bidId),
          child: ParcelLinkScreen(bidId: bidId),
        );
      },
    ),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `flutter test test/features/receptions/ test/app/`
Expected: PASS (dont `router_screen_names_test.dart` et `router_auth_redirect_test.dart`).

- [ ] **Step 6: Commit**

```bash
git add lib/features/receptions/presentation/screens/parcel_link_screen.dart lib/app/router.dart lib/l10n/ test/features/receptions/presentation/parcel_link_screen_test.dart test/l10n/
git commit -m "feat(receptions): écran du lien colis selon le rôle

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task B4: commentaire du parseur QR + test du format réel

**Files:**
- Modify: `lib/features/tracking/presentation/widgets/qr_camera_view.dart:17-21`
- Test: test existant de `extractBidIdFromQr` (`grep -rln extractBidIdFromQr test/`) ou `test/features/tracking/presentation/widgets/qr_camera_view_test.dart`

- [ ] **Step 1: Test du format réel encodé par le back**

```dart
  test('lit le bidId dans l\'URL réelle du QR (…/api/v1/tracking/<id>/scan)', () {
    const id = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
    expect(
      extractBidIdFromQr('https://api.yadony.com/api/v1/tracking/$id/scan'),
      id,
    );
  });
```

- [ ] **Step 2:** `flutter test <fichier>` → PASS (le parseur gère déjà ce format ; le test le verrouille).
- [ ] **Step 3:** Remplacer le commentaire par :

```dart
/// Le QR encode l'URL de la page publique du colis,
/// `https://api.yadony.com/api/v1/tracking/<bidId>/scan` (scannée hors app,
/// elle affiche la page publique). On prend le segment qui suit `tracking`
/// et on exige un UUID, pour qu'un QR quelconque (menu de restaurant, lien
/// marketing) ne déclenche jamais rien.
```

- [ ] **Step 4: Commit**

```bash
git add lib/features/tracking/presentation/widgets/qr_camera_view.dart test/
git commit -m "docs(tracking): format réel de l'URL du QR colis

Claude-Session: https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv"
```

### Task B5: vérifications et PR

- [ ] **Step 1:** `flutter analyze` (projet entier) → 0 problème.
- [ ] **Step 2:** `dart run tool/check_hardcoded_strings.dart` → 0.
- [ ] **Step 3:** `flutter test --coverage` → 0 rouge ; nouveaux fichiers couverts ≥ 90 %.
- [ ] **Step 4:** Push, `gh pr create` : description qui cite la PR back (A5) comme jumelle, précise que l'app tolère l'ancien back (route absente → écran d'erreur avec Réessayer, pas de crash), finit par `https://claude.ai/code/session_01Co2qUYxiqdqrrhEKbVFRTv`.
- [ ] **Step 5:** Fusionner **seulement après** la PR back, CI verte. Puis nettoyage des deux worktrees et branches locales (règle CLAUDE.md).
