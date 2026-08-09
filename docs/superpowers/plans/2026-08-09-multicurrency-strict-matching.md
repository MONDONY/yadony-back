# Multi-devise — matching strict (sans conversion) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer le mécanisme cassé Stripe `fx_quote` par un modèle sans conversion : chaque trajet/colis est figé dans la devise de son créateur, un utilisateur ne voit et ne paie que ce qui est dans sa devise active, et le wallet devient multi-lignes (une par devise déjà utilisée).

**Architecture:** `CurrencyMatchGuard` centralise la règle "devise payeur == devise annonce" (create bid, négociation, checkout). La devise est dénormalisée sur `Bid`/`NegotiationThread` à la création (copiée de l'annonce/demande parente, immuable). `WalletAccountEntity` passe de `UNIQUE(user_id)` à `UNIQUE(user_id, currency)` : plusieurs lignes par utilisateur, une seule "active" (= devise business-prefs courante). `StripeFxQuoteService` et tout appareillage `fx_quote` sont supprimés.

**Tech Stack:** Spring Boot 3.5.x, Java 21, PostgreSQL/Flyway, JUnit 5/Mockito/MockMvc, Flutter/Dart, BLoC, Dio, GoRouter, Hive.

## Global Constraints

- Ne jamais modifier une migration Flyway existante ; créer `V(n+1)` (dernière migration actuelle : `V196__widen_business_prefs_currency.sql`, donc démarrer à `V197`).
- Ne jamais faire de DELETE physique ; respecter le soft-delete existant (`@Where(clause = "deleted_at IS NULL")`).
- `GlobalExceptionHandler` : toute erreur métier passe par `YadonyBusinessException` (RFC 7807 `ProblemDetail`), jamais de String/Map brut.
- `@PreAuthorize` déjà en place sur les endpoints concernés — ne pas y toucher.
- Devise assignée à la création d'une annonce/demande **côté serveur uniquement** (lue depuis `business-prefs` du créateur), jamais confiée au payload client.
- Flutter : BLoC uniquement (jamais `setState` hors état UI local), GoRouter uniquement (jamais `Navigator.push`).
- Couverture de tests ≥ 90 % back et front (cf. CLAUDE.md), tous les tests doivent passer avant tout commit.
- Jamais de `Co-Authored-By: Claude` dans les messages de commit.
- Ne jamais commit sur `main` — travail sur `feature/multicurrency-stripe-back` (worktree `.worktrees/dony-back-multicurrency`) et `feature/multicurrency-stripe-app` (worktree `.worktrees/dony-app-multicurrency`).

---

## BACKEND — `dony-back` (worktree `.worktrees/dony-back-multicurrency`)

### Task 1: Migrations — colonnes devise + contrainte wallet

**Files:**
- Create: `src/main/resources/db/migration/V197__announcement_package_request_currency.sql`
- Create: `src/main/resources/db/migration/V198__bid_negotiation_wallet_tx_currency.sql`
- Create: `src/main/resources/db/migration/V199__wallet_accounts_per_currency.sql`
- Modify: `src/test/java/com/yadony/api/migrations/V89MigrationTest.java` (ou test H2 équivalent le plus récent référencé — vérifier lequel construit les fixtures `announcements`/`bids`/`wallet_accounts` en dur et y ajouter les nouvelles colonnes)

**Interfaces:**
- Produces: colonnes `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` sur `announcements`, `package_requests`, `bids`, `negotiation_threads`, `wallet_transactions` ; contrainte `wallet_accounts` `UNIQUE(user_id, currency)`.

- [ ] **Step 1: Écrire V197**

```sql
ALTER TABLE announcements
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD CONSTRAINT chk_announcement_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF'));

ALTER TABLE package_requests
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD CONSTRAINT chk_package_request_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF'));
```

- [ ] **Step 2: Écrire V198**

```sql
ALTER TABLE bids
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD CONSTRAINT chk_bid_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF'));

ALTER TABLE negotiation_threads
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD CONSTRAINT chk_negotiation_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF'));

ALTER TABLE wallet_transactions
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD CONSTRAINT chk_wallet_tx_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF'));
```

- [ ] **Step 3: Écrire V199**

```sql
ALTER TABLE wallet_accounts DROP CONSTRAINT wallet_accounts_user_id_key;
ALTER TABLE wallet_accounts ADD CONSTRAINT uq_wallet_accounts_user_currency UNIQUE (user_id, currency);
```

Note : le nom exact de la contrainte unique existante (`wallet_accounts_user_id_key`) est celui généré par défaut par PostgreSQL pour `@Column(unique = true)` sur `user_id` — vérifier avec `\d wallet_accounts` en local avant d'appliquer si le nom diffère :
```bash
docker exec yadony_db psql -U yadony -d yadony_dev -c "\d wallet_accounts"
```

- [ ] **Step 4: Lancer le back en dev pour vérifier que les 3 migrations s'appliquent sans erreur**

```bash
cd .worktrees/dony-back-multicurrency
source .env.dev && set +a && ./mvnw spring-boot:run -Dspring.profiles.active=dev
```
Vérifier dans les logs : `Migrating schema "public" to version "199"` sans erreur, puis `curl http://localhost:8080/api/v1/actuator/health` → `{"status":"UP"}`. Arrêter le process ensuite (`Ctrl+C` ou `kill <pid>`).

- [ ] **Step 5: Trouver et corriger le test de migration H2 qui construit des fixtures en dur**

```bash
rtk proxy grep -rl "INSERT INTO announcements\|INSERT INTO bids\|INSERT INTO wallet_accounts" src/test/java/
```
Pour chaque fichier trouvé qui échouerait sur les nouvelles colonnes `NOT NULL` sans `DEFAULT` en DDL H2 (piège déjà rencontré cette session sur `V89MigrationTest`), ajouter `currency` avec la valeur `'EUR'` à l'`INSERT` correspondant.

- [ ] **Step 6: Lancer la suite de tests migrations**

```bash
./mvnw test -Dtest='*MigrationTest' 2>&1 | grep -E "Tests run|FAIL"
```
Expected: `Tests run: N, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V197__announcement_package_request_currency.sql \
        src/main/resources/db/migration/V198__bid_negotiation_wallet_tx_currency.sql \
        src/main/resources/db/migration/V199__wallet_accounts_per_currency.sql \
        src/test/java/com/yadony/api/migrations/
git commit -m "feat(currency): migrations devise trajet/colis/bid + wallet multi-devise"
```

---

### Task 2: `CurrencyMatchGuard`

**Files:**
- Create: `src/main/java/com/yadony/api/payments/currency/CurrencyMatchGuard.java`
- Create: `src/test/java/com/yadony/api/payments/currency/CurrencyMatchGuardTest.java`

**Interfaces:**
- Produces: `CurrencyMatchGuard.assertMatches(String listingCurrency, String actorCurrency)` — `void`, `throws YadonyBusinessException` (422) si différent (comparaison insensible à la casse).

- [ ] **Step 1: Écrire le test rouge**

```java
package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyMatchGuardTest {

    private final CurrencyMatchGuard guard = new CurrencyMatchGuard();

    @Test
    void doesNotThrowWhenCurrenciesMatch() {
        guard.assertMatches("EUR", "EUR");
        guard.assertMatches("eur", "EUR"); // insensible à la casse
    }

    @Test
    void throwsCurrencyMismatchWhenDifferent() {
        assertThatThrownBy(() -> guard.assertMatches("EUR", "CAD"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException business = (YadonyBusinessException) ex;
                    assertThat(business.getStatus().value()).isEqualTo(422);
                    assertThat(business.getErrorCode()).isEqualTo("currency-mismatch");
                    assertThat(business.getProperties()).containsEntry("listingCurrency", "EUR");
                    assertThat(business.getProperties()).containsEntry("actorCurrency", "CAD");
                });
    }
}
```

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=CurrencyMatchGuardTest
```
Expected: FAIL (classe `CurrencyMatchGuard` inexistante)

- [ ] **Step 3: Implémentation**

```java
package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CurrencyMatchGuard {

    public void assertMatches(String listingCurrency, String actorCurrency) {
        if (listingCurrency == null || actorCurrency == null
                || !listingCurrency.equalsIgnoreCase(actorCurrency)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "currency-mismatch",
                    "Currency Mismatch",
                    "Ce trajet est publié en " + listingCurrency
                            + ", ton compte est en " + actorCurrency + ".",
                    Map.of(
                            "listingCurrency", listingCurrency,
                            "actorCurrency", actorCurrency));
        }
    }
}
```

- [ ] **Step 4: Vérifier le succès**

```bash
./mvnw test -Dtest=CurrencyMatchGuardTest
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/payments/currency/CurrencyMatchGuard.java \
        src/test/java/com/yadony/api/payments/currency/CurrencyMatchGuardTest.java
