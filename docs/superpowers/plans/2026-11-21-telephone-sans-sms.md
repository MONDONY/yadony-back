# Numéro de téléphone sans SMS (pays bloqués) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un utilisateur dont l'indicatif est dans `SMS_BLOCKED_COUNTRY_CODES` enregistre son numéro sans SMS (« déclaré »), un admin peut le valider (« Vérifié par Yadony »), et ce numéro sert à joindre et retrouver la personne, jamais à se connecter ni à toucher à l'argent.

**Architecture:** Le numéro déclaré vit dans un claim Firebase `declared_phone` (jamais dans le champ officiel `phoneNumber`, que tout le système lit comme « vérifié par SMS »). La base ne garde que son empreinte HMAC (pour la recherche) et son niveau. Trois dépôts : back (`dony-back`), app (`dony_app`), admin (`dony-admin`), en PR jumelles : back d'abord.

**Tech Stack:** Spring Boot 3.4 / Java 21, Flyway, Firebase Admin SDK, Caffeine ; Flutter BLoC + GoRouter ; Next.js (admin).

**Spec:** `docs/superpowers/specs/2026-11-21-telephone-sans-sms-design.md`

## Global Constraints

- Branche dédiée par dépôt (`feature/telephone-declare`), jamais sur `main`. Aucun `Co-Authored-By`. Travail dans un worktree : `dony-back/.claude/worktrees/<nom>`, `dony_app-worktrees/<nom>`. Partir de `origin/main` (les copies locales sont périmées).
- Prochaine migration Flyway : **V391** (la dernière sur `origin/main` est V390). Jamais d'édition d'une migration existante.
- Erreurs : `YadonyBusinessException.localized(HttpStatus, code, "problem.<clé>")` (RFC 7807), clés ajoutées FR et EN dans les messages.
- `audit_log` : jamais le numéro dans le payload.
- Numéros au format E.164. Indicatif dans `SMS_BLOCKED_COUNTRY_CODES` = seule condition pour déclarer.
- `phoneVerified` et `Contact.phoneNumber()` gardent leur sens « vérifié par SMS » : on n'y écrit jamais un numéro déclaré.
- Niveaux : `DECLARED` (badge « Non vérifié »), `YADONY_VERIFIED` (badge « Vérifié par Yadony »). Pas de tiret cadratin dans les textes d'interface.
- Tests : tout passe, couverture ≥ 90 % sur le code ajouté. Flutter : pas de `setState`, pas de `Navigator.push`, `DonyButton` de bottom sheet dans `stickyBottom`, libellés de bouton sur une ligne (FR et EN, 360 dp).
- Fiches assistant FR et EN (même `id`) mises à jour dans la PR back.
- Fusion : PR back d'abord, puis app/admin (qui citent le numéro de la PR back et tolèrent son absence). Aucun déploiement prod.

## Review Focus

- Deux comptes déclarent le même numéro : recherche ambiguë, jamais de rattachement automatique au hasard.
- Utilisateur modifie son numéro après validation admin : retour à `DECLARED`.
- Compte qui porte déjà un numéro vérifié par SMS appelle `declare` : 409 `phone-already-set`.
- Indicatif hors liste bloquée appelle `declare` : 409, sinon contournement du SMS.
- `setCustomUserClaims` remplace tous les claims : les claims existants doivent être conservés.
- Ancien client / ancien back : champs absents ou inconnus ignorés des deux côtés.
- Admin sans l'autorité : 403 sur la validation.
- Le numéro déclaré attaché plus tard par SMS : le déclaré disparaît.

---

### Task 1: Empreinte et colonnes en base

**Files:**
- Create: `src/main/resources/db/migration/V391__declared_phone.sql`
- Create: `src/main/java/com/yadony/api/auth/DeclaredPhoneHasher.java`
- Modify: `src/main/java/com/yadony/api/auth/UserEntity.java`, `src/main/java/com/yadony/api/auth/UserRepository.java`, `.env.example`, `.env.prod.template`, `.env.staging.template`, `src/main/resources/application.yml`
- Test: `src/test/java/com/yadony/api/auth/DeclaredPhoneHasherTest.java`

**Interfaces:**
- Produces: `DeclaredPhoneHasher#hash(String e164): String` (HMAC-SHA256 hex, 64 car.) ; `enum DeclaredPhoneLevel { DECLARED, YADONY_VERIFIED }` (package `auth`) ; sur `UserEntity` : `declaredPhoneHash: String`, `declaredPhoneLevel: DeclaredPhoneLevel`, `declaredPhoneVerifiedAt: LocalDateTime`, `declaredPhoneVerifiedBy: UUID` (getters/setters) ; `UserRepository#findAllByDeclaredPhoneHash(String): List<UserEntity>`.

