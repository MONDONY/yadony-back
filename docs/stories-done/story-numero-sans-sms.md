# Story numéro sans SMS — Numéro déclaré pour les pays où le SMS n'arrive pas (Backend)

**Date:** 2026-11-22
**Status:** Code complet (PR yadony-back #777). Suite complète `./mvnw test` et couverture JaCoCo non mesurées en local : à confirmer par la CI (voir « Tests »).

## Résumé
Dans les pays listés dans le paramètre admin `SMS_BLOCKED_COUNTRY_CODES`, le code SMS de vérification n'arrive pas. Un compte peut y **déclarer** son numéro sans SMS ; un administrateur peut ensuite le **valider à la main** (« Vérifié par Yadony »). Le numéro sert à être joint et à être retrouvé ; seuls un numéro vérifié par SMS ou validé par Yadony rattachent un colis ou résolvent une invitation. Jamais de connexion, jamais d'argent sur un numéro déclaré.

Spécification : `docs/superpowers/specs/2026-11-21-telephone-sans-sms-design.md`. Plan : `docs/superpowers/plans/2026-11-21-telephone-sans-sms.md`.

## Fichiers créés
- `src/main/java/com/yadony/api/smsotp/DeclaredPhoneService.java` — déclaration (`declare`) et effacement de la déclaration quand un numéro SMS est rattaché (`onPhoneAttached`).
- `src/main/java/com/yadony/api/smsotp/dto/DeclarePhoneRequest.java` — corps de `POST /auth/sms-otp/declare` (E.164 validé).
- `src/main/java/com/yadony/api/admin/AdminDeclaredPhoneService.java` — validation par un administrateur (`verify`), échec fermé.
- `src/main/java/com/yadony/api/admin/dto/VerifyDeclaredPhoneRequest.java` — numéro que l'administrateur a sous les yeux.
- `src/main/java/com/yadony/api/auth/DeclaredPhoneHasher.java` — empreinte HMAC-SHA256 du numéro (clé `DECLARED_PHONE_HASH_KEY`).
- `src/main/java/com/yadony/api/auth/DeclaredPhoneLevel.java` — `DECLARED` | `YADONY_VERIFIED`.
- `src/main/java/com/yadony/api/auth/DeclaredPhoneResolver.java` — point unique qui décide quel numéro déclaré et quel niveau un compte peut exposer.
- `src/main/java/com/yadony/api/auth/PhoneLookupService.java` — recherche d'un compte par numéro (SMS ou déclaré) avec la précédence, et `trustedDeclaredPhone`.
- `src/main/java/com/yadony/api/auth/PhoneMatch.java` — `Found(user, trust SMS|YADONY|DECLARED)`, `Ambiguous`, `None`.
- `src/main/resources/db/migration/V397__declared_phone.sql` — 4 colonnes nullables sur `users` + index sur l'empreinte.
- `src/main/resources/db/migration/V398__declared_phone_yadony_unique.sql` — index unique partiel : un seul compte vivant validé par Yadony par numéro.
- `src/main/resources/assistant/kb/{fr,en}/numero-sans-sms.md`, `src/main/resources/assistant/admin-help/numero-declare-verifie-par-yadony.md` — fiches de l'assistant.

## Fichiers modifiés
- `auth/FirebaseContactService.java` — `Contact.declaredPhone` (claim `declared_phone`), `setDeclaredPhone` (lecture-modification-écriture des claims), `findPhoneOwner` (FOUND / NOT_FOUND / ERROR), `readContactStrict` (lecture fraîche, vide sur panne).
- `auth/UserEntity.java`, `auth/UserRepository.java` — colonnes du numéro déclaré ; `findTop10ByDeclaredPhoneHash` (admin), `findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel` (comptes validés, lus à part), `existsByDeclaredPhoneHashAndDeclaredPhoneLevelAndIdNot` ; recherche admin `findAdminFiltered` avec `IN (:queryFirebaseUids)`.
- `auth/AuthService.java`, `auth/dto/UserResponse.java` — profil du propriétaire : `declaredPhone`, `declaredPhoneLevel`.
- `auth/ProfilePublicService.java`, `auth/dto/ProfilePublicResponse.java` — `phoneYadonyVerified` (booléen, jamais le numéro).
- `matching/BidService.java`, `matching/dto/ContactPhoneResponse.java` — numéro de la contrepartie : SMS, sinon déclaré, avec `verification` = `SMS` | `YADONY` | `NONE`.
- `matching/reception/ReceptionLinker.java`, `ReceptionCatchUpListener.java` — rattachement des colis : SMS ou YADONY seulement.
- `addressbook/invitation/RecipientInvitationService.java` — invitation par numéro résolue sur SMS ou YADONY seulement ; invitations reçues sur le numéro validé.
- `admin/AdminUserController.java`, `admin/dto/AdminUserDetailResponse.java`, `admin/account/AdminPermission.java` — fiche admin (numéro et niveau), `POST /admin/users/{id}/declared-phone/verify`, permission `USER_PHONE_VERIFY`, recherche par numéro.
- `admin/AdminPackageRequestQueryService.java` — recherche de demandes de colis par numéro (tous les comptes qui le portent).
- `auth/AccountFinalizationService.java` — l'anonymisation efface empreinte, niveau et trace de validation.
- `export/UserDataExportService.java`, `export/dto/UserDataExportDto.java` — export RGPD : numéro déclaré, niveau, date de validation.
- `smsotp/SmsOtpController.java`, `smsotp/SmsOtpService.java`, `config/SecurityConfig.java` — route `declare` (authentifiée, jamais un invité).
- `ratelimit/RateLimitRule.java`, `application.yml` — `POST /auth/sms-otp/declare` dans `OTP_VERIFY_IP` et règle par compte `SMS_OTP_DECLARE_ACCOUNT` (10 / 24 h).
- `application*.yml`, `.env*.template`, `.github/workflows/deploy-{staging,prod}.yml` — `yadony.declared-phone.hash-key` / `DECLARED_PHONE_HASH_KEY`.
- `i18n/messages_{fr,en}.properties` — codes d'erreur ci-dessous.
- `assistant/kb/{fr,en}/changer-numero.md`, `creer-compte.md` — parcours mis à jour.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
**Déclaration (utilisateur)**
1. L'ajout de numéro par SMS répond 503 `country-blocked` pour un indicatif bloqué ; l'app propose « Enregistrer sans vérification » et appelle `POST /auth/sms-otp/declare` avec le numéro E.164.
2. `DeclaredPhoneService.declare` : clé d'empreinte et Firebase présents (sinon 503 `declared-phone-unavailable`), pays servi (422), indicatif bloqué (sinon 409 `phone-declare-not-allowed` : il faut le SMS), compte existant, puis lecture Firebase **stricte** : panne = 503, numéro SMS présent = 409 `phone-already-set`.
3. Même numéro déjà en place (empreinte, niveau et claim identiques) : retour immédiat, rien n'est écrit.
4. Sinon : empreinte et niveau `DECLARED` en base (un autre numéro efface la validation admin), `saveAndFlush`, audit `USER_PHONE_DECLARED` (sans numéro ni empreinte), puis claim Firebase `declared_phone` en dernier. Si Firebase refuse, la transaction est annulée (base et audit).
5. La réponse est le profil (`UserResponse`) avec `declaredPhone` et `declaredPhoneLevel`.

**Validation (administrateur)**
1. La fiche admin affiche le numéro déclaré et son niveau ; l'admin envoie `POST /admin/users/{id}/declared-phone/verify` avec le numéro qu'il voit.
2. `AdminDeclaredPhoneService.verify` : lecture Firebase stricte (panne = 503), claim et empreinte concordants (sinon 409 `declared-phone-missing`), numéro affiché = claim (sinon 409 `declared-phone-changed`), pas de numéro SMS sur le compte (409 `phone-already-set`).
3. `requireNoOtherOwner` : propriétaire SMS ailleurs = 409 `declared-phone-conflict` ; numéro SMS porté par un compte Firebase sans compte Yadony vivant = 409 `declared-phone-held-by-unregistered-account` ; panne de la recherche = 503 ; autre compte déjà validé en base = 409 `declared-phone-conflict`.
4. Niveau `YADONY_VERIFIED`, date et administrateur, `saveAndFlush` (une violation de l'index V398 = 409 `declared-phone-conflict`), audit `ADMIN_USER_PHONE_VERIFIED` sans numéro. Idempotent.

**Utilisation du numéro**
- Contact d'une contrepartie (`ContactPhoneResponse`) : le numéro SMS, sinon le déclaré avec `verification`.
- Rattachement d'un colis (`ReceptionLinker`) et invitation par numéro (`RecipientInvitationService`) : `PhoneLookupService.findByPhone`, seuls `Found` de confiance SMS ou YADONY comptent.
- Rattrapage des réceptions et invitations reçues : `PhoneLookupService.trustedDeclaredPhone`, revalidé à chaque appel (aucun cache).
- Recherche admin (utilisateurs, demandes de colis) : `findAllByPhone`, propriétaire SMS et déclarants confirmés.

### Points d'entrée API
- `POST /api/v1/auth/sms-otp/declare` — déclarer son numéro sans SMS. Compte connecté, jamais un invité. Limites : 60 / 10 min par IP (`OTP_VERIFY_IP`), 10 / 24 h par compte (`SMS_OTP_DECLARE_ACCOUNT`).
- `POST /api/v1/admin/users/{userId}/declared-phone/verify` — valider le numéro affiché. `hasRole('ADMIN') and hasAuthority('USER_PHONE_VERIFY')` (ADMIN et SUPER_ADMIN, jamais le support).
- `GET /api/v1/admin/users/{userId}` — `declaredPhone`, `declaredPhoneLevel` (niveau recalculé par `DeclaredPhoneResolver`).
- `GET /api/v1/admin/users?query=+225…` — recherche par numéro, déclarants compris.

### Entités JPA impliquées
- `UserEntity` → table `users` : `declared_phone_hash` (VARCHAR 64, HMAC-SHA256 hexadécimal), `declared_phone_level` (`DECLARED` | `YADONY_VERIFIED`), `declared_phone_verified_at`, `declared_phone_verified_by` (UUID admin). Le numéro en clair n'est **jamais** en base : il vit dans le claim Firebase `declared_phone`. Soft delete via `@Where(deleted_at IS NULL)`.
- Index `idx_users_declared_phone_hash` (V397) ; index unique partiel `uq_users_declared_phone_yadony` sur `declared_phone_hash` `WHERE declared_phone_level = 'YADONY_VERIFIED' AND deleted_at IS NULL` (V398).

### Logique métier critique
- **Trois niveaux** : déclaré (aucune preuve), « Vérifié par Yadony » (validation manuelle), vérifié par SMS (seul à servir à la connexion et aux paiements). Le déclaré n'est jamais écrit dans Firebase `phoneNumber` : ~10 lecteurs le prennent pour vérifié (vol de colis, squat de numéro).
- **Règle de fiabilité** (`DeclaredPhoneResolver`) : le niveau en base n'est cru que si l'empreinte du claim courant égale `declared_phone_hash` ; sinon le compte retombe à `DECLARED`. Un numéro SMS sur le compte fait ignorer le déclaré.
- **Précédence** (`PhoneLookupService.findByPhone`) : propriétaire SMS > compte unique validé par Yadony > ambigu (plusieurs validés) ; un simple déclarant n'est jamais rattaché. Panne Firebase = aucun résultat (échec fermé). Les comptes validés sont lus par une requête dédiée (plafond 3), jamais dans la liste mixte plafonnée à 10 : des déclarants en nombre ne peuvent pas masquer le compte validé.
- **Décision du propriétaire (21/11)** : un numéro simplement déclaré ne rattache jamais un colis et ne résout jamais une invitation (sinon un faux compte verrait le code de retrait d'un destinataire).
- **Pas d'unicité des déclarés** : plusieurs comptes peuvent déclarer le même numéro ; un seul peut être validé (V398).
- **Aucun cache de confiance** : `trustedDeclaredPhone` et `findByPhone` interrogent Firebase à chaque usage (recherche du propriétaire SMS ; la lecture groupée des claims profite du cache de 5 min de `FirebaseContactService`). Un résultat positif mis en cache resterait valable après qu'un propriétaire SMS a rattaché le numéro ou qu'un administrateur a changé la validation : une invitation acceptée ou un colis rattaché à tort. Garde-fou : `PhoneLookupServiceTest.trustedDeclaredPhone_revalidatedOnEveryCall_smsOwnerAppearingIsSeenAtOnce`.
- **Échec fermé partout** : lecture stricte `readContactStrict` (vide sur panne), recherche de propriétaire en trois issues, validation refusée tant qu'un compte Firebase fantôme porte le numéro (aucune libération automatique).
- **Audit** : `USER_PHONE_DECLARED` et `ADMIN_USER_PHONE_VERIFIED`, payload `{level}` seulement ; une déclaration inchangée n'écrit aucune ligne (table immuable).

### Events Spring publiés / écoutés
- `UserPhoneAttachedEvent` (rattachement d'un numéro SMS) → écouté par `DeclaredPhoneService.onPhoneAttached` (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` + `REQUIRES_NEW`) : retire le claim puis efface empreinte, niveau et validation.
- `ReceptionCatchUpListener` : rattrapage des colis à l'inscription ou à l'ajout d'un numéro, sur le numéro SMS ou le numéro validé de confiance.

### Pièges et points d'attention
- **Migrations** : V397 (colonnes) et V398 (index unique) ont été renumérotées trois fois à la fusion de `origin/main` (d'abord V391, puis V392 et V393, puis V396 et V397) : V391 est prise par l'identifiant Yadony, V393 à V395 par les frais d'annulation (#779), V396 par le destinataire désigné par identifiant (#781). Les deux sont renumérotées ensemble, après la dernière de main : un environnement qui a déjà joué les migrations de main refuserait une version plus basse arrivée plus tard (hors ordre). `MigrationVersionsUniqueTest` ne voit que la branche locale : relire `git ls-tree -r origin/main --name-only | grep db/migration` avant chaque fusion. Ne jamais modifier V397/V398 une fois déployées, créer V(n+1).
- **V398 échoue** si une base contient déjà deux comptes vivants validés sur la même empreinte (possible seulement là où une ancienne version de la branche aurait tourné). Avant un tel déploiement : `SELECT declared_phone_hash, count(*) FROM users WHERE declared_phone_level = 'YADONY_VERIFIED' AND deleted_at IS NULL GROUP BY 1 HAVING count(*) > 1;`.
- **H2 contre PostgreSQL** : le profil test crée le schéma par Hibernate, les migrations n'y tournent pas (ni V397 ni l'index partiel V398). Le SQL natif doit rester portable : pas de `string_to_array`/`ANY`, plus de `POSITION` dans une chaîne jointe ; `IN (:queryFirebaseUids)` avec la garde `:hasFirebaseUids` et une liste sentinelle (`List.of("")`) pour ne jamais générer `IN ()`. Les vérifications propres à PostgreSQL sont dans `DeclaredPhoneSchemaPostgresIT`, `AdminDeclaredPhoneRacePostgresIT`, `UserRepositoryAdminSearchIT`.
- **`DECLARED_PHONE_HASH_KEY`** : vide = fonction désactivée (503 `declared-phone-unavailable`, le démarrage n'échoue pas). La changer rend toutes les empreintes muettes (niveaux retombés à `DECLARED`, plus aucune recherche par numéro déclaré) : ne jamais la faire tourner sans recalcul. Transmise par `deploy-staging.yml` et `deploy-prod.yml`.
- **`setCustomUserClaims` remplace tout le dictionnaire** : `setDeclaredPhone` relit les claims et ne touche qu'à `declared_phone`.
- **Ordre des écritures** dans `declare` : `saveAndFlush` (version, verrou), audit, Firebase en dernier ; dans `onPhoneAttached`, Firebase d'abord (un échec laisse l'empreinte pour un nouvel essai).
- **`getContact` (cache 5 min, `Contact.EMPTY` sur panne)** ne convient pas aux gardes : utiliser `readContactStrict`.
- **Messages FR** : MessageFormat, chaque apostrophe doublée (`''`).
- **Garde source** : aucune classe de `payments/` ni `notifications/` ne lit le numéro déclaré (`DeclaredPhoneIsolationTest`).

## Critères d'acceptation couverts
- [x] Indicatif dans `SMS_BLOCKED_COUNTRY_CODES` : numéro enregistré sans SMS, niveau `DECLARED`, visible avec la mention (`DeclaredPhoneService`, `UserResponse`, `ContactPhoneResponse`).
- [x] Indicatif hors liste : 409 `phone-declare-not-allowed`, le SMS reste obligatoire.
- [x] Validation manuelle par un administrateur, audit avec l'id de l'admin, sans le numéro ; redéclarer un autre numéro ramène à `DECLARED`.
- [x] Jamais pour la connexion ni l'argent (claim distinct de `phoneNumber`, garde source sur `payments/` et `notifications/`).
- [x] Recherche admin par numéro, déclarants compris.
- [x] Seuls SMS et « Vérifié par Yadony » rattachent un colis ou résolvent une invitation ; numéro contesté = aucun rattachement.
- [x] Un numéro SMS rattaché remplace la déclaration (`onPhoneAttached`).
- [x] Limitation de débit (IP et compte), anonymisation et export RGPD couverts.

## Tests
Ce qui a été lancé dans la session de corrections de revue (22/11), **tests ciblés uniquement** :
- Unitaires et PostgreSQL : `PhoneLookupServiceTest` (31), `AdminDeclaredPhoneServiceTest` (11), `DeclaredPhoneServiceTest` (21), `FirebaseContactServiceDeclaredPhoneTest` (13), `FirebaseContactServiceTest` (22), `RateLimitInterceptorTest` (17), `UserDataExportServiceTest` (8), `DeclaredPhoneSchemaPostgresIT` (4), `AdminDeclaredPhoneRacePostgresIT` (1), `ReceptionCatchUpPrecedenceTest` (3), `DeclaredPhoneIsolationTest` (14), `AdminUserControllerTest` (34), `MigrationVersionsUniqueTest` (1), `MessagesBundleTest` (8), `ServerMessagesGuardTest` (5), `ReceptionLinkerTest` (36), `RecipientInvitationServiceTest` (55), `ContactPhoneDeclaredTest` (19), `DeclaredPhoneHasherTest` (5) : 308 tests, 0 échec.
- Intégration Spring (H2 et PostgreSQL) : `AdminUser*`, `AdminPackageRequest*`, `AdminRecetteBulkIT`, `SmsOtpControllerDeclareIT` et `SmsOtpController*Test`, `DeclaredPhoneColumnsIT`, `RecipientInvitation*`, `UserDataExport*`, `RateLimit*`, `UserRepositoryAdminSearchIT` : 441 tests, 0 échec.
- `./mvnw -q -DskipTests test-compile` : OK.
- **Non fait en local** : la suite complète `./mvnw test` et le rapport JaCoCo ; le pourcentage de couverture n'a pas été mesuré. La CI de la PR fait foi.

Tests créés pour la fonctionnalité : `DeclaredPhoneServiceTest`, `SmsOtpControllerDeclareIT`, `AdminDeclaredPhoneServiceTest`, `AdminUserDeclaredPhoneIT`, `AdminDeclaredPhoneRacePostgresIT`, `AdminUserPhoneSearchH2IT`, `PhoneLookupServiceTest`, `DeclaredPhoneIsolationTest`, `ContactPhoneDeclaredTest`, `DeclaredPhoneColumnsIT`, `DeclaredPhoneSchemaPostgresIT`, `DeclaredPhoneHasherTest`, `FirebaseContactServiceDeclaredPhoneTest`, `ReceptionCatchUpPrecedenceTest`. Modifiés : `RateLimitInterceptorTest`, `UserDataExportServiceTest`, `UserRepositoryAdminSearchIT`, `AdminUserControllerTest`, `RecipientInvitationServiceTest`, `ReceptionLinkerTest`, `AccountFinalizationServiceTest`, `E2EMockConfig`.

## Décisions techniques
- **Claim Firebase plutôt que colonne en clair** : un vol de la base ne révèle aucun numéro ; l'empreinte HMAC (clé hors base) suffit à retrouver les déclarants. Écarté : écrire dans `phoneNumber` sans code (lu comme vérifié partout).
- **Pas d'unicité des déclarés, unicité des validés** : bloquer un numéro déclaré laisserait un squatteur empêcher le vrai titulaire ; deux comptes validés rendraient le numéro ambigu pour tout le monde (index V398).
- **Requête dédiée aux comptes validés** plutôt qu'un plafond plus haut ou un tri : le plafond reste nécessaire pour l'admin, et aucun nombre de déclarants ne doit pouvoir masquer le compte validé.
- **Compte Firebase fantôme : échec fermé avec un code distinct**, sans libération automatique : libérer un numéro SMS d'un compte Firebase est irréversible et relève d'un geste humain.
- **Pas de cache sur `trustedDeclaredPhone`** (constat de revue « appel Firebase à chaque ouverture des réceptions » laissé ouvert) : la confiance doit être revalidée à chaque usage, un cache positif est une fenêtre de péremption. Chaque requête ne fait déjà qu'un appel (`ReceptionLinker#catchUp`, `RecipientInvitationService#catchUp` ou `#accept`), et seule la recherche du propriétaire SMS part réellement vers Firebase ; aucune réduction sûre n'était possible.
- **Injection de `PhoneLookupService` / `DeclaredPhoneResolver` hors du paquet `auth`** : même précédent que `FirebaseContactService` (lecture synchrone, pas d'événement possible pour une réponse immédiate). Conservé tel quel.
