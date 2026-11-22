# Destinataire d'un colis par identifiant (étape 2) : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** L'expéditeur désigne le destinataire d'un colis par son identifiant Yadony, à la création du colis et au changement de destinataire, sans connaître son numéro.

**Architecture:** `bids.recipient_user_id` mémorise le compte désigné. À l'acceptation du colis, `ReceptionLinker` lie ce compte (au lieu de chercher par numéro) et le circuit existant de confirmation, refus et remplacement s'applique. À la confirmation, le nom public du compte remplace le libellé `@identifiant` et son numéro est copié s'il ne l'a pas masqué.

**Tech Stack:** Spring Boot 3.4 / Java 21 / PostgreSQL + Flyway / JUnit 5 + MockMvc (back) ; Flutter, BLoC, GoRouter, `DonyBottomSheet` (app).

**Spec:** `docs/superpowers/specs/2026-11-22-destinataire-par-identifiant-colis-design.md` (étape 1 : `2026-11-21-destinataire-par-pseudo-design.md`). Maquettes de l'étape 1 : https://claude.ai/artifact/Q3MupK5SMqrZbHi7ZhVm2Y

## Global Constraints

- Git : jamais sur `main`, branche `feature/recipient-by-username-bid` dans un worktree par dépôt, aucune ligne `Co-Authored-By`, PR back avant PR app, fusion après CI verte (autorisée), aucune soumission Apple, aucun build sans accord.
- Migration : `V(n+1)` libre au moment de coder (dernier vu : V391 ; `feature/telephone-declare` en prévoit un aussi). Jamais modifier une migration existante.
- Erreurs RFC 7807 localisées (`YadonyBusinessException.localized`), clés FR et EN ; `audit_log` pour la désignation, jamais de numéro en clair ; entre packages, événements Spring ou interface de `common/`, jamais d'injection de service croisée.
- Exclusivité : un colis reçoit soit nom + numéro, soit `recipientUsername`, jamais les deux (422 `recipient-mode-conflict`) ; aucun des deux reste permis comme aujourd'hui.
- Désignation : passe par `UsernameLookupService.beginLookup` (format, puis seau partagé de 30 par heure, 429 `recipient-lookup-quota`) puis `resolveVisible` ; introuvable, supprimé, suspendu ou bloqué : 404 `user-not-found` ; soi-même : 422 `recipient-invitation-self` ; voyageur du trajet : 422 `recipient-is-traveler`.
- Le numéro n'est jamais copié avant la confirmation, ni si le compte a masqué son numéro (`hide_phone_number`).
- La demande négociée (`ThreadAcceptedBidListener`, `NegotiationService`) reste inchangée.
- Fiches de l'assistant FR et EN (même `id`) mises à jour dans la PR back ; les routes citées existent dans `dony_app/lib/app/router.dart` sur `origin/main`.
- App : BLoC et GoRouter, pas de `setState`, boutons des feuilles dans `stickyBottom`, libellés de bouton sur une ligne FR et EN à 360 dp, pas de tiret cadratin ni d'emoji, aucun dégradé, analytics pour toute nouvelle action et tout échec (jamais l'identifiant), l'app tolère l'ancien back (la désignation n'est offerte qu'après une recherche réussie).
- Tests ciblés par tâche ; suite complète une fois avant la PR et en CI ; couverture ≥ 90 % ; jamais deux `mvnw` ni deux `flutter` en parallèle.

## Review Focus

- Un colis créé avec `recipientUsername` sur un back qui ignorerait le champ : l'app ne doit jamais l'envoyer sans recherche réussie préalable (405 → désignation masquée).
- Désigner son propre compte, le voyageur du trajet, un compte bloqué, supprimé ou suspendu : refusé avant toute écriture.
- Destinataire qui masque son numéro : jamais de numéro dans le colis (expéditeur comme voyageur) ; destinataire qui supprime son compte ou devient inéligible avant l'acceptation : pas de lien, colis remplaçable.
- Changement de destinataire d'un numéro vers un identifiant et inversement : l'ancien lien et l'ancien accès sont retirés, un seul des deux champs (`recipient_user_id`, `recipient_phone`) décrit le destinataire jusqu'à la confirmation.
- Double confirmation ou confirmation après refus : pas de numéro copié deux fois, pas d'écrasement d'un destinataire plus récent.