git commit -m "feat(currency): CurrencyMatchGuard centralisé"
```

---

### Task 3: `AnnouncementEntity` + `AnnouncementSpecification` — champ et filtre devise

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementEntity.java` (ajout champ, près de `status` — un champ similaire existe déjà en haut du fichier, suivre le même style `@Column`)
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementSpecification.java` (ajout prédicat, à côté de `hasStatus` ligne ~18)
- Test: `src/test/java/com/yadony/api/matching/AnnouncementSpecificationTest.java` (créer si absent, sinon ajouter un cas)

**Interfaces:**
- Produces: `AnnouncementEntity.getCurrency()` / `.setCurrency(String)` ; `AnnouncementSpecification.hasCurrency(String currency)`.

- [ ] **Step 1: Ajouter le champ à `AnnouncementEntity`**

Ajouter après le champ `status` (ou tout champ `String` simple existant, ex. `description`) :
```java
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
```

- [ ] **Step 2: Écrire le test rouge pour le prédicat**

```java
// src/test/java/com/yadony/api/matching/AnnouncementSpecificationTest.java
package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnnouncementSpecificationTest {

    @Autowired
    AnnouncementRepository announcementRepository;

    @Test
    void hasCurrencyFiltersByExactCurrency() {
        AnnouncementEntity eurAnnouncement = buildAnnouncement("EUR");
        AnnouncementEntity cadAnnouncement = buildAnnouncement("CAD");
        announcementRepository.save(eurAnnouncement);
        announcementRepository.save(cadAnnouncement);

        var results = announcementRepository.findAll(AnnouncementSpecification.hasCurrency("CAD"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getCurrency()).isEqualTo("CAD");
    }

    private AnnouncementEntity buildAnnouncement(String currency) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(java.util.UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setCurrency(currency);
        return a;
    }
}
```
Adapter les setters obligatoires si `AnnouncementRepository.save` échoue sur d'autres colonnes `NOT NULL` — s'aligner sur un test existant du même package pour les champs minimum requis (ex. `AnnouncementServiceTest`).

- [ ] **Step 3: Vérifier l'échec**

```bash
./mvnw test -Dtest=AnnouncementSpecificationTest
```
Expected: FAIL (méthode `hasCurrency` inexistante)

- [ ] **Step 4: Ajouter le prédicat**

Dans `AnnouncementSpecification.java`, à côté de `hasStatus` :
```java
    public static Specification<AnnouncementEntity> hasCurrency(String currency) {
        return (root, query, cb) -> cb.equal(root.get("currency"), currency);
    }
```

- [ ] **Step 5: Vérifier le succès**

```bash
./mvnw test -Dtest=AnnouncementSpecificationTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yadony/api/matching/AnnouncementEntity.java \
        src/main/java/com/yadony/api/matching/AnnouncementSpecification.java \
        src/test/java/com/yadony/api/matching/AnnouncementSpecificationTest.java
git commit -m "feat(currency): champ devise + filtre AnnouncementSpecification"
```

---

### Task 4: `AnnouncementService` — assignation à la création + filtre recherche

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementService.java` (constructeur ligne ~108, `createAnnouncement` ligne ~307-354, `searchAnnouncements` ligne ~140-163)
- Test: `src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java` (ajouter les cas)

**Interfaces:**
- Consumes: `UserBusinessPrefsRepository.findById(UUID)` → `Optional<UserBusinessPrefsEntity>` (existant), `.getCurrencyCode()` (existant, défaut `"EUR"`).
- Produces: `createAnnouncement` assigne `currency` depuis les prefs du créateur ; `searchAnnouncements` filtre par devise du viewer.

- [ ] **Step 1: Écrire les tests rouges**

Dans `AnnouncementServiceTest.java`, ajouter :
```java
@Test
void createAnnouncement_assignsCurrencyFromCreatorBusinessPrefs() {
    UserEntity traveler = buildVerifiedTraveler(); // helper existant du fichier, adapter le nom si différent
    when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(traveler));
    UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
    prefs.setUserId(traveler.getId());
    prefs.setCurrencyCode("CAD");
    when(userBusinessPrefsRepository.findById(traveler.getId())).thenReturn(Optional.of(prefs));
    when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    AnnouncementResponse response = service.createAnnouncement("uid", buildValidRequest());

    ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
    verify(announcementRepository).save(captor.capture());
    assertThat(captor.getValue().getCurrency()).isEqualTo("CAD");
}

@Test
void createAnnouncement_defaultsToEurWhenNoBusinessPrefs() {
    UserEntity traveler = buildVerifiedTraveler();
    when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(traveler));
    when(userBusinessPrefsRepository.findById(traveler.getId())).thenReturn(Optional.empty());
    when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.createAnnouncement("uid", buildValidRequest());

    ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
    verify(announcementRepository).save(captor.capture());
    assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
}
```
Ajouter `@Mock UserBusinessPrefsRepository userBusinessPrefsRepository;` au fichier de test et l'injecter dans le constructeur `service = new AnnouncementService(..., userBusinessPrefsRepository)` en suivant l'ordre des autres mocks déjà présents. Si `buildVerifiedTraveler()`/`buildValidRequest()` n'existent pas sous ce nom exact, réutiliser les helpers déjà présents dans le fichier pour un test `createAnnouncement` existant.

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=AnnouncementServiceTest
```
Expected: FAIL (compilation : `UserBusinessPrefsRepository` pas encore un paramètre du constructeur)

- [ ] **Step 3: Ajouter la dépendance au constructeur**

Dans `AnnouncementService.java`, ajouter `UserBusinessPrefsRepository userBusinessPrefsRepository` à la liste de paramètres du constructeur (ligne ~108-136) et au champ `private final UserBusinessPrefsRepository userBusinessPrefsRepository;` correspondant, en suivant le style des autres champs `private final`.

- [ ] **Step 4: Assigner la devise à la création**

Dans `createAnnouncement`, juste après la construction de `announcement` (après `announcement.setTravelerId(user.getId());` ligne ~355) :
```java
        String creatorCurrency = userBusinessPrefsRepository.findById(user.getId())
                .map(UserBusinessPrefsEntity::getCurrencyCode)
                .orElse("EUR");
        announcement.setCurrency(creatorCurrency);
```

- [ ] **Step 5: Filtrer la recherche par devise du viewer**

Dans `searchAnnouncements`, juste après la résolution de `viewerId` (ligne ~155) :
```java
        String viewerCurrency = viewerId != null
                ? userBusinessPrefsRepository.findById(viewerId)
                        .map(UserBusinessPrefsEntity::getCurrencyCode)
                        .orElse("EUR")
                : "EUR";
```
Puis, juste après la ligne `spec = AnnouncementSpecification.hasStatus(...).and(AnnouncementSpecification.publicOrOpenSurplus());` :
```java
        spec = spec.and(AnnouncementSpecification.hasCurrency(viewerCurrency));
```

**Attention cache** : la clé `@Cacheable` de `searchAnnouncements` (ligne ~139) inclut déjà `viewerFirebaseUid` — comme la devise en dépend indirectement (via `userBusinessPrefsRepository`), pas besoin d'ajouter la devise séparément à la clé de cache, `viewerFirebaseUid` suffit à distinguer deux utilisateurs de devises différentes.

- [ ] **Step 6: Vérifier le succès**

```bash
./mvnw test -Dtest=AnnouncementServiceTest
```
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yadony/api/matching/AnnouncementService.java \
        src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java
git commit -m "feat(currency): AnnouncementService assigne et filtre par devise"
```

---

### Task 5: `PackageRequestEntity` + `PackageRequestSpecification` — champ et filtre devise

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java` (ajout champ près de `status`, ligne ~84)
- Modify: fichier de spécification JPA du package request (localiser avec `find src/main/java/com/yadony/api/requests -iname "*Specification*.java"`)
- Test: fichier de test correspondant

**Interfaces:**
- Produces: `PackageRequestEntity.getCurrency()`/`.setCurrency(String)` ; prédicat `hasCurrency(String)` sur la spécification équivalente.

- [ ] **Step 1: Localiser la classe de spécification**

```bash
find src/main/java/com/yadony/api/requests -iname "*Specification*.java"
```

- [ ] **Step 2: Ajouter le champ à `PackageRequestEntity`** (même pattern que Task 3 Step 1)

```java
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
```

- [ ] **Step 3: Écrire le test rouge du prédicat** (même structure que `AnnouncementSpecificationTest`, adapté à `PackageRequestEntity`/`PackageRequestRepository`)

- [ ] **Step 4: Vérifier l'échec, ajouter le prédicat `hasCurrency`, vérifier le succès** (même déroulé que Task 3 Steps 3-5)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java \
        src/main/java/com/yadony/api/requests/
git commit -m "feat(currency): champ devise + filtre PackageRequestSpecification"
```

---

### Task 6: `PackageRequestService` — assignation à la création + filtre recherche

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java` (`createAndReturnEntity` ligne ~179-227, méthode de recherche — localiser avec `rtk proxy grep -n "public Page<.*Response> search" src/main/java/com/yadony/api/requests/service/PackageRequestService.java`)

**Interfaces:**
- Consumes: `UserBusinessPrefsRepository` (même que Task 4).
- Produces: même comportement que Task 4, pour les demandes de colis.

- [ ] **Step 1: Écrire les tests rouges** (même structure que Task 4 Step 1, adaptés à `PackageRequestService.createAndReturnEntity`)

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=PackageRequestServiceTest
```

- [ ] **Step 3: Ajouter `UserBusinessPrefsRepository` au constructeur**, assigner `entity.setCurrency(...)` juste après `entity.setSenderId(senderId)` (ou équivalent) dans `createAndReturnEntity`, en suivant exactement le pattern de Task 4 Step 4.

- [ ] **Step 4: Filtrer la méthode de recherche par devise du viewer**, même pattern que Task 4 Step 5.

- [ ] **Step 5: Vérifier le succès et commit**

```bash
./mvnw test -Dtest=PackageRequestServiceTest
git add src/main/java/com/yadony/api/requests/service/PackageRequestService.java \
        src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java
git commit -m "feat(currency): PackageRequestService assigne et filtre par devise"
```

---

### Task 7: `BidEntity` — champ devise + `BidService.createBid` — copie et garde

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/BidEntity.java` (ajout champ près de `status`, ligne ~69-71)
- Modify: `src/main/java/com/yadony/api/matching/BidService.java` (`createBid` ligne ~183-230+, constructeur pour injecter `CurrencyMatchGuard`)
- Test: `src/test/java/com/yadony/api/matching/BidServiceTest.java`

**Interfaces:**
- Consumes: `CurrencyMatchGuard.assertMatches(String, String)` (Task 2), `UserBusinessPrefsRepository` (Task 4).
- Produces: `BidEntity.getCurrency()`/`.setCurrency(String)`.

- [ ] **Step 1: Ajouter le champ à `BidEntity`**

```java
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
```

- [ ] **Step 2: Écrire les tests rouges**

```java
@Test
void createBid_copiesCurrencyFromAnnouncement() {
    AnnouncementEntity announcement = buildActiveAnnouncement(); // helper existant
    announcement.setCurrency("CAD");
    when(announcementRepository.findByIdForUpdate(announcement.getId()))
            .thenReturn(Optional.of(announcement));
    UserEntity sender = buildSender(); // helper existant
    when(userBusinessPrefsRepository.findById(sender.getId()))
            .thenReturn(Optional.of(prefsWithCurrency("CAD")));
    when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.createBid(announcement.getId(), "uid", buildValidBidRequest(), httpRequest);

    ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
    verify(bidRepository).save(captor.capture());
    assertThat(captor.getValue().getCurrency()).isEqualTo("CAD");
}

@Test
void createBid_throwsCurrencyMismatchWhenSenderCurrencyDiffers() {
    AnnouncementEntity announcement = buildActiveAnnouncement();
    announcement.setCurrency("EUR");
    when(announcementRepository.findByIdForUpdate(announcement.getId()))
            .thenReturn(Optional.of(announcement));
    UserEntity sender = buildSender();
    when(userBusinessPrefsRepository.findById(sender.getId()))
            .thenReturn(Optional.of(prefsWithCurrency("CAD")));

    assertThatThrownBy(() ->
            service.createBid(announcement.getId(), "uid", buildValidBidRequest(), httpRequest))
            .isInstanceOf(YadonyBusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "currency-mismatch");
}
```
Ajouter le helper `prefsWithCurrency(String code)` s'il n'existe pas :
```java
private UserBusinessPrefsEntity prefsWithCurrency(String code) {
    UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
    prefs.setCurrencyCode(code);
    return prefs;
}
```
Ajouter `@Mock UserBusinessPrefsRepository userBusinessPrefsRepository;` et `CurrencyMatchGuard currencyMatchGuard = new CurrencyMatchGuard();` (instance réelle, pas de mock — c'est une pure fonction sans dépendance) au setup du fichier de test, et les passer au constructeur de `BidService`.

- [ ] **Step 3: Vérifier l'échec**

```bash
./mvnw test -Dtest=BidServiceTest
```
Expected: FAIL (compilation)

- [ ] **Step 4: Injecter les dépendances et implémenter**

Ajouter `CurrencyMatchGuard currencyMatchGuard` et `UserBusinessPrefsRepository userBusinessPrefsRepository` au constructeur de `BidService` (mêmes champs `private final`).

Dans `createBid`, juste après le bloc `if (announcement.getStatus() != AnnouncementStatus.ACTIVE) { ... }` (ligne ~203) :
```java
        String senderCurrency = userBusinessPrefsRepository.findById(sender.getId())
                .map(UserBusinessPrefsEntity::getCurrencyCode)
                .orElse("EUR");
        currencyMatchGuard.assertMatches(announcement.getCurrency(), senderCurrency);
```

Puis, à la construction du bid (`BidEntity bid = new BidEntity();` ligne ~337), ajouter :
```java
        bid.setCurrency(announcement.getCurrency());
```

- [ ] **Step 5: Vérifier le succès**

```bash
./mvnw test -Dtest=BidServiceTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yadony/api/matching/BidEntity.java \
        src/main/java/com/yadony/api/matching/BidService.java \
        src/test/java/com/yadony/api/matching/BidServiceTest.java
git commit -m "feat(currency): BidService copie la devise de l'annonce + garde"
```

---

### Task 8: `NegotiationThreadEntity` — champ devise + `NegotiationService.start` — copie et garde

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/entity/NegotiationThreadEntity.java` (ajout champ près de `status`, ligne ~40)
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java` (`start` ligne ~104-170+)
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`

**Interfaces:**
- Consumes: `CurrencyMatchGuard` (Task 2), `UserBusinessPrefsRepository` (Task 4).
- Produces: `NegotiationThreadEntity.getCurrency()`/`.setCurrency(String)`.

- [ ] **Step 1: Ajouter le champ** (même pattern que Task 7 Step 1, adapté à `NegotiationThreadEntity`)

- [ ] **Step 2: Écrire les tests rouges** (même structure que Task 7 Step 2, adaptés : `PackageRequestEntity.getCurrency()` au lieu de l'annonce, `traveler` au lieu de `sender`)

```java
@Test
void start_copiesCurrencyFromPackageRequest() {
    PackageRequestEntity request = buildOpenRequest(); // helper existant
    request.setCurrency("GBP");
    when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
    UserEntity traveler = buildVerifiedTraveler();
    when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
    when(userBusinessPrefsRepository.findById(traveler.getId()))
            .thenReturn(Optional.of(prefsWithCurrency("GBP")));
    when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.start(traveler.getId(), buildValidStartRequest(request.getId()));

    ArgumentCaptor<NegotiationThreadEntity> captor = ArgumentCaptor.forClass(NegotiationThreadEntity.class);
    verify(threadRepo).save(captor.capture());
    assertThat(captor.getValue().getCurrency()).isEqualTo("GBP");
}

@Test
void start_throwsCurrencyMismatchWhenTravelerCurrencyDiffers() {
    PackageRequestEntity request = buildOpenRequest();
    request.setCurrency("EUR");
    when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
    UserEntity traveler = buildVerifiedTraveler();
    when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
    when(userBusinessPrefsRepository.findById(traveler.getId()))
            .thenReturn(Optional.of(prefsWithCurrency("GBP")));

    assertThatThrownBy(() -> service.start(traveler.getId(), buildValidStartRequest(request.getId())))
            .isInstanceOf(YadonyBusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "currency-mismatch");
}
```

- [ ] **Step 3: Vérifier l'échec**

```bash
./mvnw test -Dtest=NegotiationServiceTest
```

- [ ] **Step 4: Injecter les dépendances et implémenter**

Ajouter `CurrencyMatchGuard currencyMatchGuard` et `UserBusinessPrefsRepository userBusinessPrefsRepository` au constructeur.

Dans `start`, juste après le bloc `if (request.getStatus() != PackageRequestStatus.OPEN && request.getStatus() != PackageRequestStatus.NEGOTIATING) { ... }` (avant le check `!request.isNegotiable()`, ligne ~135) :
```java
        String travelerCurrency = userBusinessPrefsRepository.findById(travelerId)
                .map(UserBusinessPrefsEntity::getCurrencyCode)
                .orElse("EUR");
        currencyMatchGuard.assertMatches(request.getCurrency(), travelerCurrency);
```

À la construction du thread (`NegotiationThreadEntity thread = new NegotiationThreadEntity();` ligne ~164), ajouter :
```java
        thread.setCurrency(request.getCurrency());
```

- [ ] **Step 5: Vérifier le succès et commit**

```bash
./mvnw test -Dtest=NegotiationServiceTest
git add src/main/java/com/yadony/api/requests/entity/NegotiationThreadEntity.java \
        src/main/java/com/yadony/api/requests/service/NegotiationService.java \
        src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java
git commit -m "feat(currency): NegotiationService copie la devise de la demande + garde"
```

---

### Task 9: `PaymentService.createEscrow` — retrait fx_quote, devise = celle du bid

**Files:**
- Modify: `src/main/java/com/yadony/api/payments/PaymentService.java` (bloc `createEscrow`/méthode contenant le code lu en amont, lignes ~496-565 et son équivalent legacy lignes ~1520-1565)
- Test: `src/test/java/com/yadony/api/payments/PaymentServiceTest.java`, `src/test/java/com/yadony/api/payments/PaymentServiceOnBehalfOfTest.java`

**Interfaces:**
- Consumes: `CurrencyMatchGuard` (Task 2), `bid.getCurrency()` (Task 7).
- Produces: `PaymentEntity.currency` = `bid.getCurrency()` directement (plus de résolution via `currencyCatalog.resolve(sender.getCountry(), request.getCurrencyCode())`, plus d'appel `StripeFxQuoteService`).

- [ ] **Step 1: Repérer les deux occurrences du pattern fx_quote**

```bash
rtk proxy grep -n "createFxQuote\|convertForPayment\|fx_quote\|currencyCatalog.resolve" src/main/java/com/yadony/api/payments/PaymentService.java
```

- [ ] **Step 2: Écrire le test rouge** (dans `PaymentServiceTest.java`, à côté d'un test existant sur `createEscrow`)

```java
@Test
void createEscrow_neverCallsStripeFxQuoteService() {
    BidEntity bid = buildAcceptedBid(); // helper existant
    bid.setCurrency("CAD");
    // ... stubs existants du test (sender, traveler, announcement, repos) ...

    service.createEscrow(bid.getId(), senderFirebaseUid, buildValidPaymentRequest());

    verifyNoInteractions(stripeFxQuoteService);
}

@Test
void createEscrow_throwsCurrencyMismatchWhenSenderCurrencyDiffersFromBid() {
    BidEntity bid = buildAcceptedBid();
    bid.setCurrency("EUR");
    when(userBusinessPrefsRepository.findById(sender.getId()))
            .thenReturn(Optional.of(prefsWithCurrency("CAD")));

    assertThatThrownBy(() -> service.createEscrow(bid.getId(), senderFirebaseUid, buildValidPaymentRequest()))
            .isInstanceOf(YadonyBusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "currency-mismatch");
}
```
Adapter les noms de helpers (`buildAcceptedBid`, `buildValidPaymentRequest`, `senderFirebaseUid`) à ceux réellement présents dans `PaymentServiceTest.java` — les lire d'abord avec `head -80 src/test/java/com/yadony/api/payments/PaymentServiceTest.java` avant d'écrire ce step.

- [ ] **Step 3: Vérifier l'échec**

```bash
./mvnw test -Dtest=PaymentServiceTest
```

- [ ] **Step 4: Supprimer le fx_quote et brancher `CurrencyMatchGuard`**

Remplacer (dans les deux occurrences trouvées au Step 1) :
```java
        SupportedCurrency currency = currencyCatalog.resolve(sender.getCountry(), request.getCurrencyCode());
        StripeFxQuoteService.FxQuoteSnapshot fxQuote = createFxQuote(currency);
        CurrencyAmount localAmount = convertForPayment(amount, currency, fxQuote);
        CurrencyAmount localCommission = convertForPayment(commission, currency, fxQuote);
```
par :
```java
        String senderCurrency = userBusinessPrefsRepository.findById(sender.getId())
                .map(UserBusinessPrefsEntity::getCurrencyCode)
                .orElse("EUR");
        currencyMatchGuard.assertMatches(bid.getCurrency(), senderCurrency);
        SupportedCurrency currency = SupportedCurrency.fromCode(bid.getCurrency());
        CurrencyAmount localAmount = CurrencyAmount.of(amount, currency);
        CurrencyAmount localCommission = CurrencyAmount.of(commission, currency);
```

Puis supprimer le bloc `if (fxQuote != null) { paramsBuilder.putExtraParam("fx_quote", ...) ... }` (ligne ~536-540 et son équivalent legacy) et le bloc équivalent sur `payment.setStripeFxQuoteId(...)` (ligne ~560-564 et équivalent legacy).

Ajouter `CurrencyMatchGuard currencyMatchGuard` et `UserBusinessPrefsRepository userBusinessPrefsRepository` au constructeur de `PaymentService` s'ils n'y sont pas déjà (vérifier d'abord — `PaymentService` a peut-être déjà `CurrencyCatalog` injecté depuis le chantier fx_quote précédent, auquel cas remplacer l'usage plutôt qu'ajouter un doublon).

Supprimer aussi les méthodes privées désormais mortes `createFxQuote(...)` et `convertForPayment(...)` si elles ne sont plus appelées ailleurs dans le fichier (`rtk proxy grep -n "createFxQuote\|convertForPayment" src/main/java/com/yadony/api/payments/PaymentService.java` pour confirmer 0 appelant restant avant suppression).

- [ ] **Step 5: Vérifier le succès**

```bash
./mvnw test -Dtest=PaymentServiceTest,PaymentServiceOnBehalfOfTest
```
Expected: PASS. Si `PaymentServiceOnBehalfOfTest` échoue sur des assertions liées à `fx_quote`/`putExtraParam`, les retirer (elles testaient le mécanisme qu'on supprime).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yadony/api/payments/PaymentService.java \
        src/test/java/com/yadony/api/payments/PaymentServiceTest.java \
        src/test/java/com/yadony/api/payments/PaymentServiceOnBehalfOfTest.java
git commit -m "feat(currency): PaymentService.createEscrow abandonne fx_quote, devise = bid"
```

---

### Task 10: `WalletAccountEntity` + `WalletAccountRepository` — multi-devise

**Files:**
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletAccountEntity.java`
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletAccountRepository.java`
- Test: créer `src/test/java/com/yadony/api/payments/wallet/WalletAccountRepositoryTest.java` si aucun test repository n'existe déjà pour cette classe

**Interfaces:**
- Produces: `WalletAccountRepository.findByUserIdAndCurrency(UUID, String)`, `.findByUserIdAndCurrencyForUpdate(UUID, String)`, `.findAllByUserId(UUID)` (liste toutes les lignes devise d'un utilisateur, pour l'écran wallet).

- [ ] **Step 1: Écrire le test rouge**

```java
package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WalletAccountRepositoryTest {

    @Autowired WalletAccountRepository repository;

    @Test
    void allowsMultipleCurrencyRowsPerUser() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity eur = new WalletAccountEntity();
        eur.setUserId(userId);
        eur.setCurrency("EUR");
        eur.setBalance(new BigDecimal("10.00"));
        repository.save(eur);

        WalletAccountEntity cad = new WalletAccountEntity();
        cad.setUserId(userId);
        cad.setCurrency("CAD");
        cad.setBalance(new BigDecimal("5.00"));
        repository.save(cad);

        assertThat(repository.findAllByUserId(userId)).hasSize(2);
        assertThat(repository.findByUserIdAndCurrency(userId, "CAD")).isPresent();
    }
}
```

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=WalletAccountRepositoryTest
```
Expected: FAIL (compilation : méthodes/champ `currency` inexistants)

- [ ] **Step 3: Ajouter `currency` à `WalletAccountEntity`**

Remplacer le contenu du fichier par :
```java
package com.yadony.api.payments.wallet;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallet_accounts")
@Where(clause = "deleted_at IS NULL")
public class WalletAccountEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
```
(Retrait de `unique = true` sur `user_id`, désormais géré par la contrainte composite `V199`.)

- [ ] **Step 4: Ajouter les méthodes au repository**

```java
package com.yadony.api.payments.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletAccountRepository extends JpaRepository<WalletAccountEntity, UUID> {

    Optional<WalletAccountEntity> findByUserIdAndCurrency(UUID userId, String currency);

    List<WalletAccountEntity> findAllByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletAccountEntity w WHERE w.userId = :userId AND w.currency = :currency")
    Optional<WalletAccountEntity> findByUserIdAndCurrencyForUpdate(
            @Param("userId") UUID userId, @Param("currency") String currency);
}
```

- [ ] **Step 5: Vérifier le succès et commit**

```bash
./mvnw test -Dtest=WalletAccountRepositoryTest
git add src/main/java/com/yadony/api/payments/wallet/WalletAccountEntity.java \
        src/main/java/com/yadony/api/payments/wallet/WalletAccountRepository.java \
        src/test/java/com/yadony/api/payments/wallet/WalletAccountRepositoryTest.java
git commit -m "feat(currency): WalletAccountEntity/Repository multi-devise"
```

---

### Task 11: `WalletService` — devise explicite sur toutes les méthodes

**Files:**
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletService.java` (fichier entier, ~150 lignes, remplacer les 5 méthodes publiques)
- Modify: `src/main/java/com/yadony/api/payments/cash/CashCommissionService.java` (call sites `walletService.*`)
- Modify: `src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java` (idem)
- Modify: `src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java` (idem)
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletController.java` (idem)
- Modify: `src/main/java/com/yadony/api/payments/wallet/ReferralRewardWalletListener.java` (idem)
- Test: `src/test/java/com/yadony/api/payments/wallet/WalletServiceTest.java`

**Interfaces:**
- Produces (nouvelles signatures) :
  - `WalletAccountEntity getOrCreate(UUID userId, String currency)`
  - `BigDecimal getBalance(UUID userId, String currency)`
  - `List<WalletAccountEntity> getAllBalances(UUID userId)` (nouveau — pour l'écran wallet multi-lignes)
  - `void credit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, String paymentRef, String idempotencyKey)`
  - `void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId)`
  - `void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, String paymentRef, String idempotencyKey)`

**Décision de scope** : les 5 call sites existants (`CashCommissionService`, `CashGateAdapter`, `PaymentStripeWebhookHandler`, `WalletController`, `ReferralRewardWalletListener`) gèrent tous de la commission/récompense **en EUR** aujourd'hui (aucun n'a de notion de devise dynamique) — ils passent la constante `"EUR"` explicitement, comportement inchangé. Seul `WalletTopupOrchestrator` (Task 12) utilisera une devise dynamique.

- [ ] **Step 1: Écrire les tests rouges** (réécrire entièrement `WalletServiceTest.java` avec la nouvelle signature — lire d'abord le fichier existant pour connaître les helpers déjà en place)

```java
@Test
void creditAndDebitAreScopedPerCurrency() {
    UUID userId = UUID.randomUUID();
    service.credit(userId, "EUR", new BigDecimal("20.00"), WalletTransactionType.TOPUP, "ref1", "idem1");
    service.credit(userId, "CAD", new BigDecimal("15.00"), WalletTransactionType.TOPUP, "ref2", "idem2");

    assertThat(service.getBalance(userId, "EUR")).isEqualByComparingTo("20.00");
    assertThat(service.getBalance(userId, "CAD")).isEqualByComparingTo("15.00");
    assertThat(service.getAllBalances(userId)).hasSize(2);
}
```

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=WalletServiceTest
```

- [ ] **Step 3: Réécrire `WalletService.java`**

```java
package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletAccountRepository walletAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AuditService auditService;

    public WalletService(WalletAccountRepository walletAccountRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         AuditService auditService) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.auditService = auditService;
    }

    public WalletAccountEntity getOrCreate(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, currency).orElseGet(() -> {
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setUserId(userId);
            wallet.setCurrency(currency);
            return walletAccountRepository.save(wallet);
        });
    }

    public BigDecimal getBalance(UUID userId, String currency) {
        return getOrCreate(userId, currency).getBalance();
    }

    public List<WalletAccountEntity> getAllBalances(UUID userId) {
        return walletAccountRepository.findAllByUserId(userId);
    }

    public List<WalletTransactionEntity> getTransactions(UUID userId, int page) {
        return walletTransactionRepository
            .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 50))
            .getContent();
    }

    public void credit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                       String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent credit ignored for key={}", idempotencyKey);
                return;
            }
        }

        WalletAccountEntity wallet = getOrCreate(userId, currency);
        BigDecimal newBalance = wallet.getBalance().add(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(currency);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "currency", currency,
                    "paymentRef", String.valueOf(paymentRef)));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type, UUID bidId) {
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
            .orElseGet(() -> getOrCreate(userId, currency));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(currency);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setBidId(bidId);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "currency", currency, "bidId", String.valueOf(bidId)));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, String currency, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent debit ignored for key={}", idempotencyKey);
                return;
            }
        }

        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
            .orElseGet(() -> getOrCreate(userId, currency));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(currency);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "currency", currency,
                    "paymentRef", String.valueOf(paymentRef)));
    }
}
```

- [ ] **Step 4: Ajouter `currency` à `WalletTransactionEntity`**

```java
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
```

- [ ] **Step 5: Mettre à jour les 5 call sites avec `"EUR"` explicite**

```bash
rtk proxy grep -n "walletService\.\(credit\|debit\|getOrCreate\|getBalance\)" \
  src/main/java/com/yadony/api/payments/cash/CashCommissionService.java \
  src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java \
  src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java \
  src/main/java/com/yadony/api/payments/wallet/WalletController.java \
  src/main/java/com/yadony/api/payments/wallet/ReferralRewardWalletListener.java
