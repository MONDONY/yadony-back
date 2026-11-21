# Destinataire par identifiant Yadony : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un utilisateur peut modifier son identifiant public (`users.username`), et un expéditeur peut inviter un destinataire par cet identifiant, avec le même accord du destinataire que pour un numéro ou un e-mail.

**Architecture:** On étend l'existant : `users.username` (V183) devient modifiable, et `recipient-invitations` gagne un canal `USERNAME`. La recherche est une requête SQL (aucun appel Firebase) bornée par un quota. Le lien colis/destinataire existant (`bid_recipient_links`, `RecipientReplacementService`) sert au refus. Livraison en deux étapes, back puis app à chaque fois.

**Tech Stack:** Spring Boot 3.4 / Java 21 / PostgreSQL + Flyway / Caffeine / JUnit 5 + MockMvc (back) ; Flutter, BLoC, GoRouter, `DonyBottomSheet` (app).

**Spec:** `docs/superpowers/specs/2026-11-21-destinataire-par-pseudo-design.md` (maquettes : https://claude.ai/artifact/Q3MupK5SMqrZbHi7ZhVm2Y)

## Écarts avec le design (découverts en lisant le code)

- **Le numéro n'est jamais montré à l'expéditeur avant l'accord.** Il arrive dans son carnet seulement à l'acceptation (`RecipientInvitationService.accept`, déjà le cas). On garde ce principe.
- **L'étape 2 (invitation liée à un colis, « en attente » sur le colis)** demande de relier invitation et colis (`bidId`). Elle est séparée de l'étape 1 pour livrer vite ce qui est autonome. L'étape 1 donne déjà : modifier son identifiant, inviter par identifiant, notification de refus.
- **Le refus d'une invitation notifie désormais l'expéditeur** (aujourd'hui `decline()` ne dit rien). Les tests qui figent ce silence changent.

## Global Constraints

- Commits : jamais sur `main`, branche `feature/username-recipient-invite` dans un worktree (`dony-back/.claude/worktrees/<nom>`), aucune ligne `Co-Authored-By`.
- Migrations : jamais modifier l'existant ; prendre `V(n+1)` libre au moment de coder (dernier vu : V390). Pas de collision de version entre PR.
- Erreurs RFC 7807 via `YadonyBusinessException.localized(HttpStatus, type, messageKey)` ; clés dans les fichiers de messages FR et EN.
- `audit_log` pour chaque changement d'identifiant ; jamais de numéro en clair dans le journal.
- Pas de `Utils.java` ; code partagé dans `common/` ; entre packages, événements Spring seulement.
- Format d'identifiant : 3 à 20 caractères, `a-z 0-9 . _`, pas de `.` ni `_` au début ou à la fin, pas deux séparateurs d'affilée. Comparaison sans casse.
- Un changement d'identifiant par 30 jours ; l'ancien reste réservé 30 jours.
- Recherche exacte uniquement, 30 recherches par heure et par utilisateur, 429 au-delà. Identifiant inconnu : 404 explicite (voulu par le propriétaire).
- Quota d'invitations commun : 20 par 24 h (existant).
- Fiches assistant FR et EN (même `id`) mises à jour dans la PR back.
- Tests : `./mvnw test` à 0 rouge, couverture ≥ 90 %. Jamais deux `mvnw` en parallèle. Jamais `-DskipTests`.
- App : BLoC + GoRouter, pas de `setState`, boutons des feuilles dans `stickyBottom`, contrôleurs de texte possédés par le `State` du contenu, libellés de bouton courts, vérifier FR et EN à 360 dp, pas de tiret cadratin dans l'UI, aucun dégradé.
- Merge : back avant app. Jamais de soumission en review Apple. Aucun build sans accord explicite.

## Review Focus

- Identifiant avec majuscules, espaces, accents ou caractères Unicode : normalisé (minuscules, trim) ou refusé en 422, jamais accepté de travers.
- Deux utilisateurs prennent le même identifiant en même temps : l'index unique tranche, le perdant reçoit 409 `username-taken`, pas un 500.
- Identifiant d'un compte supprimé (soft delete) : reste réservé, `lookup` répond « introuvable ».
- L'expéditeur est bloqué par le destinataire, ou se cherche lui-même : « introuvable » dans le premier cas, 422 dans le second.
- Le destinataire change d'identifiant pendant qu'une invitation est en attente : l'invitation suit l'identifiant du compte (id), pas le texte.

---

# ÉTAPE 1 : identifiant modifiable + invitation par identifiant

## Back

### Task 1: Règles d'identifiant

**Files:**
- Create: `src/main/java/com/yadony/api/auth/UsernamePolicy.java`
- Create: `src/main/java/com/yadony/api/auth/UsernameSuggester.java`
- Test: `src/test/java/com/yadony/api/auth/UsernamePolicyTest.java`, `UsernameSuggesterTest.java`

**Interfaces:**
- Produces:
  - `UsernamePolicy.normalize(String raw): String` (trim + minuscules, `null` devient `""`).
  - `UsernamePolicy.violation(String normalized): Optional<UsernamePolicy.Violation>` avec `enum Violation { INVALID_FORMAT, RESERVED }`.
  - `UsernameSuggester.suggest(String normalizedBase, Predicate<String> isFree): List<String>` (0 à 3 propositions libres et valides).

- [ ] **Step 1: Write the failing tests** (`UsernamePolicyTest`) : `normalize("  Awa.Diallo ")` égale `"awa.diallo"` ; `violation("ab")` égale `INVALID_FORMAT` ; 21 caractères égale `INVALID_FORMAT` ; `"awa diallo"`, `"awa-d"`, `"é"`, `".awa"`, `"awa."`, `"a..b"`, `"_awa"` égalent `INVALID_FORMAT` ; `"awa.diallo"`, `"awa_d2"`, `"user1785012345"` sont valides ; `"admin"`, `"yadony"`, `"support"`, `"moderation"`, `"officiel"`, `"yadony.support"` égalent `RESERVED`. `UsernameSuggesterTest` : avec `isFree = s -> !s.equals("awa")` et base `"awa"`, `suggest` renvoie 3 éléments tous valides selon `UsernamePolicy`, distincts, différents de `"awa"` ; avec `isFree = s -> false` renvoie une liste vide.
- [ ] **Step 2: Run** `./mvnw test -Dtest=UsernamePolicyTest,UsernameSuggesterTest` ; attendu : échec de compilation.
- [ ] **Step 3: Implement** les deux classes. Regex `^(?=.{3,20}$)[a-z0-9]+([._][a-z0-9]+)*$`. Mots réservés (insensible à la casse, contient le mot) dans une constante `Set<String>` : `admin`, `yadony`, `support`, `moderation`, `officiel`. Suggestions : base tronquée pour laisser la place, puis suffixes `.d`, `_` + 2 chiffres, année courante ; chaque candidat doit passer `violation` vide et `isFree`.
- [ ] **Step 4: Run** les mêmes tests ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(auth): règles et suggestions d'identifiant`.

### Task 2: Migration et réservation des anciens identifiants

**Files:**
- Create: `src/main/resources/db/migration/V391__username_change_and_released.sql` (prendre le numéro libre)
- Modify: `src/main/java/com/yadony/api/auth/UserEntity.java` (champ `usernameChangedAt`), `UserRepository.java`
- Create: `src/main/java/com/yadony/api/auth/ReleasedUsernameEntity.java`, `ReleasedUsernameRepository.java`
- Test: `src/test/java/com/yadony/api/auth/UsernameMigrationIT.java`

**Interfaces:**
- Produces:
  - Colonne `users.username_changed_at TIMESTAMPTZ NULL`.
  - Index unique `ux_users_username_lower ON users (lower(username))`.
  - Table `released_usernames(username VARCHAR(32) PRIMARY KEY, released_at TIMESTAMPTZ NOT NULL, former_user_id UUID NOT NULL)`.
  - `UserRepository.findByUsernameIgnoreCase(String lowered): Optional<UserEntity>` (requête native sur `lower(username)`, lignes soft-deleted exclues).
  - `UserRepository.existsByUsernameIgnoreCase(String lowered): boolean` (native, y compris soft-deleted, comme `existsByUsername`).
  - `ReleasedUsernameRepository.existsByUsernameAndReleasedAtAfter(String, OffsetDateTime): boolean` et `deleteByReleasedAtBefore(OffsetDateTime): int`.

- [ ] **Step 1: Write the failing test** `UsernameMigrationIT` : la migration s'applique sur base vide ; insérer deux utilisateurs `Awa` et `awa` échoue sur `ux_users_username_lower` ; `findByUsernameIgnoreCase("awa")` retrouve un compte stocké en `Awa`, et ne retrouve pas un compte avec `deleted_at` renseigné.
- [ ] **Step 2: Run** `./mvnw test -Dtest=UsernameMigrationIT` ; attendu : FAIL.
- [ ] **Step 3: Implement** migration et accesseurs. Les 14-16 caractères existants (`user<epoch>`) respectent déjà l'index. Vérifier qu'aucun doublon de casse n'existe (la requête de contrôle est dans la migration en commentaire).
- [ ] **Step 4: Run** le test ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(auth): migration changement d'identifiant`.

### Task 3: Vérifier et changer son identifiant

**Files:**
- Create: `src/main/java/com/yadony/api/auth/UsernameService.java`, `UsernamePurgeScheduler.java`
- Create: `src/main/java/com/yadony/api/auth/dto/UsernameAvailabilityResponse.java`, `ChangeUsernameRequest.java`
- Modify: `src/main/java/com/yadony/api/auth/AuthController.java`, `src/main/java/com/yadony/api/config/SecurityConfig.java` (réservé aux non invités, comme `/auth/me/residence-address`), fichiers de messages FR/EN
- Test: `UsernameServiceTest.java`, `AuthControllerUsernameIT.java`

**Interfaces:**
- Consumes: Task 1 (`UsernamePolicy`, `UsernameSuggester`), Task 2 (repositories).
- Produces:
  - `record UsernameAvailabilityResponse(boolean available, String reason, List<String> suggestions)` ; `reason` parmi `null`, `INVALID_FORMAT`, `RESERVED`, `TAKEN`, `RECENTLY_RELEASED`, `UNCHANGED`.
  - `UsernameService.check(String firebaseUid, String raw): UsernameAvailabilityResponse`.
  - `UsernameService.change(String firebaseUid, String raw): UserResponse`.
  - `GET /auth/me/username/availability?value=` et `PUT /auth/me/username` (corps `{username}`), retour `UserResponse` mis à jour.

- [ ] **Step 1: Write the failing tests.** `UsernameServiceTest` : libre donne `available=true` ; pris par un autre compte donne `TAKEN` avec 1 à 3 suggestions ; identifiant libéré il y a 10 jours donne `RECENTLY_RELEASED` ; le sien donne `UNCHANGED` ; `change` valide met à jour `username`, `usernameChangedAt`, ajoute l'ancien dans `released_usernames`, écrit `audit_log` `USERNAME_CHANGED` (ancien et nouveau) ; `change` 10 jours après un précédent changement lève 409 `username-change-too-soon` avec `nextChangeAt` ; `change` vers un pris lève 409 `username-taken` ; `DataIntegrityViolationException` à l'écriture donne 409 `username-taken` ; format invalide donne 422. `AuthControllerUsernameIT` : `PUT` 200 puis `GET /auth/me` renvoie le nouvel identifiant ; un invité reçoit 403.
- [ ] **Step 2: Run** `./mvnw test -Dtest=UsernameServiceTest,AuthControllerUsernameIT` ; attendu : FAIL.
- [ ] **Step 3: Implement** le service (transaction, verrou `findByFirebaseUidForUpdate`), les deux endpoints et `UsernamePurgeScheduler` : `@Scheduled(cron = "0 15 3 * * *")` supprime les lignes de plus de 30 jours (idempotent). Une identité libérée n'est reprenable par personne pendant 30 jours, pas même son ancien propriétaire (simplifie la règle).
- [ ] **Step 4: Run** les tests ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(auth): modification de l'identifiant avec vérification de disponibilité`.

### Task 4: Recherche d'un identifiant (aperçu minimal)

**Files:**
- Create: `src/main/java/com/yadony/api/addressbook/invitation/UsernameLookupService.java`, `LookupRateLimiter.java`, `dto/UsernameLookupResponse.java`
- Modify: `RecipientInvitationController.java`
- Test: `UsernameLookupServiceTest.java`, `RecipientInvitationLookupIT.java`

**Interfaces:**
- Consumes: Task 2 (`findByUsernameIgnoreCase`), `BlockVisibility.isHidden(UUID, UUID)`.
- Produces:
  - `record UsernameLookupResponse(String username, String initial)` (`initial` : première lettre du prénom en majuscule, `"?"` si pas de prénom).
  - `UsernameLookupService.lookup(String firebaseUid, String raw): UsernameLookupResponse`.
  - `GET /recipient-invitations/lookup?username=`.
  - `LookupRateLimiter.tryAcquire(UUID userId): boolean` (Caffeine, 30 par heure glissante, instance unique).

- [ ] **Step 1: Write the failing tests.** Trouvé : `{"username":"awa.diallo","initial":"A"}` sans nom de famille, téléphone ni e-mail dans la réponse (`jsonPath("$.phoneNumber").doesNotExist()` et idem pour `email`, `lastName`) ; inconnu : 404 `user-not-found` ; compte supprimé : 404 ; bloqué par le destinataire : 404 ; soi-même : 422 `recipient-invitation-self` ; 31e appel dans l'heure : 429 `recipient-lookup-quota` ; l'appel avec « AWA.Diallo » trouve le même compte.
- [ ] **Step 2: Run** `./mvnw test -Dtest=UsernameLookupServiceTest,RecipientInvitationLookupIT` ; attendu : FAIL.
- [ ] **Step 3: Implement** : normaliser par `UsernamePolicy.normalize`, une valeur au format invalide est traitée comme introuvable (pas d'oracle sur le format). Compter le quota avant de chercher.
- [ ] **Step 4: Run** ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(recipients): recherche d'un destinataire par identifiant`.

### Task 5: Invitation par identifiant

**Files:**
- Modify: `InvitationChannel.java` (ajout `USERNAME`), `dto/CreateRecipientInvitationRequest.java` (champ `String username`, constructeurs existants conservés), `RecipientInvitationService.java`, `RecipientInvitationRepository.java`, `InvitationTargets.java`
- Test: `RecipientInvitationServiceTest.java`, `RecipientInvitationControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 4 (même règles de résolution).
- Produces:
  - `POST /recipient-invitations` accepte exactement un de `phone`, `email`, `username`.
  - `RecipientInvitationRepository.existsByInviterUserIdAndInviteeUserIdAndStatusIn(UUID, UUID, Set<InvitationStatus>)`.
  - Invitation `USERNAME` : `inviteeUserId` renseigné, `targetHash` = hash de `"u:" + inviteeUserId`, `maskedTarget` = `"@" + username`.

- [ ] **Step 1: Write the failing tests.** Pseudo existant : 202 `SENT`, une ligne `PENDING` avec le bon `inviteeUserId`, événement `RecipientInvitationCreatedEvent` publié ; pseudo inconnu : 404 `user-not-found`, rien d'écrit, quota non consommé ; deux invitations vivantes vers le même compte : la seconde ne crée rien (202) ; plusieurs champs fournis ou aucun : 422 ; compte qui a bloqué l'inviteur : 404 ; quota de 20 : 429 existant conservé ; les invitations `phone` et `email` gardent la réponse neutre (tests existants inchangés).
- [ ] **Step 2: Run** `./mvnw test -Dtest=RecipientInvitationServiceTest,RecipientInvitationControllerIntegrationTest` ; attendu : FAIL.
- [ ] **Step 3: Implement.** Le Javadoc de la classe précise que l'invariant « la réponse ne révèle jamais l'existence d'un compte » vaut pour `phone` et `email`, et que le canal `USERNAME` répond 404 pour un inconnu par décision du propriétaire, borné par le quota de recherche. Le libellé affiché (`SentInvitationDto`) relit l'identifiant courant du compte au moment de la lecture, pas le texte masqué stocké.
- [ ] **Step 4: Run** ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(recipients): invitation par identifiant`.

### Task 6: Notification de refus à l'expéditeur

**Files:**
- Modify: `RecipientInvitationService.java` (`decline`), `RecipientInvitationNotifications.java` (`DECLINED = "RECIPIENT_INVITATION_DECLINED"`), `NotificationTexts.java` (`recipientInvitationDeclined(...)`, FR et EN), `RecipientInvitationNotificationListener.java` si le push passe par là
- Test: `RecipientInvitationServiceTest.java`, `RecipientInvitationNotificationListenerTest.java`

**Interfaces:**
- Consumes: `NotificationDispatcher.notifyUser(UUID, String title, String body, Map<String,String> data)` comme dans `accept`.
- Produces: notification `data["type"] = "RECIPIENT_INVITATION_DECLINED"`, `data["invitationId"]`. Titre « Invitation non acceptée », corps du type « @awa.diallo n'a pas accepté votre invitation. » (cible = `maskedTarget`, jamais un numéro complet) ; EN équivalent.

- [ ] **Step 1: Write the failing tests** : `decline` appelle `notifyUser` une fois vers l'inviteur avec le type `RECIPIENT_INVITATION_DECLINED` et un corps qui contient `maskedTarget` ; un second `decline` sur la même invitation ne notifie pas deux fois (statut déjà `DECLINED`, `requirePending` lève comme aujourd'hui). Remplacer le test existant qui exige le silence.
- [ ] **Step 2: Run** les deux classes de test ; attendu : FAIL.
- [ ] **Step 3: Implement** et corriger le Javadoc de `decline` (« Refuse et prévient l'inviteur »).
- [ ] **Step 4: Run** ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(recipients): prévenir l'expéditeur du refus d'une invitation`.

### Task 7: Fiches assistant et clôture back de l'étape 1

**Files:**
- Modify: `src/main/resources/assistant/kb/fr/ecran-destinataires.md` et `en/…` (mêmes `id`), `ecran-nouveau-destinataire.md` si l'écran change
- Create: `src/main/resources/assistant/kb/fr/identifiant-yadony.md` et `en/identifiant-yadony.md` (`id: identifiant-yadony`)
- Create: `docs/stories-done/story-recipient-username-step1.md` (modèle du `CLAUDE.md`)

**Interfaces:** les `routes` des fiches ne citent que des chemins existants de `dony_app/lib/app/router.dart` (lire le routeur sur `origin/main` ; `/profile/recipients` et l'écran « Modifier le profil »).

- [ ] **Step 1:** écrire la fiche FR et EN : où trouver et copier son identifiant, comment le modifier (une fois tous les 30 jours), comment ajouter un destinataire par identifiant, ce qui se passe au refus.
- [ ] **Step 2: Run** `./mvnw test` (le test de la base de connaissances valide en-têtes et routes) ; attendu : tout vert.
- [ ] **Step 3: Run** `./mvnw test jacoco:report` ; relever la couverture globale ≥ 90 %.
- [ ] **Step 4: Commit** `docs(assistant): fiches identifiant Yadony` puis ouvrir la PR back, attendre la CI verte, fusionner.

## App (après fusion de la PR back, la PR app cite le numéro de la PR back)

### Task 8: Données et feuille « Mon identifiant »

**Files:**
- Modify: `lib/features/recipients/data/models/recipient_invitation.dart` (canal `username`), `recipient_invitation_datasource.dart` (`lookupUsername`, `send` avec `username`), repository, `lib/features/profile/data/…` (datasource `checkUsername`, `changeUsername`), `UserModel` (champ `username` s'il n'existe pas), `lib/l10n/app_fr.arb` et `app_en.arb`
- Create: `lib/features/profile/bloc/username/username_cubit.dart`, `lib/features/profile/presentation/widgets/edit_username_sheet.dart`
- Test: `test/features/profile/bloc/username_cubit_test.dart`, `test/features/profile/presentation/edit_username_sheet_test.dart`

**Interfaces:**
- Produces: `UsernameCubit.inputChanged(String)` (délai 400 ms avant la requête, annule la précédente), état `UsernameState{status, reason, suggestions, available}` ; `EditUsernameSheet.show(BuildContext): Future<bool>` (feuille `DonyBottomSheet.show` avec `wrapper` `BlocProvider`, `stickyBottom` bouton « Enregistrer », champ « Identifiant » avec préfixe `@`, aide « 3 à 20 caractères : minuscules, chiffres, point et tiret bas. Modifiable une fois tous les 30 jours. »).

- [ ] **Step 1: Write the failing tests** : `inputChanged("awa")` renvoyant `TAKEN` + suggestions émet l'état d'erreur « Cet identifiant est déjà pris. » avec 3 puces ; toucher une puce remplit le champ et relance la vérification ; deux frappes rapprochées ne font qu'une requête ; `available` active le bouton ; 409 `username-change-too-soon` affiche « Modification possible le <date> » ; widget test à 360 dp en FR et EN : `tester.takeException()` nul, libellé du bouton sur une ligne.
- [ ] **Step 2: Run** `flutter test test/features/profile` ; attendu : FAIL.
- [ ] **Step 3: Implement** (clés l10n FR/EN : `usernameSheetTitle` « Mon identifiant », `usernameFieldLabel`, `usernameTaken`, `usernameAvailable` « Disponible », `usernameTrySuggestions` « Essayez plutôt », `usernameChangeTooSoon`, `usernameSaved`). Contrôleur de texte dans le `State` du contenu, jamais disposé dans `whenComplete`.
- [ ] **Step 4: Run** ; attendu : PASS. Puis `flutter analyze`.
- [ ] **Step 5: Commit** `feat(profile): feuille Mon identifiant`.

### Task 9: Identifiant visible et modifiable dans le profil

**Files:**
- Modify: `lib/features/profile/presentation/widgets/profile_header.dart` (puce `@identifiant` avec icône copier, `Clipboard.setData` puis `DonySnackbar` « Identifiant copié »), `lib/features/profile/presentation/screens/edit_profile_screen.dart` (ligne `_InfoCardRow` « IDENTIFIANT YADONY » + pastille « Modifier » qui ouvre `EditUsernameSheet`, après les champs Identité)
- Test: `test/features/profile/presentation/profile_header_test.dart`, `edit_profile_screen_test.dart`

- [ ] **Step 1: Write the failing tests** : l'en-tête affiche `awa.diallo` ; un tap copie `awa.diallo` dans le presse-papiers (via `SystemChannels.platform` simulé) et affiche le message ; l'écran d'édition affiche la ligne et ouvre la feuille ; la puce est absente si `username` est vide.
- [ ] **Step 2: Run** ; attendu : FAIL.
- [ ] **Step 3: Implement.** La puce suit le style `_Chip` existant (fond `blue50`, texte `blue700`).
- [ ] **Step 4: Run** `flutter test test/features/profile` ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(profile): identifiant visible et modifiable`.

### Task 10: Onglet « Identifiant » dans la feuille d'invitation

**Files:**
- Modify: `lib/features/recipients/presentation/widgets/invite_recipient_sheet.dart`, `lib/features/recipients/bloc/invite_recipient_cubit.dart`, `recipient_picker_sheet.dart` (entrée « Ajouter un destinataire Yadony »), `sent_invitations_section.dart` (puce « EN ATTENTE », texte de la tuile complété), l10n FR/EN
- Test: `test/features/recipients/bloc/invite_recipient_cubit_test.dart`, `invite_recipient_sheet_test.dart`

**Interfaces:**
- Consumes: Task 8 (`lookupUsername`).
- Produces: `InvitationChannel.username` ; `InviteRecipientCubit.selectChannel(username)` ; état `lookup` (`idle|searching|found|notFound`, `foundInitial`). Bouton « Envoyer l'invitation » actif seulement si `lookup == found` pour ce canal.

- [ ] **Step 1: Write the failing tests** : le segment « Identifiant » apparaît après « Numéro » et « E-mail » ; saisie suivie d'une pause de 400 ms déclenche `lookupUsername` ; résultat `found` affiche l'aperçu (« Compte trouvé, prénom commençant par A ») et active le bouton ; 404 affiche « Cet utilisateur n'existe pas. Vérifiez l'identifiant. » et le lien « L'ajouter avec son numéro » bascule sur l'onglet Numéro en gardant le nom saisi ; 429 affiche le texte de quota ; widget test 360 dp FR et EN sans débordement, libellés à taille normale.
- [ ] **Step 2: Run** `flutter test test/features/recipients` ; attendu : FAIL.
- [ ] **Step 3: Implement.** L'identifiant saisi est normalisé (minuscules, sans `@` initial) avant la requête.
- [ ] **Step 4: Run** ; attendu : PASS. Puis `flutter test --coverage` global ≥ 90 %, `flutter analyze`.
- [ ] **Step 5: Commit** puis PR app (cite la PR back), CI verte, fusion. Pas de build sans l'accord du propriétaire.

### Task 11: Notification de refus dans l'app

**Files:**
- Modify: le gestionnaire de notifications existant (chercher `RECIPIENT_INVITATION_ACCEPTED` dans `lib/`), l10n si un texte local est utilisé
- Test: le test de ce gestionnaire

- [ ] **Step 1: Write the failing test** : une notification `RECIPIENT_INVITATION_DECLINED` ouvre `/profile/recipients` et recharge les invitations envoyées (comme pour `ACCEPTED`).
- [ ] **Step 2: Run** ; attendu : FAIL.
- [ ] **Step 3: Implement** en reprenant le traitement de `ACCEPTED`.
- [ ] **Step 4: Run** ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(notifications): refus d'invitation`.

---

# ÉTAPE 2 : invitation liée à un colis (plan à détailler après l'étape 1)

Livrée seulement quand l'étape 1 est en production et que le propriétaire l'a validée. Les décisions déjà prises, que la planification détaillée reprendra :

- `recipient_invitations.bid_id UUID NULL` ; `POST /recipient-invitations` accepte `bidId` (le demandeur est l'expéditeur du colis, statut dans `RecipientChangeService.CHANGEABLE`).
- Un événement `RecipientInvitationAnsweredEvent(invitationId, inviterId, inviteeId, bidId, status)` publié par `addressbook`, écouté par un nouveau listener dans `matching/reception` : à l'acceptation, applique le destinataire au colis comme le fait `RecipientChangeService` (code de retrait et lien de suivi renouvelés, lien `bid_recipient_links` créé `CONFIRMED` car relation de confiance) ; au refus, le colis reste sans destinataire.
- `BidResponse` expose l'état de l'invitation (« en attente », « refusée ») pour l'écran Colis et destinataire ; le panneau « Destinataire à remplacer » reçoit la ligne « @identifiant n'a pas accepté l'invitation ».
- Le code de retrait reste valable pendant l'attente (décision du propriétaire).
- Relance de l'attente : réutiliser le délai de 12 h de `RecipientReplacementService`.
- Fiches assistant FR et EN mises à jour dans la PR back.

---

## Auto-revue

- **Couverture du design :** règles d'identifiant (T1), changement et réservation 30 jours (T2, T3), recherche et quota (T4), invitation par identifiant (T5), refus notifié (T6), fiches (T7), écrans profil et feuilles (T8 à T10), notification app (T11), colis en attente (étape 2).
- **Cohérence des noms :** `UsernamePolicy`, `UsernameService`, `findByUsernameIgnoreCase`, `InvitationChannel.USERNAME`, `UsernameLookupResponse` sont utilisés identiquement dans les tâches suivantes.
- **Hors plan volontaire :** aucune migration des identifiants existants, pas d'écran admin, aucune recherche floue.