---

# BACK (`dony-back`)

### Task 1: Compte désigné sur le colis et résolution partagée

**Files:**
- Create: `src/main/resources/db/migration/V392__bid_recipient_user.sql` (prendre le numéro libre)
- Create: `src/main/java/com/yadony/api/common/RecipientAccountResolver.java`
- Create: `src/main/java/com/yadony/api/addressbook/invitation/UsernameRecipientResolver.java`
- Modify: `src/main/java/com/yadony/api/matching/BidEntity.java`
- Test: `UsernameRecipientResolverTest.java`, `BidRecipientUserMigrationIT.java`

**Interfaces:**
- Produces:
  - Colonne `bids.recipient_user_id UUID NULL` + index `ix_bids_recipient_user_id`.
  - `BidEntity.getRecipientUserId(): UUID` / `setRecipientUserId(UUID)`.
  - `interface RecipientAccountResolver { RecipientAccount resolve(UUID callerUserId, String rawUsername); }` et `record RecipientAccount(UUID userId, String username)` dans `common/`. Lance les `YadonyBusinessException` de la contrainte « Désignation » (hors voyageur du trajet, contrôlé par le colis).
  - `UsernameRecipientResolver` (addressbook) l'implémente avec `UsernameLookupService.beginLookup` puis `resolveVisible`.

- [ ] **Step 1: Write the failing tests.** `UsernameRecipientResolverTest` : compte trouvé renvoie `RecipientAccount(id, "awa.diallo")` ; « AWA.Diallo » trouve le même ; introuvable, bloqué, suspendu : `user-not-found` 404 ; soi-même : 422 `recipient-invitation-self` ; 31e appel dans l'heure : 429 `recipient-lookup-quota` (même seau que la recherche) ; un format invalide ne consomme pas le quota. `BidRecipientUserMigrationIT` (Postgres embarqué, comme `UsernameMigrationIT`) : la migration s'applique sur base vide, la colonne accepte NULL et un UUID.
- [ ] **Step 2: Run** `./mvnw test -Dtest=UsernameRecipientResolverTest,BidRecipientUserMigrationIT` ; attendu : échec de compilation.
- [ ] **Step 3: Implement** les trois éléments. Pas de clé étrangère (même choix que `recipient_id` des invitations), index simple.
- [ ] **Step 4: Run** les mêmes tests ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(matching): compte destinataire désigné sur le colis`.

### Task 2: Désignation à la création du colis

**Files:**
- Create: `src/main/java/com/yadony/api/matching/BidRecipientDesignation.java`
- Modify: `dto/BidCheckoutRequest.java`, `dto/BidNegotiationStartRequest.java` (et le DTO du chemin `BidService` ~ligne 404 s'il est encore appelé par l'app : le vérifier dans `dony_app/lib/features/matching/data/datasources/bid_remote_datasource.dart`), `BidCheckoutService.java` (~l. 236), `BidNegotiationService.java` (~l. 209), `BidService.java` (~l. 404), messages FR/EN
- Test: `BidRecipientDesignationTest.java`, `BidCheckoutRecipientUsernameIT.java`

**Interfaces:**
- Consumes: Task 1 (`RecipientAccountResolver`, `BidEntity.setRecipientUserId`).
- Produces:
  - Champ facultatif `String recipientUsername` sur `BidCheckoutRequest` et `BidNegotiationStartRequest` ; `recipientName` / `recipientPhone` de `BidNegotiationStartRequest` perdent `@NotBlank` (la règle devient : nom + numéro, ou `recipientUsername`, vérifiée par le service).
  - `BidRecipientDesignation.apply(BidEntity bid, UUID senderId, UUID travelerId, String name, String phone, String username)` : valide l'exclusivité (422 `recipient-mode-conflict`), le voyageur (422 `recipient-is-traveler`) et pose soit `recipientName` / `recipientPhone`, soit `recipientUserId` avec `recipientName = "@" + username` et `recipientPhone = null`. Écrit `audit_log` `RECIPIENT_DESIGNATED_BY_USERNAME` (id du colis, id du compte).

- [ ] **Step 1: Write the failing tests.** `BidRecipientDesignationTest` : nom + numéro seuls inchangés ; `username` seul pose `recipientUserId`, `recipientName == "@awa.diallo"`, `recipientPhone == null` ; username avec nom ou numéro : 422 `recipient-mode-conflict` sans écriture ; username égal au voyageur du trajet : 422 `recipient-is-traveler` ; aucun des trois : colis sans destinataire comme aujourd'hui ; la demande négociée sans nom ni numéro mais avec username passe la validation. `BidCheckoutRecipientUsernameIT` : `POST` du checkout avec `recipientUsername` crée le colis `AWAITING_PAYMENT` avec les bons champs et une ligne d'audit ; compte inconnu : 404 et aucun colis créé ; quota de recherche épuisé : 429.
- [ ] **Step 2: Run** `./mvnw test -Dtest=BidRecipientDesignationTest,BidCheckoutRecipientUsernameIT` ; attendu : FAIL.
- [ ] **Step 3: Implement.** Appeler `BidRecipientDesignation.apply` à la place des deux `setRecipientName` / `setRecipientPhone` des trois services ; le calcul du montant et le paiement ne changent pas. Clés FR/EN : `recipient-mode-conflict`, `recipient-is-traveler`.
- [ ] **Step 4: Run** ces tests plus les tests existants de `BidCheckoutService`, `BidNegotiationService` et `BidService` ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(matching): désigner le destinataire par identifiant à la création du colis`.

