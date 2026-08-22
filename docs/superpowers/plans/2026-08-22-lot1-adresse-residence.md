# Lot 1 — Adresse de résidence · Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development` (recommandé) ou `superpowers:executing-plans` pour dérouler ce plan tâche par tâche. Les étapes sont en cases à cocher (`- [ ]`).

**But :** collecter l'adresse de résidence du voyageur une seule fois, la stocker, et poser le champ `onboarding_seen_at` qui servira au résolveur du lot 2.

**Architecture :** quatre colonnes nullables sur `users` (V230), un endpoint `PUT /auth/me/residence-address`, un écran Flutter piloté par un `Cubit`. Aucune progression n'est stockée : l'adresse *est* le fait qui valide l'étape 4.

**Pile :** Spring Boot 3.5 / Java 21 / Flyway / PostgreSQL · Flutter / flutter_bloc / Dio / GetIt

**Spec :** `docs/superpowers/specs/2026-08-22-onboarding-progressif-design.md`

## Contraintes globales

- **Deux dépôts, deux branches, deux PR.** `dony-back` → `feature/residence-address`, `dony_app` → `feature/residence-address`. Jamais de commit sur `main`. Jamais de ligne `Co-Authored-By`.
- **Worktree obligatoire** pour tout changement de code, dans les deux dépôts.
- **Correction de la spec :** la spec écrit `PUT /users/me/residence-address`. La convention réelle du projet est **`/auth/me/...`** (`AuthController` porte `@RequestMapping("/auth")`). Le plan utilise `PUT /auth/me/residence-address`.
- **Migration :** la dernière appliquée est `V229__wallet_refund_eligibility.sql`. La nôtre est **V230**. Ne jamais modifier une migration existante.
- **Colonnes nullables obligatoires.** Une colonne `NOT NULL` casse `V89MigrationTest`, dont l'`INSERT` ne connaît pas ces champs.
- **Erreurs backend :** `YadonyBusinessException` → RFC 7807 par `GlobalExceptionHandler`. Jamais de `Map` ni de `String` brut.
- **Flutter :** BLoC/Cubit uniquement, jamais `setState`. GoRouter uniquement, jamais `Navigator.push`. Services via GetIt, jamais instanciés dans un widget.
- **Design system :** `DonyPageScaffold`, `DonyTextField`, `DonyButton`. Jamais de `Color(0xFF…)`, jamais de `EdgeInsets.all(16)`, jamais de `GoogleFonts.*` direct — passer par `cs.*`, `DonyColors`, `DonySpacing`, `DonyRadius`, `Theme.of(context).textTheme`.
- **Analytics :** noms déclarés dans `AnalyticsEvents`, émis **dans le Cubit**, toujours `unawaited`, aucune PII (jamais l'adresse elle-même).
- **Couverture ≥ 90 %** dans les deux dépôts.
- **Jamais deux `./mvnw` ni deux commandes Flutter en parallèle** — cela fabrique de faux échecs.

---

# Partie A — `dony-back`

### Tâche 1 : migration V230 et champs d'entité

**Fichiers :**
- Créer : `src/main/resources/db/migration/V230__residence_address.sql`
- Modifier : `src/main/java/com/yadony/api/auth/UserEntity.java`
- Test : `src/test/java/com/yadony/api/auth/UserEntityResidenceTest.java`

**Interfaces :**
- Produit : `UserEntity.getResidenceStreet()/setResidenceStreet(String)`, `getResidenceLine2()/setResidenceLine2(String)`, `getResidencePostalCode()/setResidencePostalCode(String)`, `getOnboardingSeenAt()/setOnboardingSeenAt(Instant)`

- [ ] **Étape 1 : écrire la migration**

```sql
-- V230__residence_address.sql
-- Adresse de résidence du voyageur, collectée une seule fois à l'étape 4 de
-- l'onboarding et transmise à Stripe Connect. Distincte de pickup_addresses
-- (points de retrait de colis) : un point de retrait n'est pas un domicile légal.
--
-- Le pays n'est pas dupliqué : users.country, figé à l'étape 2, fait foi.
--
-- Toutes les colonnes sont NULLABLE : l'étape est passable, et une colonne
-- NOT NULL casserait V89MigrationTest dont l'INSERT ne les connaît pas.
ALTER TABLE users
    ADD COLUMN residence_street      VARCHAR(255),
    ADD COLUMN residence_line2       VARCHAR(100),
    ADD COLUMN residence_postal_code VARCHAR(20),
    ADD COLUMN onboarding_seen_at    TIMESTAMPTZ;

COMMENT ON COLUMN users.onboarding_seen_at IS
    'Posé quand l''utilisateur atteint l''accueil depuis le parcours d''onboarding, '
    'qu''il ait tout complété ou tout passé. NULL = le parcours s''impose encore.';
```

- [ ] **Étape 2 : ajouter les champs à `UserEntity`**

Après le champ `country` (`UserEntity.java:196`) :

```java
    @Column(name = "residence_street", length = 255)
    private String residenceStreet;

    @Column(name = "residence_line2", length = 100)
    private String residenceLine2;

    @Column(name = "residence_postal_code", length = 20)
    private String residencePostalCode;

    @Column(name = "onboarding_seen_at")
    private Instant onboardingSeenAt;
```

Et les accesseurs, à côté de `getCountry()/setCountry()` :

```java
    public String getResidenceStreet() { return residenceStreet; }
    public void setResidenceStreet(String residenceStreet) { this.residenceStreet = residenceStreet; }

    public String getResidenceLine2() { return residenceLine2; }
    public void setResidenceLine2(String residenceLine2) { this.residenceLine2 = residenceLine2; }

    public String getResidencePostalCode() { return residencePostalCode; }
    public void setResidencePostalCode(String residencePostalCode) { this.residencePostalCode = residencePostalCode; }

    public Instant getOnboardingSeenAt() { return onboardingSeenAt; }
    public void setOnboardingSeenAt(Instant onboardingSeenAt) { this.onboardingSeenAt = onboardingSeenAt; }
```

- [ ] **Étape 3 : écrire le test qui échoue**

```java
package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class UserEntityResidenceTest {

    @Test
    void residenceFieldsDefaultToNull() {
        UserEntity user = new UserEntity();
        assertThat(user.getResidenceStreet()).isNull();
        assertThat(user.getResidenceLine2()).isNull();
        assertThat(user.getResidencePostalCode()).isNull();
        assertThat(user.getOnboardingSeenAt()).isNull();
    }

    @Test
    void residenceFieldsRoundTrip() {
        UserEntity user = new UserEntity();
        Instant seen = Instant.parse("2026-08-22T10:00:00Z");
        user.setResidenceStreet("12 rue des Lilas");
        user.setResidenceLine2("Bat. B");
        user.setResidencePostalCode("75011");
        user.setOnboardingSeenAt(seen);

        assertThat(user.getResidenceStreet()).isEqualTo("12 rue des Lilas");
        assertThat(user.getResidenceLine2()).isEqualTo("Bat. B");
        assertThat(user.getResidencePostalCode()).isEqualTo("75011");
        assertThat(user.getOnboardingSeenAt()).isEqualTo(seen);
    }
}
```

- [ ] **Étape 4 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=UserEntityResidenceTest
```
Attendu : échec de compilation, `cannot find symbol: method getResidenceStreet()`.

- [ ] **Étape 5 : relancer, vérifier qu'il passe**

```bash
./mvnw test -Dtest=UserEntityResidenceTest
```
Attendu : `Tests run: 2, Failures: 0`.

- [ ] **Étape 6 : vérifier que la migration n'a rien cassé**

```bash
./mvnw test -Dtest=V89MigrationTest
```
Attendu : vert. Si rouge, une colonne a été déclarée `NOT NULL` — la rendre nullable.

- [ ] **Étape 7 : commit**

```bash
git add src/main/resources/db/migration/V230__residence_address.sql \
        src/main/java/com/yadony/api/auth/UserEntity.java \
        src/test/java/com/yadony/api/auth/UserEntityResidenceTest.java
git commit -m "feat(auth): stocke l'adresse de résidence et la date de fin d'onboarding"
```

---

### Tâche 2 : DTO et service

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/auth/dto/ResidenceAddressRequest.java`
- Modifier : `src/main/java/com/yadony/api/auth/AuthService.java` (à la suite de `updateAnalyticsConsent`, ligne ~282)
- Test : `src/test/java/com/yadony/api/auth/AuthServiceResidenceAddressTest.java`

**Interfaces :**
- Consomme : les accesseurs de la tâche 1.
- Produit : `AuthService.updateResidenceAddress(String firebaseUid, ResidenceAddressRequest request)` (void), `AuthService.markOnboardingSeen(String firebaseUid)` (void, idempotent).

- [ ] **Étape 1 : créer le DTO**

```java
package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adresse de résidence du voyageur, transmise ensuite à Stripe Connect.
 *
 * <p>Le pays n'y figure pas volontairement : il vient de {@code users.country},
 * figé à l'étape « pays » de l'onboarding, et Stripe verrouille le pays du
 * compte connecté. L'accepter ici permettrait de le contredire.
 *
 * @param street     requis — numéro et voie
 * @param line2      optionnel — appartement, étage, bâtiment
 * @param postalCode requis
 * @param city       requis — écrase {@code users.city}, même donnée
 */
public record ResidenceAddressRequest(
        @NotBlank @Size(max = 255) String street,
        @Size(max = 100) String line2,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 100) String city) {}
```

- [ ] **Étape 2 : écrire les tests qui échouent**

Harnais repris à l'identique de `AuthServiceTest` : `@ExtendWith(MockitoExtension.class)`, champs `@Mock`, `@InjectMocks`. **Ne créer aucune fabrique de test** — Mockito injecte le constructeur à onze paramètres tout seul.

```java
package com.yadony.api.auth;

import com.yadony.api.auth.dto.ResidenceAddressRequest;
import com.yadony.api.common.audit.AuditService;
import com.yadony.api.common.exception.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceResidenceAddressTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private AuthService service;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setFirebaseUid("uid-1");
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void storesAddressAndAudits() {
        service.updateResidenceAddress("uid-1",
                new ResidenceAddressRequest("12 rue des Lilas", "Bat. B", "75011", "Paris"));

        assertThat(user.getResidenceStreet()).isEqualTo("12 rue des Lilas");
        assertThat(user.getResidenceLine2()).isEqualTo("Bat. B");
        assertThat(user.getResidencePostalCode()).isEqualTo("75011");
        assertThat(user.getCity()).isEqualTo("Paris");
        verify(auditService).log(eq("USER"), eq(user.getId()),
                eq("RESIDENCE_ADDRESS_UPDATED"), eq(user.getId()), any());
    }

    @Test
    void auditPayloadCarriesNoAddress() {
        service.updateResidenceAddress("uid-1",
                new ResidenceAddressRequest("12 rue des Lilas", null, "75011", "Paris"));

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).log(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().toString())
                .doesNotContain("Lilas")
                .doesNotContain("75011");
    }

    @Test
    void rejectsUnknownUser() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateResidenceAddress("ghost",
                new ResidenceAddressRequest("x", null, "y", "z")))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Utilisateur introuvable");
    }

    @Test
    void marksOnboardingSeenOnce() {
        service.markOnboardingSeen("uid-1");
        var first = user.getOnboardingSeenAt();
        assertThat(first).isNotNull();

        service.markOnboardingSeen("uid-1");
        assertThat(user.getOnboardingSeenAt()).isEqualTo(first);
    }
}
```

- [ ] **Étape 3 : lancer, vérifier l'échec**

```bash
./mvnw test -Dtest=AuthServiceResidenceAddressTest
```
Attendu : `cannot find symbol: method updateResidenceAddress`.

- [ ] **Étape 4 : implémenter dans `AuthService`**

```java
    /**
     * Adresse de résidence. Le pays n'est pas modifiable ici : il vient de
     * l'étape « pays » de l'onboarding et Stripe verrouille celui du compte
     * connecté.
     *
     * <p>{@code city} écrase le champ profil existant : c'est la même donnée,
     * en garder deux versions les ferait diverger.
     */
    @Transactional
    public void updateResidenceAddress(String firebaseUid, ResidenceAddressRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setResidenceStreet(request.street());
        user.setResidenceLine2(request.line2());
        user.setResidencePostalCode(request.postalCode());
        user.setCity(request.city());
        userRepository.save(user);

        // Aucune PII dans le journal : on trace le fait, pas l'adresse.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("hasLine2", request.line2() != null && !request.line2().isBlank());
        auditService.log("USER", user.getId(), "RESIDENCE_ADDRESS_UPDATED", user.getId(), payload);
    }

    /**
     * Marque le parcours d'onboarding comme vu. Idempotent : une seconde
     * réponse ne réécrit pas la date, sinon un utilisateur qui rouvre le
     * récapitulatif repousserait indéfiniment son propre horodatage.
     */
    @Transactional
    public void markOnboardingSeen(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        if (user.getOnboardingSeenAt() != null) {
            return;
        }
        user.setOnboardingSeenAt(Instant.now());
        userRepository.save(user);
    }
```

- [ ] **Étape 5 : relancer, vérifier que ça passe**

```bash
./mvnw test -Dtest=AuthServiceResidenceAddressTest
```
Attendu : `Tests run: 4, Failures: 0`.

- [ ] **Étape 6 : commit**

```bash
git add src/main/java/com/yadony/api/auth/dto/ResidenceAddressRequest.java \
        src/main/java/com/yadony/api/auth/AuthService.java \
        src/test/java/com/yadony/api/auth/AuthServiceResidenceAddressTest.java
git commit -m "feat(auth): enregistre l'adresse de résidence sans PII dans l'audit"
```

---

### Tâche 3 : endpoints et exposition

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/auth/AuthController.java` (après `updateAnalyticsConsent`, ligne ~96)
- Modifier : `src/main/java/com/yadony/api/auth/dto/UserResponse.java`
- Modifier : `src/main/java/com/yadony/api/auth/AuthService.java:615` (unique site de construction de `UserResponse`)
- Test : `src/test/java/com/yadony/api/auth/AuthControllerResidenceAddressIT.java`

**Interfaces :**
- Consomme : `AuthService.updateResidenceAddress`, `AuthService.markOnboardingSeen`.
- Produit : `PUT /auth/me/residence-address` (204), `PUT /auth/me/onboarding-seen` (204), et quatre champs de plus sur `UserResponse` : `residenceStreet`, `residenceLine2`, `residencePostalCode`, `onboardingSeenAt` (`String`, ISO-8601 ou `null`).

- [ ] **Étape 1 : écrire le test d'intégration qui échoue**

Harnais repris à l'identique de `AnalyticsConsentControllerTest`, le voisin le plus proche (même contrôleur, même forme d'endpoint) :

```java
package com.yadony.api.auth;

import com.yadony.api.auth.dto.ResidenceAddressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthControllerResidenceAddressIT {

    private static final String FIREBASE_UID = "uid-residence-test";

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void PUT_residenceAddress_rueVide_retourne400() throws Exception {
        mvc.perform(put("/auth/me/residence-address")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void PUT_residenceAddress_valide_retourne204() throws Exception {
        mvc.perform(put("/auth/me/residence-address")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"12 rue des Lilas","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(status().isNoContent());

        verify(authService).updateResidenceAddress(eq(FIREBASE_UID), any(ResidenceAddressRequest.class));
    }

    @Test
    void PUT_onboardingSeen_retourne204() throws Exception {
        mvc.perform(put("/auth/me/onboarding-seen")
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(authService).markOnboardingSeen(FIREBASE_UID);
    }
}
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
./mvnw test -Dtest=AuthControllerResidenceAddressIT
```
Attendu : `404` au lieu de `204` — la route n'existe pas.

- [ ] **Étape 3 : ajouter les endpoints**

```java
    @PutMapping("/me/residence-address")
    public ResponseEntity<Void> updateResidenceAddress(
            @Valid @RequestBody com.yadony.api.auth.dto.ResidenceAddressRequest request) {
        authService.updateResidenceAddress(requireFirebaseUid(), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/onboarding-seen")
    public ResponseEntity<Void> markOnboardingSeen() {
        authService.markOnboardingSeen(requireFirebaseUid());
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Étape 4 : exposer les champs sur `UserResponse`**

Ajouter à la fin du record, après `admin` :

```java
    String residenceStreet,
    String residenceLine2,
    String residencePostalCode,
    String onboardingSeenAt
```

Puis compléter l'unique site de construction, `AuthService.java:615` :

```java
                user.getResidenceStreet(),
                user.getResidenceLine2(),
                user.getResidencePostalCode(),
                user.getOnboardingSeenAt() == null ? null : user.getOnboardingSeenAt().toString()
```

- [ ] **Étape 5 : relancer, vérifier que ça passe**

```bash
./mvnw test -Dtest=AuthControllerResidenceAddressIT
```
Attendu : `Tests run: 2, Failures: 0`.

- [ ] **Étape 6 : suite complète**

```bash
./mvnw test > /tmp/back-full.log 2>&1; echo "EXIT=$?"; tail -20 /tmp/back-full.log
```
Attendu : `EXIT=0`. Lire le code de sortie, **jamais** juger sur un `| tail` seul — un tube masque le code de sortie.

- [ ] **Étape 7 : commit et PR**

```bash
git add -A
git commit -m "feat(auth): expose l'adresse de résidence et l'état d'onboarding"
git push -u origin feature/residence-address
gh pr create --title "feat(auth): adresse de résidence (lot 1 onboarding)" --body "Voir docs/superpowers/specs/2026-08-22-onboarding-progressif-design.md"
```

---

# Partie B — `dony_app`

### Tâche 4 : modèle et couche données

**Fichiers :**
- Modifier : `lib/features/auth/data/models/user_model.dart`
- Modifier : `lib/features/auth/data/datasources/auth_remote_datasource.dart`
- Modifier : `lib/features/auth/data/repositories/auth_repository.dart`
- Test : `test/features/auth/data/models/user_model_residence_test.dart`

**Interfaces :**
- Produit : `UserModel.residenceStreet`, `.residenceLine2`, `.residencePostalCode`, `.onboardingSeenAt` (`DateTime?`) ; `AuthRepository.updateResidenceAddress({required String street, String? line2, required String postalCode, required String city})` → `Future<void>` ; `AuthRepository.markOnboardingSeen()` → `Future<void>`.

- [ ] **Étape 1 : écrire le test qui échoue**

```dart
import 'package:dony/features/auth/data/models/user_model.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('UserModel — adresse de résidence', () {
    test('parse les champs quand ils sont présents', () {
      final u = UserModel.fromJson({
        'id': 'u1',
        'residenceStreet': '12 rue des Lilas',
        'residenceLine2': 'Bat. B',
        'residencePostalCode': '75011',
        'onboardingSeenAt': '2026-08-22T10:00:00Z',
      });

      expect(u.residenceStreet, '12 rue des Lilas');
      expect(u.residenceLine2, 'Bat. B');
      expect(u.residencePostalCode, '75011');
      expect(u.onboardingSeenAt, DateTime.utc(2026, 8, 22, 10));
    });

    test('tolère leur absence — un backend antérieur ne doit rien casser', () {
      final u = UserModel.fromJson({'id': 'u1'});

      expect(u.residenceStreet, isNull);
      expect(u.residenceLine2, isNull);
      expect(u.residencePostalCode, isNull);
      expect(u.onboardingSeenAt, isNull);
    });
  });
}
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/data/models/user_model_residence_test.dart
```
Attendu : `The getter 'residenceStreet' isn't defined`.

- [ ] **Étape 3 : ajouter les champs au modèle**

Dans `UserModel`, à la suite de `city` :

```dart
  final String? residenceStreet;
  final String? residenceLine2;
  final String? residencePostalCode;

  /// Date à laquelle l'utilisateur a atteint l'accueil depuis le parcours
  /// d'onboarding, qu'il l'ait terminé ou passé. `null` = le parcours
  /// s'impose encore.
  final DateTime? onboardingSeenAt;
```

Au constructeur : `this.residenceStreet, this.residenceLine2, this.residencePostalCode, this.onboardingSeenAt,`

Dans `fromJson` :

```dart
      residenceStreet: json['residenceStreet'] as String?,
      residenceLine2: json['residenceLine2'] as String?,
      residencePostalCode: json['residencePostalCode'] as String?,
      onboardingSeenAt: json['onboardingSeenAt'] == null
          ? null
          : DateTime.parse(json['onboardingSeenAt'] as String),
```

Compléter aussi `toJson` et `copyWith` s'ils existent dans ce modèle, en suivant exactement la forme des champs voisins.

- [ ] **Étape 4 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/data/models/user_model_residence_test.dart
```
Attendu : `All tests passed!`

- [ ] **Étape 5 : ajouter les appels réseau**

Dans `auth_remote_datasource.dart` :

```dart
  Future<void> updateResidenceAddress({
    required String street,
    String? line2,
    required String postalCode,
    required String city,
  }) async {
    await _apiClient.dio.put<void>(
      '/auth/me/residence-address',
      data: {
        'street': street,
        'line2': ?line2,
        'postalCode': postalCode,
        'city': city,
      },
    );
  }

  Future<void> markOnboardingSeen() async {
    await _apiClient.dio.put<void>('/auth/me/onboarding-seen');
  }
```

Dans `auth_repository.dart`, la délégation :

```dart
  Future<void> updateResidenceAddress({
    required String street,
    String? line2,
    required String postalCode,
    required String city,
  }) => _datasource.updateResidenceAddress(
    street: street,
    line2: line2,
    postalCode: postalCode,
    city: city,
  );

  Future<void> markOnboardingSeen() => _datasource.markOnboardingSeen();
```

- [ ] **Étape 6 : commit**

```bash
git add lib/features/auth/data test/features/auth/data
git commit -m "feat(auth): lit et enregistre l'adresse de résidence"
```

---

### Tâche 5 : `ResidenceAddressCubit`

**Fichiers :**
- Créer : `lib/features/auth/bloc/residence_address_cubit.dart`
- Modifier : `lib/core/services/analytics_events.dart`
- Modifier : `lib/core/di/injection.dart` (à la suite du bloc `CountryOnboardingCubit`, ligne ~290)
- Test : `test/features/auth/bloc/residence_address_cubit_test.dart`

**Interfaces :**
- Consomme : `AuthRepository.updateResidenceAddress`, `AuthRepository.markOnboardingSeen`.
- Produit : `ResidenceAddressCubit(AuthRepository, AnalyticsService)` avec `submit({required String street, String? line2, required String postalCode, required String city})` et `skip()`. États : `ResidenceAddressInitial`, `ResidenceAddressSaving`, `ResidenceAddressSuccess`, `ResidenceAddressError(String message)`.

- [ ] **Étape 1 : déclarer les events analytics**

À la fin de `AnalyticsEvents`, avant l'accolade fermante :

```dart
  // Onboarding progressif — lot 1
  static const residenceAddressSaved = 'residence_address_saved';
  static const onboardingStepSkipped = 'onboarding_step_skipped';
```

- [ ] **Étape 2 : écrire le test qui échoue**

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:dony/core/services/analytics_service.dart';
import 'package:dony/features/auth/bloc/residence_address_cubit.dart';
import 'package:dony/features/auth/data/repositories/auth_repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class _MockAuthRepository extends Mock implements AuthRepository {}
class _MockAnalytics extends Mock implements AnalyticsService {}

void main() {
  late _MockAuthRepository repo;
  late _MockAnalytics analytics;

  setUp(() {
    repo = _MockAuthRepository();
    analytics = _MockAnalytics();
    when(() => analytics.logEvent(any(), properties: any(named: 'properties')))
        .thenAnswer((_) async {});
  });

  blocTest<ResidenceAddressCubit, ResidenceAddressState>(
    'enregistre puis émet Success',
    build: () {
      when(() => repo.updateResidenceAddress(
            street: any(named: 'street'),
            line2: any(named: 'line2'),
            postalCode: any(named: 'postalCode'),
            city: any(named: 'city'),
          )).thenAnswer((_) async {});
      return ResidenceAddressCubit(repo, analytics);
    },
    act: (c) => c.submit(
      street: '12 rue des Lilas', line2: null, postalCode: '75011', city: 'Paris',
    ),
    expect: () => [
      isA<ResidenceAddressSaving>(),
      isA<ResidenceAddressSuccess>(),
    ],
  );

  blocTest<ResidenceAddressCubit, ResidenceAddressState>(
    'émet Error avec un message utilisable quand le réseau échoue',
    build: () {
      when(() => repo.updateResidenceAddress(
            street: any(named: 'street'),
            line2: any(named: 'line2'),
            postalCode: any(named: 'postalCode'),
            city: any(named: 'city'),
          )).thenThrow(Exception('boom'));
      return ResidenceAddressCubit(repo, analytics);
    },
    act: (c) => c.submit(
      street: 'x', line2: null, postalCode: 'y', city: 'z',
    ),
    expect: () => [
      isA<ResidenceAddressSaving>(),
      isA<ResidenceAddressError>(),
    ],
  );

  blocTest<ResidenceAddressCubit, ResidenceAddressState>(
    'skip marque l\'onboarding vu et n\'échoue jamais',
    build: () {
      when(() => repo.markOnboardingSeen()).thenThrow(Exception('hors ligne'));
      return ResidenceAddressCubit(repo, analytics);
    },
    act: (c) => c.skip(),
    expect: () => [
      isA<ResidenceAddressSaving>(),
      isA<ResidenceAddressSuccess>(),
    ],
  );

  test('aucune PII dans les properties analytics', () async {
    when(() => repo.updateResidenceAddress(
          street: any(named: 'street'),
          line2: any(named: 'line2'),
          postalCode: any(named: 'postalCode'),
          city: any(named: 'city'),
        )).thenAnswer((_) async {});

    await ResidenceAddressCubit(repo, analytics).submit(
      street: '12 rue des Lilas', line2: null, postalCode: '75011', city: 'Paris',
    );

    final captured = verify(() => analytics.logEvent(
          captureAny(), properties: captureAny(named: 'properties'),
        )).captured;
    expect(captured.toString(), isNot(contains('Lilas')));
    expect(captured.toString(), isNot(contains('75011')));
    expect(captured.toString(), isNot(contains('Paris')));
  });
}
```

- [ ] **Étape 3 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/bloc/residence_address_cubit_test.dart
```
Attendu : `Target of URI doesn't exist: residence_address_cubit.dart`.

- [ ] **Étape 4 : implémenter le Cubit**

```dart
import 'dart:async';

import 'package:dony/core/services/analytics_events.dart';
import 'package:dony/core/services/analytics_service.dart';
import 'package:dony/features/auth/data/repositories/auth_repository.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

sealed class ResidenceAddressState extends Equatable {
  const ResidenceAddressState();
  @override
  List<Object?> get props => const [];
}

class ResidenceAddressInitial extends ResidenceAddressState {
  const ResidenceAddressInitial();
}

class ResidenceAddressSaving extends ResidenceAddressState {
  const ResidenceAddressSaving();
}

class ResidenceAddressSuccess extends ResidenceAddressState {
  const ResidenceAddressSuccess();
}

class ResidenceAddressError extends ResidenceAddressState {
  const ResidenceAddressError(this.message);
  final String message;
  @override
  List<Object?> get props => [message];
}

/// Étape « adresse » du parcours d'onboarding.
///
/// L'adresse n'est jamais reprise de la pièce d'identité : celle du document
/// peut être périmée, alors que Stripe Connect demande la résidence actuelle.
/// C'est pourquoi on la collecte nous-mêmes, une seule fois.
class ResidenceAddressCubit extends Cubit<ResidenceAddressState> {
  ResidenceAddressCubit(this._repository, this._analytics)
    : super(const ResidenceAddressInitial());

  final AuthRepository _repository;
  final AnalyticsService _analytics;

  Future<void> submit({
    required String street,
    String? line2,
    required String postalCode,
    required String city,
  }) async {
    if (state is ResidenceAddressSaving) return;
    emit(const ResidenceAddressSaving());
    try {
      await _repository.updateResidenceAddress(
        street: street,
        line2: line2,
        postalCode: postalCode,
        city: city,
      );
      // Aucune PII : ni rue, ni code postal, ni ville.
      unawaited(_analytics.logEvent(
        AnalyticsEvents.residenceAddressSaved,
        properties: {'has_line2': line2 != null && line2.isNotEmpty},
      ));
      emit(const ResidenceAddressSuccess());
    } catch (_) {
      emit(const ResidenceAddressError(
        'Impossible d\'enregistrer cette adresse. Réessayez.',
      ));
    }
  }

  /// Passer l'étape ne doit jamais échouer : l'utilisateur n'a rien à
  /// enregistrer ici, et un problème réseau ne peut pas le retenir.
  Future<void> skip() async {
    if (state is ResidenceAddressSaving) return;
    emit(const ResidenceAddressSaving());
    try {
      await _repository.markOnboardingSeen();
    } catch (_) {
      // Ignoré volontairement : la date sera posée à la prochaine occasion.
    }
    unawaited(_analytics.logEvent(
      AnalyticsEvents.onboardingStepSkipped,
      properties: {'step': 'address'},
    ));
    emit(const ResidenceAddressSuccess());
  }
}
```

- [ ] **Étape 5 : enregistrer dans GetIt**

Dans `injection.dart`, après le bloc `CountryOnboardingCubit` :

```dart
  getIt.registerFactory<ResidenceAddressCubit>(
    () => ResidenceAddressCubit(
      getIt<AuthRepository>(),
      getIt<AnalyticsService>(),
    ),
  );
```

- [ ] **Étape 6 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/bloc/residence_address_cubit_test.dart
```
Attendu : `All tests passed!`

- [ ] **Étape 7 : commit**

```bash
git add lib/features/auth/bloc lib/core/services/analytics_events.dart \
        lib/core/di/injection.dart test/features/auth/bloc
git commit -m "feat(auth): cubit de l'étape adresse, sans PII dans le tracking"
```

---

### Tâche 6 : écran, route, et vérification responsive

**Fichiers :**
- Créer : `lib/features/auth/presentation/screens/residence_address_screen.dart`
- Modifier : `lib/app/router.dart` (après la route `/auth/country-selection`, ligne ~342)
- Test : `test/features/auth/presentation/residence_address_screen_test.dart`
- Test : `test/a11y/large_text_smoke_test.dart` (ajouter un cas au groupe « Taille de texte à 200 % », ligne ~803)

**Interfaces :**
- Consomme : `ResidenceAddressCubit` (tâche 5), `UserModel.country` pour le pays verrouillé.
- Produit : route `/auth/residence-address`.

- [ ] **Étape 1 : écrire les tests qui échouent**

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:dony/core/design/widgets/dony_button.dart';
import 'package:dony/features/auth/bloc/residence_address_cubit.dart';
import 'package:dony/features/auth/presentation/screens/residence_address_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

class _MockCubit extends MockCubit<ResidenceAddressState>
    implements ResidenceAddressCubit {}

Widget _wrap(ResidenceAddressCubit cubit) {
  return MaterialApp.router(
    routerConfig: GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => BlocProvider<ResidenceAddressCubit>.value(
            value: cubit,
            child: const ResidenceAddressScreen(),
          ),
        ),
        GoRoute(path: '/home', builder: (_, _) => const Scaffold(body: Text('Accueil'))),
      ],
    ),
  );
}

void main() {
  late _MockCubit cubit;

  setUp(() {
    cubit = _MockCubit();
    when(() => cubit.state).thenReturn(const ResidenceAddressInitial());
    whenListen(cubit, const Stream<ResidenceAddressState>.empty(),
        initialState: const ResidenceAddressInitial());
  });

  testWidgets('le bouton Continuer est désactivé tant que le formulaire est vide',
      (tester) async {
    await tester.pumpWidget(_wrap(cubit));
    await tester.pump(const Duration(milliseconds: 400));

    final btn = tester.widget<DonyButton>(
      find.widgetWithText(DonyButton, 'Continuer'),
    );
    expect(btn.onPressed, isNull);
  });

  testWidgets('un formulaire rempli active le bouton et appelle submit',
      (tester) async {
    when(() => cubit.submit(
          street: any(named: 'street'),
          line2: any(named: 'line2'),
          postalCode: any(named: 'postalCode'),
          city: any(named: 'city'),
        )).thenAnswer((_) async {});

    await tester.pumpWidget(_wrap(cubit));
    await tester.pump(const Duration(milliseconds: 400));

    await tester.enterText(find.byKey(const Key('residence-street')), '12 rue des Lilas');
    await tester.enterText(find.byKey(const Key('residence-postal')), '75011');
    await tester.enterText(find.byKey(const Key('residence-city')), 'Paris');
    await tester.pump();

    await tester.tap(find.widgetWithText(DonyButton, 'Continuer'));
    await tester.pump();

    verify(() => cubit.submit(
          street: '12 rue des Lilas',
          line2: null,
          postalCode: '75011',
          city: 'Paris',
        )).called(1);
  });

  testWidgets('« Passer pour l\'instant » appelle skip', (tester) async {
    when(() => cubit.skip()).thenAnswer((_) async {});

    await tester.pumpWidget(_wrap(cubit));
    await tester.pump(const Duration(milliseconds: 400));

    await tester.tap(find.text('Passer pour l\'instant'));
    await tester.pump();

    verify(() => cubit.skip()).called(1);
  });
}
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/presentation/residence_address_screen_test.dart
```
Attendu : `Target of URI doesn't exist: residence_address_screen.dart`.

- [ ] **Étape 3 : écrire l'écran**

Contraintes non négociables, à respecter à la lettre :
- coque `DonyPageScaffold`, jamais `Scaffold` + `DonyAppBar` recopiés ;
- champs `DonyTextField` avec les clés `residence-street`, `residence-line2`, `residence-postal`, `residence-city` ;
- pays affiché **verrouillé** (`enabled: false`), valeur reprise de `UserModel.country` ;
- `DonyButton(label: 'Continuer', onPressed: ...)` — `onPressed: null` tant que rue, code postal et ville ne sont pas tous renseignés, et pendant `ResidenceAddressSaving` (`isLoading: true`) ;
- un `TextButton` « Passer pour l'instant » sous le bouton principal ;
- **`line2` vide doit être envoyé en `null`, jamais en chaîne vide** : `line2: c.text.trim().isEmpty ? null : c.text.trim()`. Le test de l'étape 1 vérifie `line2: null`, et le backend distingue les deux dans son `hasLine2` d'audit ;
- les trois champs requis sont `trim()`és avant envoi ;
- validité du formulaire suivie par un `ValueNotifier<bool>` alimenté depuis les `onChanged`, **jamais** `setState` ;
- `BlocListener` : sur `ResidenceAddressSuccess` → `context.go('/auth/referral-code')` ; sur `ResidenceAddressError` → `SnackBar` avec `state.message` ;
- espacements par `DonySpacing.*`, rayons par `DonyRadius.*`, couleurs par `cs.*` — **aucune** valeur en dur ;
- `Semantics` sur le champ pays verrouillé pour expliquer pourquoi il ne s'édite pas.

- [ ] **Étape 4 : déclarer la route**

Dans `router.dart`, après la route `/auth/country-selection` :

```dart
    GoRoute(
      path: '/auth/residence-address',
      builder: (context, state) => BlocProvider(
        create: (_) => getIt<ResidenceAddressCubit>(),
        child: const ResidenceAddressScreen(),
      ),
    ),
```

Ajouter aussi `'/auth/residence-address'` à la liste des routes publiques d'onboarding, à côté de `'/auth/country-selection'` (`router.dart:212`).

- [ ] **Étape 5 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/presentation/residence_address_screen_test.dart
```
Attendu : `All tests passed!` — les trois tests tapent réellement les boutons.

- [ ] **Étape 6 : ajouter le cas 200 % dans la suite d'accessibilité**

Dans `test/a11y/large_text_smoke_test.dart`, groupe « Taille de texte à 200 % » :

```dart
    testWidgets('adresse de résidence', (tester) async {
      final cubit = _ResidenceMockCubit();
      when(() => cubit.state).thenReturn(const ResidenceAddressInitial());
      whenListen(cubit, const Stream<ResidenceAddressState>.empty(),
          initialState: const ResidenceAddressInitial());

      await pumpAt200(tester, _wrapResidenceAddress(cubit));
      expect(tester.takeException(), isNull);
    });
```

`pumpAt200` simule 1080×2400 en densité 3 — soit **360×800 logiques à 200 % de texte**, la classe du téléphone de test (720×1640). Tout débordement lève une exception que `takeException()` capture.

Déclarer `_ResidenceMockCubit` et `_wrapResidenceAddress` en haut du fichier, en suivant exactement la forme des harnais déjà présents.

- [ ] **Étape 7 : lancer la suite d'accessibilité**

```bash
flutter test test/a11y/
```
Attendu : `All tests passed!`

- [ ] **Étape 8 : analyse, format, suite complète**

```bash
dart format lib/ test/
flutter analyze > /tmp/analyze.log 2>&1; echo "ANALYZE=$?"; tail -3 /tmp/analyze.log
flutter test > /tmp/front-full.log 2>&1; echo "TEST=$?"; tail -6 /tmp/front-full.log
```
Attendu : `ANALYZE=0` et `TEST=0`.

**Ne jamais conclure sur les tests ciblés.** Faire lire un bloc fourni à l'échelle de l'app par un widget existant fait tomber en `ProviderNotFoundException` tout harnais qui montait ce widget sans ce provider — piège rencontré **quatre fois** sur le lot précédent, dont une fois par une atteinte indirecte qu'aucun `grep` ne trouvait. Seule la suite complète le révèle.

- [ ] **Étape 9 : vérification sur le téléphone connecté**

```bash
flutter run --dart-define-from-file=env.dev.json -d <device-id>
```

Parcourir l'écran et vérifier de visu :
- le bouton « Continuer » s'active bien quand les trois champs requis sont remplis ;
- « Passer pour l'instant » mène à l'écran suivant ;
- rien ne déborde en thème clair **et** sombre ;
- rien ne déborde à 200 % de taille de texte (Réglages Android › Affichage › Taille du texte).

- [ ] **Étape 10 : commit et PR**

```bash
git add -A
git commit -m "feat(auth): écran d'adresse de résidence (lot 1 onboarding)"
git push -u origin feature/residence-address
gh pr create --title "feat(auth): écran adresse de résidence (lot 1 onboarding)" --body "Dépend de yadony-back#<n>. Voir la spec 2026-08-22-onboarding-progressif-design.md"
```

> `gh pr merge` est bloqué par le classifieur : demander à l'utilisateur de lancer la fusion lui-même.

---

## Ordre de fusion

`dony-back` **avant** `dony_app`. Sans l'endpoint, l'écran ne peut rien enregistrer. Le modèle Flutter tolère l'absence des champs, donc un front en avance ne casse rien — il ne sert simplement à rien.

## Hors périmètre du lot 1

Le résolveur `nextStep`, la jauge, le récapitulatif, la carte de reprise et le préremplissage Connect. `onboarding_seen_at` est **posé** ici mais n'est **lu** qu'au lot 2 — c'est voulu : le champ doit exister avant que le résolveur s'en serve.