```
Pour chaque appel trouvé, insérer `"EUR", ` juste après le premier argument (`userId`). Exemple type :
```java
// avant
walletService.credit(userId, amount, WalletTransactionType.COMMISSION, ref, key);
// après
walletService.credit(userId, "EUR", amount, WalletTransactionType.COMMISSION, ref, key);
```

- [ ] **Step 6: Vérifier la compilation complète et les tests**

```bash
./mvnw compile
./mvnw test -Dtest=WalletServiceTest,CashCommissionServiceTest,PaymentStripeWebhookHandlerTest,WalletControllerIT,ReferralRewardWalletListenerTest
```
Corriger les tests existants de ces 5 fichiers qui appellent `walletService.*` avec l'ancienne signature (ajouter `"EUR"` dans les `verify(...)`/`when(...)` correspondants).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yadony/api/payments/wallet/ src/main/java/com/yadony/api/payments/cash/ \
        src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java \
        src/test/java/com/yadony/api/payments/wallet/WalletServiceTest.java
git commit -m "feat(currency): WalletService devise explicite sur toutes les méthodes"
```

---

### Task 12: `WalletTopupOrchestrator` — retrait fx_quote, devise dynamique réelle

**Files:**
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletTopupOrchestrator.java` (fichier entier, ~85 lignes)
- Test: `src/test/java/com/yadony/api/payments/wallet/WalletTopupCurrencyTest.java`

**Interfaces:**
- Consumes: `WalletService.credit(UUID, String, ...)` (Task 11).
- Produces: `initiateStripe` charge la carte dans `currency` et crédite `WalletService` dans la **même** devise, 1:1, sans FX.

- [ ] **Step 1: Écrire le test rouge**

```java
@Test
void topup_creditsWalletInSameCurrencyAsCharge_withoutFx() throws Exception {
    UUID userId = UUID.randomUUID();
    WalletTopupRequest request = new WalletTopupRequest();
    request.setAmount(new BigDecimal("20.00"));
    request.setPaymentMethod("STRIPE");
    request.setCurrencyCode("CAD");

    try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
        PaymentIntent fakePi = mock(PaymentIntent.class);
        when(fakePi.getClientSecret()).thenReturn("secret_test");
        mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(fakePi);

        orchestrator.initiate(userId, request);

        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
        // Le crédit réel se fait à la confirmation Stripe (webhook), pas à l'initiation —
        // ce test vérifie seulement qu'aucun appel FX/fx_quote n'a lieu ici.
        mockedPi.verify(() -> PaymentIntent.create(argThat((PaymentIntentCreateParams p) ->
                "cad".equals(p.getCurrency()) && !p.getExtraParams().containsKey("fx_quote"))));
    }
}
```
Adapter selon l'implémentation réelle : si le crédit wallet a effectivement lieu de manière synchrone dans `initiateStripe` (relire le fichier avant d'écrire ce test — la version actuelle ne credite jamais directement, elle crée juste le `PaymentIntent` ; le crédit se fait ailleurs sur confirmation webhook, cf. `PaymentStripeWebhookHandler`). Le point clé à tester ici est : **aucun appel** à `StripeFxQuoteService`, et le `PaymentIntent` est créé directement dans la devise demandée sans `fx_quote`.

- [ ] **Step 2: Vérifier l'échec**

```bash
./mvnw test -Dtest=WalletTopupCurrencyTest
```

- [ ] **Step 3: Réécrire `WalletTopupOrchestrator.java`**

```java
package com.yadony.api.payments.wallet;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.beans.factory.annotation.Autowired;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    private CurrencyCatalog currencyCatalog = new CurrencyCatalog();

    @Autowired
    public void configureCurrency(CurrencyCatalog currencyCatalog) {
        this.currencyCatalog = currencyCatalog;
    }

    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        return switch (request.getPaymentMethod()) {
            case "STRIPE" -> initiateStripe(userId, request);
            case "WAVE", "ORANGE_MONEY" -> throw new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "mobile-money-topup-retired", "Mobile Money Topup Retired",
                "Le rechargement par mobile money n'est plus disponible pour le moment. "
                + "Choisissez Carte bancaire.");
            default -> throw new IllegalArgumentException(
                "Mode de paiement inconnu : " + request.getPaymentMethod());
        };
    }

    private WalletTopupResponse initiateStripe(UUID userId, WalletTopupRequest request) {
        try {
            SupportedCurrency currency = currencyCatalog.resolve(null, request.getCurrencyCode());
            CurrencyAmount localAmount = CurrencyAmount.of(request.getAmount(), currency);

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(localAmount.minor())
                .setCurrency(currency.code())
                .putMetadata("wallet_topup", "true")
                .putMetadata("user_id", userId.toString())
                .putMetadata("wallet_currency", currency.code())
                .build();

            PaymentIntent pi = PaymentIntent.create(params);
            return new WalletTopupResponse(pi.getClientSecret(), null);
        } catch (Exception e) {
            throw new RuntimeException("Erreur Stripe topup", e);
        }
    }
}
```

Vérifier au préalable où le crédit wallet se déclenche réellement à la confirmation du topup (webhook Stripe, `PaymentStripeWebhookHandler` probablement — chercher `wallet_topup` dans ce fichier) et s'assurer que le code lisant `wallet_currency` depuis les metadata du `PaymentIntent` utilise bien `walletService.credit(userId, currency, ...)` (Task 11) au lieu de convertir en EUR. Localiser :
```bash
rtk proxy grep -n "wallet_topup\|wallet_currency" src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java
```
Si ce handler lit encore `wallet_credit_eur` (métadonnée retirée dans la réécriture ci-dessus) ou force `"EUR"`, l'ajuster pour lire `wallet_currency` et créditer dans cette devise.

- [ ] **Step 4: Vérifier le succès**

```bash
./mvnw test -Dtest=WalletTopupCurrencyTest,WalletTopupOrchestratorFxQuoteTest
```
`WalletTopupOrchestratorFxQuoteTest` (écrit dans la session de debug précédente) doit être supprimé à cette étape — voir Task 13.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/payments/wallet/WalletTopupOrchestrator.java \
        src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java \
        src/test/java/com/yadony/api/payments/wallet/WalletTopupCurrencyTest.java
git commit -m "feat(currency): WalletTopupOrchestrator abandonne fx_quote, crédit 1:1"
```