### Task 3: Changement de destinataire par identifiant

**Files:**
- Modify: `matching/reception/dto/ChangeRecipientRequest.java`, `matching/reception/RecipientChangeService.java`, `RecipientChangeController.java` si la validation du corps y vit
- Test: `RecipientChangeServiceTest.java`, `RecipientChangeUsernameIT.java`

**Interfaces:**
- Consumes: Task 1 et Task 2 (`BidRecipientDesignation`).
- Produces: `ChangeRecipientRequest(String recipientName, String recipientPhone, String recipientUsername)` (les deux premiers perdent `@NotBlank`, règle d'exclusivité de la contrainte) ; en mode identifiant, `changeRecipient` applique les mêmes effets qu'un changement de numéro (lien de suivi révoqué et renouvelé, code de retrait régénéré et masqué de la page publique, ancien lien soft-deleted) avec `recipientUserId` renseigné, `recipientPhone` nul, libellé `@identifiant`, puis publie `BidRecipientChangedEvent`. Passer d'un identifiant à un numéro vide `recipientUserId`.

- [ ] **Step 1: Write the failing tests.** Numéro vers identifiant : `recipientUserId` posé, téléphone nul, ancien jeton révoqué, code régénéré, événement publié, audit ; identifiant vers numéro : `recipientUserId` nul ; même identifiant que l'actuel : seul le libellé est conservé, aucun jeton renouvelé ; colis hors `CHANGEABLE` : 409 existant ; non-expéditeur : 403 existant ; voyageur, soi-même, inconnu, bloqué : mêmes erreurs que la création ; les tests existants du changement par numéro restent verts.
- [ ] **Step 2: Run** `./mvnw test -Dtest=RecipientChangeServiceTest,RecipientChangeUsernameIT` ; attendu : FAIL.
- [ ] **Step 3: Implement** en réutilisant `BidRecipientDesignation` (qui pose les champs) et en conservant tout le reste de la méthode.
- [ ] **Step 4: Run** ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(matching): changer le destinataire d'un colis par identifiant`.

### Task 4: Rattachement et confirmation

**Files:**
- Modify: `matching/reception/ReceptionLinker.java` (`linkIfPossible`), `matching/reception/ReceptionService.java` (`confirm`)
- Test: `ReceptionLinkerTest.java`, `ReceptionServiceTest.java`, `ReceptionByUsernameIT.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: `linkIfPossible` utilise `bid.getRecipientUserId()` quand il est renseigné (pas de recherche Firebase) avec les mêmes garde-fous (soi-même, voyageur, bloqué, confiance, mode recette), le même lien `CONFIRMED` / `PENDING` et le même push ; `confirm` renomme le colis (`recipientName = publicDisplayName()` du compte) et copie le numéro E.164 du compte dans `recipientPhone` **seulement si** `hide_phone_number` est faux et que le numéro est connu (lu comme `catchUp` lit `firebaseContact.getContact(uid).phoneNumber()`), sans toucher `recipientUserId` ; `decline` reste inchangé.

- [ ] **Step 1: Write the failing tests.** Linker : colis accepté avec `recipientUserId` crée le lien `PENDING` pour ce compte et pousse, sans appel `findUidByPhone` ; destinataire de confiance : lien `CONFIRMED` ; compte devenu bloqué, supprimé ou voyageur : aucun lien. Confirm : nom public posé et numéro copié quand non masqué ; numéro masqué : `recipientPhone` reste nul ; compte sans numéro : idem ; deuxième confirmation : aucun écrasement ; confirmation après un changement de destinataire : refusée par le lien retiré (comportement existant). Décliner puis changer vers un nouvel identifiant : un nouveau lien est créé. Dans `BidResponse` vu par l'expéditeur avant confirmation : `recipientName == "@awa.diallo"`, `recipientPhone == null`, `recipientAppStatus == "PENDING"` (la valeur exacte vient de l'enum existant) ; vu par le voyageur après la remise : pas de numéro tant que le destinataire est en attente.
- [ ] **Step 2: Run** `./mvnw test -Dtest=ReceptionLinkerTest,ReceptionServiceTest,ReceptionByUsernameIT` ; attendu : FAIL.
- [ ] **Step 3: Implement.** Lire `ReceptionLinker.linkIfPossible` entier avant de modifier : seule la source du compte destinataire change.
- [ ] **Step 4: Run** ces tests et ceux du package `matching/reception` ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(reception): rattacher et confirmer un destinataire désigné par identifiant`.

### Task 5: Fiches de l'assistant, story et clôture back

**Files:**
- Modify: `src/main/resources/assistant/kb/fr/destinataire.md` et `en/…` (même `id`), `ecran-fiche-colis.md` ou la fiche d'envoi concernée (les trouver avec `grep -l destinataire`), `identifiant-yadony.md` FR et EN
- Create: `docs/stories-done/story-recipient-by-username-bid-step2.md` (modèle du `CLAUDE.md` du back)

- [ ] **Step 1:** mettre à jour les fiches (choisir « Un utilisateur Yadony » à l'envoi et au changement de destinataire ; l'attente commence quand le voyageur accepte le colis ; le destinataire confirme ou refuse ; le numéro n'apparaît qu'après sa confirmation et s'il ne l'a pas masqué ; au refus, l'expéditeur désigne un autre destinataire). Respecter la limite de longueur du corps d'écran de `KnowledgeBaseLoader`.
- [ ] **Step 2: Run** les tests de la base de connaissances (`KnowledgeBase*Test`, `AssistantServiceTest`) ; attendu : PASS.
- [ ] **Step 3:** écrire la story (flux, endpoints modifiés, colonne, décisions, pièges : exclusivité, numéro copié à la confirmation seulement, ordre de déploiement avec l'app).
- [ ] **Step 4: Run** `./mvnw test jacoco:report` une fois (suite complète) ; noter le résultat dans la story. En cas d'erreurs `initdb` de PostgreSQL embarqué sur le poste, les noter telles quelles et laisser la CI trancher.
- [ ] **Step 5: Commit** `docs(assistant): fiches et story du destinataire par identifiant (colis)`, ouvrir la PR back, attendre la CI verte, fusionner.

---

# APP (`dony_app`, après fusion de la PR back ; la PR cite le numéro de la PR back)

### Task 6: Données et événements

**Files:**
- Modify: `lib/features/matching/data/datasources/bid_remote_datasource.dart` (trois méthodes : création, checkout, négociation, et le changement de destinataire ~l. 297), `bid_negotiation_remote_datasource.dart`, les repositories, `lib/features/matching/bloc/bid_event.dart`, `bid_negotiation_event.dart`, `bid_bloc.dart`, `bid_negotiation_bloc.dart`
- Test: les tests existants de ces fichiers plus `bid_recipient_username_test.dart`

**Interfaces:**
- Produces: les paramètres `recipientName` et `recipientPhone` deviennent facultatifs (`String?`) et `String? recipientUsername` s'ajoute partout où le destinataire voyage ; le corps JSON n'envoie `recipientUsername` que s'il est renseigné et n'envoie plus nom et numéro dans ce cas ; les événements gardent un constructeur de compatibilité.

- [ ] **Step 1: Write the failing tests** : avec `recipientUsername`, le corps contient `recipientUsername` et ni `recipientName` ni `recipientPhone` (assertion sur la `Map`) ; sans, le corps est identique à aujourd'hui ; les événements et blocs transmettent le champ.
- [ ] **Step 2: Run** `flutter test test/features/matching` ; attendu : FAIL.
- [ ] **Step 3: Implement** sans changer les appels existants.
- [ ] **Step 4: Run** ; attendu : PASS, puis `flutter analyze`.
- [ ] **Step 5: Commit** `feat(matching): destinataire désigné par identifiant dans les requêtes de colis`.

### Task 7: Choisir un utilisateur Yadony pour un colis

**Files:**
- Modify: `lib/features/recipients/presentation/widgets/recipient_section.dart`, `recipient_picker_sheet.dart`, `lib/features/matching/presentation/widgets/create_bid_bottom_sheet.dart` (~l. 1664, `RecipientSection`), `lib/features/matching/presentation/widgets/bid_detail/recipient_change_sheet.dart`, `lib/l10n/app_fr.arb` et `app_en.arb` (+ générateur et catalogue formaté)
- Create: `lib/features/recipients/presentation/widgets/designate_yadony_user_sheet.dart`, `lib/features/recipients/bloc/designate_yadony_user_cubit.dart`
- Test: `designate_yadony_user_cubit_test.dart`, `designate_yadony_user_sheet_test.dart`, les tests de `RecipientSection`, `create_bid_bottom_sheet`, `recipient_change_sheet`

**Interfaces:**
- Consumes: Task 6, la recherche de l'étape 1 (`RecipientInvitationRepository.lookupUsername`, `usernameChannelUnsupported`, `UsernameLookup{username, initial}`).
- Produces: `DesignateYadonyUserSheet.show(BuildContext): Future<DesignatedUser?>` avec `DesignatedUser(String username)` ; le cubit réutilise la logique de l'étape 1 (recherche à la validation du clavier ou à la perte de focus, un seul appel par saisie, format local, 405 ou 404 sans `user-not-found` ⇒ non pris en charge et drapeau de session) ; `RecipientSection` gagne un choix « Un utilisateur Yadony » à côté de « Choisir un destinataire » et de la saisie manuelle : l'état sélectionné affiche `@identifiant` et « en attente de confirmation » et fournit `recipientUsername` au formulaire à la place du nom et du numéro ; le sélecteur d'un envoi n'offre le choix que si `usernameChannelUnsupported` est faux.

- [ ] **Step 1: Write the failing tests** : la feuille cherche à la validation seulement, affiche « Compte trouvé, prénom commençant par X » et « @identifiant », « Choisir » activé seulement si trouvé et rend `DesignatedUser` ; introuvable, soi-même et quota : messages de l'étape 1 ; route absente : le choix « Un utilisateur Yadony » disparaît ; `RecipientSection` : après sélection, `recipientUsername` est exposé et nom et numéro sont ignorés à la soumission ; le formulaire de création n'exige plus nom et numéro quand un identifiant est choisi ; `recipient_change_sheet` envoie `recipientUsername` ; textes FR et EN à 360 dp sur une ligne ; analytics `recipient_designated_by_username` (et son échec avec `error_code`), jamais l'identifiant.
- [ ] **Step 2: Run** `flutter test test/features/recipients test/features/matching` ; attendu : FAIL.
- [ ] **Step 3: Implement.** Extraire des composants de l'étape 1 (`invite_recipient_cubit` pour la logique de recherche, `invite_recipient_sheet` pour l'aperçu) ce qui est partagé, sans changer leur comportement ni leurs tests.
- [ ] **Step 4: Run** ces tests, `flutter analyze`, `dart format --output=none --set-exit-if-changed lib/ test/ tool/`, `dart run tool/check_hardcoded_strings.dart` ; attendu : tout propre.
- [ ] **Step 5: Commit** `feat(matching): choisir un utilisateur Yadony comme destinataire d'un colis`.

### Task 8: États du destinataire sur l'écran du colis

**Files:**
- Modify: `lib/features/matching/presentation/widgets/bid_detail/colis_destinataire_card.dart`, `lib/features/matching/presentation/widgets/recipient_contact/recipient_declined_panel.dart` (seulement si un texte doit mentionner l'identifiant), `lib/l10n/*`
- Test: `colis_destinataire_card_test.dart`

**Interfaces:**
- Consumes: `BidModel.recipientName` (libellé `@identifiant` avant confirmation), `recipientPhone` (nul tant que non confirmé), `recipientAppStatus` existant.

- [ ] **Step 1: Write the failing tests** : destinataire `@awa.diallo` avec statut en attente : la carte montre « @awa.diallo » et une pastille « En attente de confirmation » sans ligne téléphone ; confirmé avec numéro : nom public et numéro ; confirmé sans numéro (masqué) : nom public seul, pas de ligne vide ; refusé : le panneau existant « Destinataire à remplacer » reste affiché ; voyageur : « Destinataire non confirmé » tant que le lien est en attente ; 360 dp FR et EN sans débordement.
- [ ] **Step 2: Run** `flutter test test/features/matching/presentation` ; attendu : FAIL.
- [ ] **Step 3: Implement** les états sans toucher au panneau de refus.
- [ ] **Step 4: Run** ces tests ; attendu : PASS.
- [ ] **Step 5: Commit** `feat(matching): états du destinataire désigné par identifiant`.

### Task 9: Clôture app

- [ ] **Step 1: Run** la suite complète `flutter test --coverage` une fois (en arrière-plan, lire la fin du fichier de sortie) ; attendu : tout vert ; relever la couverture globale (≥ 90 %).
- [ ] **Step 2: Run** `flutter analyze`, `dart format --output=none --set-exit-if-changed lib/ test/ tool/`, `dart run tool/check_hardcoded_strings.dart` ; attendu : propres.
- [ ] **Step 3:** ouvrir la PR app qui cite la PR back, rappelle l'ordre (back déployé sur staging avant la fusion de l'app, qui envoie un build de recette) et liste les décisions restantes ; attendre la CI verte et relire chaque contrôle ; fusionner seulement si le back est déployé sur staging ou sur accord explicite.

---

## Auto-revue

- **Couverture du design :** colonne et résolution (T1), création (T2), changement (T3), rattachement et confirmation avec copie conditionnelle du numéro (T4), fiches et story (T5), requêtes (T6), choix dans les deux parcours avec garde ancien back (T7), états du colis (T8), clôture (T9). La demande négociée est exclue.
- **Cohérence des noms :** `recipient_user_id`, `RecipientAccountResolver` / `RecipientAccount`, `BidRecipientDesignation.apply`, `recipientUsername`, `DesignatedUser` sont utilisés à l'identique dans les tâches suivantes.
- **Point d'attention :** les trois chemins de création du back (`BidService`, `BidCheckoutService`, `BidNegotiationService`) et les trois méthodes du datasource de l'app doivent être alignés ; T2 et T6 commencent par vérifier lesquels l'app appelle réellement.