- [ ] **Step 1: Test** `hash_isDeterministicAndKeyed`: même numéro → même hash ; clé différente → hash différent ; sortie 64 hex ; jamais le numéro en clair.
- [ ] **Step 2: Lancer** `./mvnw test -Dtest=DeclaredPhoneHasherTest` : échec attendu (classe absente).
- [ ] **Step 3:** Migration V391 : 4 colonnes nullables sur `users` + index sur `declared_phone_hash`. Hasher lit `yadony.declared-phone.hash-key` (env `DECLARED_PHONE_HASH_KEY`, obligatoire en prod, valeur de test en dev/test). Ajouter la variable aux gabarits d'environnement. Entité et finder du repository.
- [ ] **Step 4: Lancer** le test, puis `./mvnw test` complet : PASS.
- [ ] **Step 5: Commit** `feat(auth): colonnes et empreinte du numéro déclaré`.

### Task 2: Lecture et écriture du claim Firebase

**Files:**
- Modify: `src/main/java/com/yadony/api/auth/FirebaseContactService.java`
- Test: `src/test/java/com/yadony/api/auth/FirebaseContactServiceDeclaredPhoneTest.java`

**Interfaces:**
- Produces: `record Contact(String phoneNumber, String email, String declaredPhone)` avec constructeur à deux arguments conservé (déclaré = null) et `Contact.EMPTY` ; `void setDeclaredPhone(String firebaseUid, String e164OrNull)` (fusionne les claims existants, met le cache à jour, ne révoque pas la session) ; `getContact` remplit `declaredPhone` depuis le claim `declared_phone`.

- [ ] **Step 1: Tests** : `getContact_readsDeclaredClaim_andLeavesPhoneNumberNull` ; `setDeclaredPhone_keepsOtherClaims` ; `setDeclaredPhone_null_removesOnlyThatClaim` ; `setDeclaredPhone_refreshesCache`.
- [ ] **Step 2: Lancer** : échec attendu.
- [ ] **Step 3:** Implémenter avec `UserRecord#getCustomClaims` puis `FirebaseAuth#setCustomUserClaims` (map fusionnée). Sans Firebase disponible : comportement des autres méthodes (`unavailable()`).
- [ ] **Step 4: Lancer** les tests : PASS.
- [ ] **Step 5: Commit** `feat(auth): claim Firebase du numéro déclaré`.

### Task 3: Endpoint « déclarer mon numéro »

**Files:**
- Create: `src/main/java/com/yadony/api/smsotp/DeclaredPhoneService.java`, `src/main/java/com/yadony/api/smsotp/dto/DeclarePhoneRequest.java`
- Modify: `SmsOtpController.java` (route `POST /auth/sms-otp/declare`), `SmsOtpService.java` (rendre `blockedCallingCode(String e164): Optional<String>` accessible au service), `config/SecurityConfig.java` (comme `/auth/sms-otp/attach`: `authenticatedNonGuest()`), `ratelimit/RateLimitRule.java` (même règle que `attach`), fichiers de messages FR/EN, l'écouteur de `UserPhoneAttachedEvent`
- Test: `DeclaredPhoneServiceTest.java`, `SmsOtpControllerDeclareIT.java`

**Interfaces:**
- Consumes: Task 1 (hasher, colonnes), Task 2 (`setDeclaredPhone`).
- Produces: `DeclaredPhoneService#declare(String firebaseUid, String e164): void` ; réponse `200` avec `UserResponse` (`authService.getProfile`).

- [ ] **Step 1: Tests** (assertions exactes) : `declare_blockedCountry_savesHashClaimAndLevelDeclared` ; `declare_countryNotBlocked_409` code `phone-declare-not-allowed` ; `declare_accountHasVerifiedPhone_409` code `phone-already-set` ; `declare_invalidE164_400` ; `declare_twice_replacesNumber_andResetsLevelToDeclared` (après validation admin) ; `declare_sameNumberOnTwoAccounts_bothAccepted` ; `declare_guest_403` ; `declare_auditContainsNoNumber` (action `USER_PHONE_DECLARED`) ; `phoneAttachedBySms_clearsDeclaredClaimHashAndLevel`.
- [ ] **Step 2: Lancer** : échec attendu.
- [ ] **Step 3:** Implémenter ; l'écouteur de `UserPhoneAttachedEvent` efface claim, hash, niveau, validation. Numéro hors `isCountryAllowed` refusé comme dans `sendOtp`.
- [ ] **Step 4: Lancer** les tests : PASS.
- [ ] **Step 5: Commit** `feat(sms-otp): déclarer son numéro sans SMS pour les pays bloqués`.

### Task 4: Exposition du numéro et du badge