---

### Task 13: Suppression `StripeFxQuoteService` et tests devenus obsolètes

**Files:**
- Delete: `src/main/java/com/yadony/api/payments/currency/StripeFxQuoteService.java`
- Delete: `src/test/java/com/yadony/api/payments/currency/StripeFxQuoteServiceTest.java`
- Delete: `src/test/java/com/yadony/api/payments/wallet/WalletTopupOrchestratorFxQuoteTest.java`
- Modify: tout fichier référençant encore `StripeFxQuoteService` (localiser au Step 1)

**Interfaces:**
- N/A (suppression pure)

- [ ] **Step 1: Vérifier qu'aucun appelant ne subsiste**

```bash
rtk proxy grep -rln "StripeFxQuoteService" src/main/java/ src/test/java/
```
Après Tasks 9 et 12, cette recherche ne doit renvoyer que la classe elle-même et ses deux tests. Si d'autres fichiers apparaissent (ex. configuration Spring, autre service), les traiter avant de continuer — ne pas supprimer tant qu'un appelant existe encore (le build casserait).

- [ ] **Step 2: Supprimer les 3 fichiers**

```bash
git rm src/main/java/com/yadony/api/payments/currency/StripeFxQuoteService.java \
       src/test/java/com/yadony/api/payments/currency/StripeFxQuoteServiceTest.java \
       src/test/java/com/yadony/api/payments/wallet/WalletTopupOrchestratorFxQuoteTest.java
```