**Files:**
- Modify: `auth/dto/UserResponse.java`, `auth/AuthService.java` (assemblage du profil, ~l.702), `auth/dto/ProfilePublicResponse.java` + `auth/ProfilePublicService.java`, `matching/BidService.java` (~l.828) et `ContactPhoneResponse`
- Test: tests existants de ces classes + `ContactPhoneDeclaredTest.java`

**Interfaces:**
- Consumes: `Contact.declaredPhone()`, `UserEntity.declaredPhoneLevel`.
- Produces: `UserResponse` gagne `declaredPhone: String` (propriétaire seulement) et `declaredPhoneLevel: String` ; `ContactPhoneResponse` gagne `verification: String` (`SMS`, `YADONY`, `NONE`) et renvoie `phoneNumber` = numéro vérifié sinon numéro déclaré ; `ProfilePublicResponse` gagne `phoneYadonyVerified: boolean` (`phoneVerified` inchangé, vrai seulement pour le SMS).

- [ ] **Step 1: Tests** : `contactPhone_fallsBackToDeclared_withVerificationNone` ; `contactPhone_yadonyVerified_returnsYadony` ; `contactPhone_smsWins_overDeclared` ; `contactPhone_hiddenByUser_stillForbidden` ; `publicProfile_phoneVerifiedStaysFalse_forDeclared` ; `publicProfile_yadonyFlag_trueOnlyForYadonyVerified`.
- [ ] **Step 2: Lancer** : échec. **Step 3:** Implémenter (champs ajoutés en fin de record, anciens constructeurs conservés pour les appelants existants). **Step 4: Lancer** : PASS.
- [ ] **Step 5: Commit** `feat(auth): exposer le numéro déclaré et son badge`.

### Task 5: Recherche par numéro (destinataires, admin)

> Une autre session Claude travaille sur la recherche de destinataires par numéro. Avant de toucher `RecipientInvitationService` et `ReceptionLinker`, lire sa branche et ses PR ouvertes ; se coordonner avec le propriétaire en cas de chevauchement.

**Files:**
- Create: `src/main/java/com/yadony/api/auth/PhoneLookupService.java`
- Modify: `addressbook/invitation/RecipientInvitationService.java` (~l.105), `matching/reception/ReceptionLinker.java` (~l.102), `admin/AdminUserController.java` (~l.147), `admin/AdminPackageRequestQueryService.java` (~l.204)
- Test: `PhoneLookupServiceTest.java` + tests existants de ces classes

**Interfaces:**
- Consumes: `FirebaseContactService#findUidByPhone`, `UserRepository#findAllByDeclaredPhoneHash`, `DeclaredPhoneHasher#hash`.
- Produces: `sealed interface PhoneMatch { Found(UserEntity user); Ambiguous(); None() }` et `PhoneLookupService#findByPhone(String e164): PhoneMatch`.