- [ ] **Step 3: Vérifier la compilation et la suite complète**

```bash
./mvnw test 2>&1 | tail -40
```
Expected: `BUILD SUCCESS`, 0 échec.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(currency): supprime StripeFxQuoteService et ses tests (fx_quote abandonné)"
```

---

## FRONTEND — `dony_app` (worktree `.worktrees/dony-app-multicurrency`)

### Task 14: `ErrorCatalog` — mapping `currency-mismatch`

**Files:**
- Modify: `lib/core/error/error_catalog.dart` (ajouter une entrée dans la map, à côté de `'announcement-not-found'` ligne ~177)
- Test: `test/core/error/error_catalog_test.dart` (créer le cas si le fichier existe déjà, sinon ajouter un test minimal)

**Interfaces:**
- Produces: `ErrorCatalog.lookup(AppException(code: 'currency-mismatch', ...))` → `ErrorPresentation` avec titre/message dédiés.

- [ ] **Step 1: Écrire le test rouge**

```dart
test('currency-mismatch maps to dedicated message', () {
  final presentation = ErrorCatalog.lookup(
    AppException(code: 'currency-mismatch', message: 'ignored'),
  );
  expect(presentation.title, 'Devise différente');
  expect(presentation.severity, ErrorSeverity.warning);
});
```
Adapter le constructeur `AppException` exact (lire `lib/core/error/app_exception.dart` d'abord si la signature diffère).

- [ ] **Step 2: Vérifier l'échec**

```bash
flutter test test/core/error/error_catalog_test.dart
```

- [ ] **Step 3: Ajouter l'entrée**

```dart
    'currency-mismatch': ErrorPresentation(
      title: 'Devise différente',
      message:
          'Ce trajet n\'est plus disponible dans ta devise. Change de devise dans Réglages pour le voir.',
      severity: ErrorSeverity.warning,
      icon: Icons.currency_exchange_rounded,
    ),
```

- [ ] **Step 4: Vérifier le succès et commit**

```bash
flutter test test/core/error/error_catalog_test.dart
git add lib/core/error/error_catalog.dart test/core/error/error_catalog_test.dart
git commit -m "feat(currency): message dédié currency-mismatch dans ErrorCatalog"
```

---

### Task 15: `HiveService` — flag onboarding devise vu

**Files:**
- Modify: `lib/core/storage/hive_service.dart` (ajouter la clé à côté de `kCurrencyCode`, ligne ~48)

**Interfaces:**
- Produces: `HiveService.kCurrencyOnboardingSeen` (String constant).

- [ ] **Step 1: Ajouter la clé**

Juste après la ligne `static const String kCurrencyCode = 'currency_code'; // 'EUR' | 'XOF' | 'XAF'` :
```dart
  static const String kCurrencyOnboardingSeen = 'currency_onboarding_seen';
```

- [ ] **Step 2: Commit**

```bash
git add lib/core/storage/hive_service.dart
git commit -m "feat(currency): clé Hive flag onboarding devise"
```

---

### Task 16: Écran onboarding devise — `CurrencySelectionScreen` + route

**Files:**
- Create: `lib/features/auth/presentation/screens/currency_selection_screen.dart`
- Create: `test/features/auth/presentation/screens/currency_selection_screen_test.dart`
- Modify: `lib/app/router.dart` (ajouter la route, à côté de `/auth/referral-code` ligne ~273)
- Modify: `lib/features/auth/presentation/screens/analytics_consent_screen.dart:171` (`context.go('/auth/referral-code')` → `context.go('/auth/currency-selection')`)
- Modify: `lib/features/auth/presentation/post_signup_route.dart`