- [ ] **Step 1: Tests** : `findByPhone_smsVerifiedOwner_found` ; `findByPhone_oneDeclaredAccount_found` ; `findByPhone_twoDeclaredAccounts_ambiguous` ; `findByPhone_smsOwnerBeatsDeclared_found` ; `findByPhone_unknown_none`. Appelants : `linkIfPossible_ambiguous_doesNotLink` ; `invitation_ambiguous_returnsNeutralSent` (même réponse qu'un inconnu, pour ne rien révéler) ; admin : plusieurs résultats listés.
- [ ] **Step 2: Lancer** : échec. **Step 3:** Implémenter et brancher les quatre appelants (le temps de réponse de l'invitation ne dépend pas de l'issue, comme le commentaire existant l'exige). **Step 4: Lancer** : PASS.
- [ ] **Step 5: Commit** `feat(auth): retrouver une personne par son numéro déclaré`.

### Task 6: Validation par l'admin

**Files:**
- Modify: `admin/AdminUserController.java`, `admin/AdminUserDetailResponse` (+ son assembleur), la déclaration des autorités admin (suivre `USER_COMMISSION`)
- Test: `AdminUserDeclaredPhoneIT.java`

**Interfaces:**
- Consumes: Task 1 (colonnes), Task 2 (claim).
- Produces: `POST /admin/users/{userId}/declared-phone/verify` → `AdminUserDetailResponse` ; autorité `USER_PHONE_VERIFY` ; détail admin gagne `declaredPhone` et `declaredPhoneLevel`.

- [ ] **Step 1: Tests** : `verify_adminWithAuthority_setsYadonyVerifiedAndAudits` (action `ADMIN_USER_PHONE_VERIFIED`, id de l'admin, sans numéro) ; `verify_withoutAuthority_403` ; `verify_noDeclaredPhone_409` code `declared-phone-missing` ; `verify_thenUserRedeclares_backToDeclared` ; `verify_doesNotWriteFirebasePhoneNumber` (le champ officiel reste nul).
- [ ] **Step 2: Lancer** : échec. **Step 3:** Implémenter. **Step 4: Lancer** : PASS.
- [ ] **Step 5: Commit** `feat(admin): valider un numéro déclaré (Vérifié par Yadony)`.

### Task 7: Garde-fous connexion et argent

**Files:**
- Test: `src/test/java/com/yadony/api/auth/DeclaredPhoneIsolationTest.java`

- [ ] **Step 1: Tests** avec un compte portant uniquement le claim (niveaux `DECLARED` puis `YADONY_VERIFIED`) : `contact_phoneNumberStaysNull` ; `phoneLogin_rejected` (`AuthService` renvoie `phone-required`) ; `mobileMoneyPayer_ignoresDeclared` (`MobileMoneyAccountService`, l.169) ; `smsFallback_skipsDeclared` (`SmsFallbackScheduler`) ; `findUidByPhone_doesNotMatchDeclared` (`FirebaseContactService`).
- [ ] **Step 2: Lancer** : PASS attendu (ils prouvent l'isolation) ; si un échoue, corriger le lecteur fautif.
- [ ] **Step 3: Commit** `test(auth): le numéro déclaré ne touche ni connexion ni argent`.

### Task 8: Fiches de l'assistant (FR et EN)

**Files:**
- Modify: `src/main/resources/assistant/kb/{fr,en}/changer-numero.md`, `creer-compte.md` (compléter ; créer `numero-sans-sms.md` FR et EN si le sujet ne tient pas dans l'existant)

- [ ] **Step 1:** Y dire la réalité du code : pays où le SMS n'arrive pas, bouton « Enregistrer sans vérifier », badges « Non vérifié » / « Vérifié par Yadony », ce numéro sert à être joint et retrouvé, pas à se connecter ni à payer, correction libre. Routes uniquement parmi celles de `dony_app/lib/app/router.dart`.
- [ ] **Step 2:** Lancer le test de la base de connaissance (`./mvnw test -Dtest='*KnowledgeBase*'`) : PASS. **Step 3: Commit** `docs(assistant): fiches numéro sans SMS`.
- [ ] **Step 4:** `./mvnw test jacoco:report`, ouvrir la PR back, attendre la CI verte.

### Task 9: App Flutter (PR jumelle, après la PR back)

**Files (dony_app, depuis `origin/main`):**
- Modify: `lib/features/auth/data/models/user_model.dart` (`declaredPhone`, `declaredPhoneLevel`, tolérant à l'absence), `lib/features/auth/data/datasources/auth_remote_datasource.dart` (`declarePhone(String e164)` vers `POST /auth/sms-otp/declare`), `lib/features/auth/bloc/` (événement `AuthPhoneDeclared`), l'écran d'ajout de numéro, l'écran du contact de la contrepartie, `lib/features/profile/data/models/profile_public_model.dart` (`phoneYadonyVerified`), les fichiers l10n FR et EN
- Create: `lib/core/design/widgets/phone_verification_badge.dart` (`PhoneVerificationBadge(level)` : « Non vérifié » / « Vérifié par Yadony » / « Vérifié », pastilles distinctes)
- Test: tests BLoC, widget du badge, widget de l'écran d'ajout

- [ ] **Step 1: Tests** : `AuthPhoneDeclared_success_emitsUserWithDeclaredPhone` ; l'ajout de numéro répondant `country-blocked` propose « Enregistrer sans vérifier » et n'envoie pas de code ; `PhoneVerificationBadge` rend les trois libellés ; contact de la contrepartie affiche le badge correspondant à `verification` ; JSON sans nouveaux champs = aucun crash ; libellé du bouton sur une ligne en FR et EN à 360 dp (`tester.takeException()` nul).
- [ ] **Step 2: Lancer** `flutter test` ciblé : échec. **Step 3:** Implémenter (BLoC, GoRouter, bouton dans `stickyBottom`, pas de `setState`). **Step 4:** `flutter analyze && flutter test --coverage` : PASS, ≥ 90 %.
- [ ] **Step 5:** PR citant le numéro de la PR back ; CI verte ; fusion après la PR back.

### Task 10: Admin web (PR jumelle, après la PR back)

**Files (dony-admin, depuis `origin/main`):** la page fiche utilisateur `/users/[id]` : afficher `declaredPhone` et son niveau ; bouton « Valider ce numéro » (visible avec l'autorité `USER_PHONE_VERIFY`) appelant `POST /admin/users/{id}/declared-phone/verify` ; recherche par numéro inchangée côté interface.

- [ ] **Step 1: Tests** du composant : bouton visible seulement avec l'autorité ; après succès, badge « Vérifié par Yadony ». **Step 2: Lancer** : échec. **Step 3:** Implémenter. **Step 4:** Lancer les tests et le lint : PASS. **Step 5:** PR citant la PR back, CI verte, fusion après elle.