**Interfaces:**
- Consumes: `SupportedCurrency.values` (existant), `HiveService.kCurrencyOnboardingSeen` (Task 15), `PUT /users/me/business-preferences` via le repository déjà branché sur `BusinessPrefsBloc`.
- Produces: route `/auth/currency-selection`.

- [ ] **Step 1: Écrire le widget test rouge**

```dart
// test/features/auth/presentation/screens/currency_selection_screen_test.dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:dony/features/auth/presentation/screens/currency_selection_screen.dart';

void main() {
  testWidgets('tapping a currency and skip both navigate to referral-code',
      (tester) async {
    String? lastRoute;
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, __) => const CurrencySelectionScreen(),
      ),
      GoRoute(
        path: '/auth/referral-code',
        builder: (_, __) {
          lastRoute = '/auth/referral-code';
          return const SizedBox();
        },
      ),
    ]);

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();

    expect(find.text('Passer pour l\'instant'), findsOneWidget);
    await tester.tap(find.text('Passer pour l\'instant'));
    await tester.pumpAndSettle();

    expect(lastRoute, '/auth/referral-code');
  });
}
```

- [ ] **Step 2: Vérifier l'échec**

```bash
flutter test test/features/auth/presentation/screens/currency_selection_screen_test.dart
```
Expected: FAIL (fichier `currency_selection_screen.dart` inexistant)

- [ ] **Step 3: Créer l'écran**

```dart
import 'package:dony/core/currency/supported_currency.dart';
import 'package:dony/core/di/injection.dart';
import 'package:dony/core/design/design_system.dart';
import 'package:dony/core/storage/hive_service.dart';
import 'package:dony/features/settings/data/repositories/business_prefs_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:go_router/go_router.dart';

class CurrencySelectionScreen extends StatefulWidget {
  const CurrencySelectionScreen({super.key});

  @override
  State<CurrencySelectionScreen> createState() =>
      _CurrencySelectionScreenState();
}

class _CurrencySelectionScreenState extends State<CurrencySelectionScreen> {
  bool _saving = false;

  Future<void> _select(SupportedCurrency currency) async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      if (getIt.isRegistered<BusinessPrefsRepository>()) {
        final current = await getIt<BusinessPrefsRepository>().fetchPrefs();
        await getIt<BusinessPrefsRepository>().updatePrefs(
          current.copyWith(currencyCode: currency.code),
        );
      }
      if (getIt.isRegistered<HiveService>()) {
        getIt<HiveService>().userPrefs.put(HiveService.kCurrencyCode, currency.code);
      }
    } catch (_) {
      // Échec réseau non bloquant : la devise reste modifiable dans Réglages.
    }
    await _finish();
  }

  Future<void> _skip() => _finish();

  Future<void> _finish() async {
    if (getIt.isRegistered<HiveService>()) {
      await getIt<HiveService>().userPrefs.put(HiveService.kCurrencyOnboardingSeen, true);
    }
    if (mounted) context.go('/auth/referral-code');
  }

  @override
  Widget build(BuildContext context) {
    final h = DonyLayout.hPadding(context);
    return Scaffold(
      backgroundColor: kBackground,
      body: SafeArea(
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: h).copyWith(top: 24, bottom: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Dans quelle devise veux-tu utiliser Yadony ?',
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                ),
              ).animate().fadeIn(duration: 300.ms),
              const SizedBox(height: 8),
              Text(
                'Tu ne verras et ne pourras payer que les trajets dans cette devise. Modifiable plus tard dans Réglages.',
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 14,
                  color: kTextSecondary,
                ),
              ),
              const SizedBox(height: 24),
              Expanded(
                child: ListView.builder(
                  itemCount: SupportedCurrency.values.length,
                  itemBuilder: (context, index) {
                    final currency = SupportedCurrency.values[index];
                    return ListTile(
                      title: Text('${currency.displayName} (${currency.code})'),
                      trailing: Text(currency.symbol),
                      onTap: _saving ? null : () => _select(currency),
                    );
                  },
                ),
              ),
              SizedBox(
                width: double.infinity,
                child: TextButton(
                  onPressed: _saving ? null : _skip,
                  child: const Text('Passer pour l\'instant'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
```
Note : import `GoogleFonts`/`kBackground`/`kTextSecondary` déjà exposés par `design_system.dart` dans ce projet (cf. les autres écrans d'onboarding) — retirer l'import direct de `google_fonts` si `design_system.dart` le réexporte déjà (vérifier avec `rtk proxy grep -n "import 'package:google_fonts" lib/features/auth/presentation/screens/referral_code_screen.dart`, et aligner l'import sur ce fichier existant).

Vérifier le nom exact de la méthode de mise à jour des préférences sur `BusinessPrefsRepository` (`updatePrefs`, vu dans `BusinessPrefsBloc._putOrRollback`) et le nom du champ `copyWith` sur le DTO (`UserBusinessPrefsDto` — vérifier qu'il expose bien `copyWith`, sinon construire un nouveau DTO directement avec les mêmes champs que `current` sauf `currencyCode`).

- [ ] **Step 4: Ajouter la route**

Dans `lib/app/router.dart`, juste avant la route `/auth/referral-code` (ligne ~273) :
```dart
    GoRoute(
      path: '/auth/currency-selection',
      builder: (context, state) => const CurrencySelectionScreen(),
    ),
```
Ajouter l'import correspondant en haut du fichier, à côté de `import 'package:dony/features/auth/presentation/screens/referral_code_screen.dart';` (ligne ~166).

- [ ] **Step 5: Rediriger `analytics_consent_screen.dart` vers le nouvel écran**

```bash
rtk proxy grep -n "auth/referral-code" lib/features/auth/presentation/screens/analytics_consent_screen.dart
```
Remplacer `context.go('/auth/referral-code');` (ligne ~171) par `context.go('/auth/currency-selection');`.

- [ ] **Step 6: Mettre à jour `post_signup_route.dart`**

```dart
Future<String> resolvePostSignupRoute(
  AnalyticsService analytics,
  Box<dynamic> prefs,
) async {
  if (analytics.isConfigured && !analytics.hasAnswered) {
    await analytics.syncFromBackend();
  }
  if (analytics.isConfigured && !analytics.hasAnswered) {
    return '/auth/analytics-consent';
  }
  if (prefs.get(HiveService.kCurrencyOnboardingSeen, defaultValue: false) != true) {
    return '/auth/currency-selection';
  }
  return '/auth/referral-code';
}
```
Ajouter `import 'package:dony/core/storage/hive_service.dart';` en haut du fichier.

- [ ] **Step 7: Vérifier le succès**

```bash
flutter test test/features/auth/presentation/screens/currency_selection_screen_test.dart
flutter analyze
```
Expected: PASS, aucune erreur d'analyse.

- [ ] **Step 8: Commit**

```bash
git add lib/features/auth/presentation/screens/currency_selection_screen.dart \
        test/features/auth/presentation/screens/currency_selection_screen_test.dart \
        lib/app/router.dart \
        lib/features/auth/presentation/screens/analytics_consent_screen.dart \
        lib/features/auth/presentation/post_signup_route.dart
git commit -m "feat(currency): écran onboarding sélection devise, skippable"
```

---

### Task 17: Bandeau devise sur formulaire création trajet/colis

**Files:**
- Modify: écran de création de trajet — localiser avec `find lib/features/matching -iname "*create*screen*.dart" -o -iname "*publish*screen*.dart"`
- Modify: écran de création de demande colis — localiser avec `find lib/features/requests -iname "*form*screen*.dart"`

**Interfaces:**
- Consumes: `HiveService.kCurrencyCode` (existant), `SupportedCurrency.fromCode` (existant).

- [ ] **Step 1: Localiser les deux écrans**

```bash
find lib/features/matching -iname "*create*screen*.dart" -o -iname "*publish*screen*.dart"
find lib/features/requests -iname "*form*screen*.dart"
```

- [ ] **Step 2: Ajouter le bandeau informatif (widget test d'abord)**

Pour chaque écran, écrire un widget test vérifiant la présence du texte `"Publié en"` avec la devise courante, puis ajouter au début du corps du formulaire (avant le premier champ) :
```dart
Container(
  padding: const EdgeInsets.all(12),
  margin: const EdgeInsets.only(bottom: 16),
  decoration: BoxDecoration(
    color: kGreenLight,
    borderRadius: BorderRadius.circular(12),
  ),
  child: Row(
    children: [
      const Icon(Icons.info_outline_rounded, size: 18, color: kGreenPrimary),
      const SizedBox(width: 8),
      Expanded(
        child: Text(
          'Publié en ${_currentCurrency.displayName} (${_currentCurrency.code}) — '
          'les expéditeurs dans une autre devise ne verront pas cette annonce.',
          style: GoogleFonts.plusJakartaSans(fontSize: 13, color: kTextSecondary),
        ),
      ),
    ],
  ),
),
```
avec un getter local :
```dart
SupportedCurrency get _currentCurrency {
  final code = getIt.isRegistered<HiveService>()
      ? getIt<HiveService>().userPrefs.get(HiveService.kCurrencyCode, defaultValue: 'EUR') as String
      : 'EUR';
  return SupportedCurrency.fromCode(code) ?? SupportedCurrency.eur;
}
```

Ce texte ne doit **jamais** être envoyé au back — vérifier qu'aucun champ `currencyCode` n'est ajouté au DTO de requête envoyé par ces formulaires (le back déduit seul la devise, cf. Tasks 4 et 6).

- [ ] **Step 3: Lancer les tests, `flutter analyze`, commit**

```bash
flutter test test/features/matching/ test/features/requests/
flutter analyze
git add lib/features/matching/ lib/features/requests/ test/features/matching/ test/features/requests/
git commit -m "feat(currency): bandeau devise sur formulaires création trajet/colis"
```

---

### Task 18: Écran wallet — solde actif + soldes verrouillés

**Files:**
- Modify: écran de solde wallet — localiser avec `find lib/features/payments/wallet -iname "*balance*screen*.dart" -o -iname "*wallet*screen*.dart"`
- Modify: `lib/features/payments/wallet/bloc/wallet_bloc.dart` (adapter au retour multi-lignes de `GET /wallet/balance`, à vérifier côté back si l'endpoint retourne déjà toutes les devises ou doit être étendu)

**Interfaces:**
- Consumes: réponse backend `GET /wallet/balance` — **vérifier au Step 1** si `WalletController.getBalance` (Task 11 dépendance) doit être étendu pour retourner `List<{currency, balance}>` au lieu d'un seul solde ; si oui, traiter ce changement de contrat ici côté back ET front dans la même task (contrat API cohérent des deux côtés).

- [ ] **Step 1: Vérifier le contrat actuel de l'endpoint**

```bash
rtk proxy grep -n "getBalance\|/balance" src/main/java/com/yadony/api/payments/wallet/WalletController.java
```
(depuis le worktree back `.worktrees/dony-back-multicurrency`). Si l'endpoint retourne un seul solde scalaire, l'étendre pour retourner la liste de `WalletService.getAllBalances(userId)` (Task 11) + un indicateur de la devise "active" (= business-prefs courante), sérialisés en JSON `[{ "currency": "EUR", "balance": 12.50, "active": false }, { "currency": "CAD", "balance": 5.00, "active": true }]`.

- [ ] **Step 2: Étendre le DTO et le contrôleur back si nécessaire** (TDD : test MockMvc rouge → implémentation → vert, même déroulé que les tasks précédentes)

- [ ] **Step 3: Adapter `WalletBloc`/le modèle front pour parser la liste**

- [ ] **Step 4: Adapter l'écran** : afficher la ligne active en premier (montant + devise, style existant), puis les lignes verrouillées en dessous avec un badge "verrouillé" et un texte "reviens sur {devise} dans Réglages pour l'utiliser".

- [ ] **Step 5: Tests, `flutter analyze` + `./mvnw test` côté back si modifié, commit** (un commit par repo si les deux worktrees sont touchés)

---

### Task 19: Réglages — confirmation au changement de devise

**Files:**
- Modify: `lib/features/settings/presentation/screens/business_prefs_screen.dart` (`_showCurrencyPicker`, ligne ~158-185)

**Interfaces:**
- Consumes: `DonyDialog.show` (pattern déjà utilisé ailleurs dans le projet, cf. `error_presenter.dart` Step examiné en amont).

- [ ] **Step 1: Widget test rouge** vérifiant qu'un dialogue de confirmation apparaît avant l'envoi de `CurrencyChanged` lors du tap sur une devise différente de l'actuelle.

- [ ] **Step 2: Modifier `onTap` du `ListTile`** dans `_showCurrencyPicker` :

```dart
              onTap: () async {
                if (current == currency.code) {
                  context.pop();
                  return;
                }
                context.pop(); // ferme le picker avant le dialogue
                final confirmed = await DonyDialog.show(
                  context,
                  title: 'Changer de devise',
                  message: 'Tes trajets/colis en $current resteront visibles pour toi '
                      'mais plus pour les autres. Ton solde $current reste récupérable '
                      'en revenant sur cette devise plus tard.',
                  confirmLabel: 'Changer pour ${currency.code}',
                  cancelLabel: 'Annuler',
                );
                if (confirmed == true && context.mounted) {
                  context.read<BusinessPrefsBloc>().add(
                    CurrencyChanged(currency.code),
                  );
                }
              },
```

- [ ] **Step 3: Tests, `flutter analyze`, commit**

```bash
flutter test test/features/settings/
flutter analyze
git add lib/features/settings/presentation/screens/business_prefs_screen.dart \
        test/features/settings/
git commit -m "feat(currency): confirmation avant changement de devise dans Réglages"
```

---

## Self-Review (fait par l'auteur du plan)

**Couverture spec → tâches :**
- Migrations données ✅ Task 1
- `CurrencyMatchGuard` ✅ Task 2
- Création trajet (devise figée) ✅ Task 3-4
- Création colis (devise figée) ✅ Task 5-6
- Bid hérite devise + garde ✅ Task 7
- Négociation hérite devise + garde ✅ Task 8
- Paiement sans fx_quote, devise = bid ✅ Task 9
- Wallet multi-devise (entité + repo) ✅ Task 10
- Wallet multi-devise (service + call sites) ✅ Task 11
- Topup sans fx_quote ✅ Task 12
- Suppression StripeFxQuoteService ✅ Task 13
- Erreur `currency-mismatch` front ✅ Task 14
- Onboarding devise skippable ✅ Task 15-16
- Bandeau création trajet/colis ✅ Task 17
- Écran wallet multi-lignes ✅ Task 18
- Confirmation switch devise Réglages ✅ Task 19

**Hors scope confirmé** (cf. spec) : renommage colonnes `_eur`, affichage converti dans les listes de recherche, reporting admin multi-devise, collecte nom/prénom/date de naissance à l'onboarding.

**Points d'incertitude explicitement signalés dans le plan** (le nom exact de méthodes/champs n'a pas pu être vérifié à 100 % sans lire chaque fichier de test existant en entier) : Task 4 Step 1 (helpers de test), Task 9 Step 2 (helpers `PaymentServiceTest`), Task 12 Step 3 (mécanisme réel de crédit au webhook), Task 18 Step 1 (contrat actuel de l'endpoint balance). Chaque instruction associée dit explicitement quoi vérifier avant d'écrire le code, pas de detail vague.
