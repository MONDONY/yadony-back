# Story destinataire par identifiant Yadony, étape 1 (Backend)

**Date:** 2026-11-21
**Status:** Étape 1 back complète, app à venir

## Résumé
Un utilisateur peut modifier son identifiant Yadony (`users.username`), le retrouver par recherche exacte et inviter un destinataire avec cet identifiant, sans connaître son numéro ni son e-mail. L'expéditeur est prévenu quand l'invité refuse. Seul le back est livré : les écrans de l'app (pastille de profil, ligne de modification, onglet « Identifiant ») viennent dans la PR app jumelle.

## Fichiers créés
- `src/main/java/com/yadony/api/auth/UsernamePolicy.java` — règles de format (3 à 20 caractères : a-z, 0-9, point, tiret bas).
- `src/main/java/com/yadony/api/auth/UsernameSuggester.java` — suggestions d'identifiants libres.
- `src/main/java/com/yadony/api/auth/UsernameService.java` — changement (une fois tous les 30 jours), disponibilité, réservation de l'ancien identifiant.
- `src/main/java/com/yadony/api/auth/ReleasedUsernameEntity.java`, `ReleasedUsernameRepository.java` — table de réservation des identifiants libérés.
- `src/main/java/com/yadony/api/auth/UsernamePurgeScheduler.java` — purge des réservations de plus de 30 jours.
- `src/main/java/com/yadony/api/auth/dto/ChangeUsernameRequest.java`, `UsernameAvailabilityResponse.java` — DTO des endpoints.
- `src/main/java/com/yadony/api/addressbook/invitation/UsernameLookupService.java` — recherche exacte d'un identifiant.
- `src/main/java/com/yadony/api/addressbook/invitation/LookupRateLimiter.java` — quota partagé recherche + envoi.
- `src/main/java/com/yadony/api/addressbook/invitation/dto/UsernameLookupResponse.java` — réponse de la recherche.
- `src/main/resources/db/migration/V391__username_change_and_released.sql` — colonne, index unique, table `released_usernames`.
- `src/main/resources/assistant/kb/{fr,en}/identifiant-yadony.md` — fiche de l'assistant.
- Tests : `UsernamePolicyTest`, `UsernameSuggesterTest`, `UsernameServiceTest`, `UsernamePurgeSchedulerTest`, `UsernameRepositoryDbTest`, `UsernameMigrationIT`, `AuthControllerUsernameIT`, `UsernameLookupServiceTest`, `LookupRateLimiterTest`, `RecipientInvitationLookupIT`.

## Fichiers modifiés
- `auth/UserEntity.java`, `UserRepository.java` — `username_changed_at`, recherche insensible à la casse.
- `auth/AuthController.java` — endpoints de changement et de disponibilité.
- `config/SecurityConfig.java` — règles d'accès des nouveaux endpoints.
- `addressbook/invitation/RecipientInvitationController.java`, `RecipientInvitationService.java`, `RecipientInvitationRepository.java`, `InvitationChannel.java`, `dto/CreateRecipientInvitationRequest.java` — invitation par `username`, notification de refus.
- `addressbook/invitation/RecipientInvitationNotifications.java`, `notifications/NotificationTexts.java`, `NotificationDeeplink.java`, `NotificationPrefsService.java` — notification `RECIPIENT_INVITATION_DECLINED`.
- `i18n/messages_fr.properties`, `messages_en.properties` — textes des erreurs et notifications.
- `assistant/kb/{fr,en}/destinataire.md`, `ecran-destinataires.md` — mention du choix « Identifiant » et du refus.
- Tests existants adaptés : `RecipientInvitationControllerIntegrationTest`, `RecipientInvitationServiceTest`, `AuthControllerRegisterRaceTest`, `NotificationDeeplinkTest`, `NotificationPrefsServiceTest`, `NotificationTextsTest`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'utilisateur change son identifiant : `PUT /auth/me/username`. `UsernameService` vérifie le format, le délai de 30 jours depuis `username_changed_at`, et que le nom n'est ni pris ni réservé. Il enregistre le nouveau nom et réserve l'ancien dans `released_usernames`.
2. L'app interroge `GET /auth/me/username/availability` pendant la saisie.
3. L'expéditeur cherche un identifiant : `GET /recipient-invitations/lookup?username=`. Réponse explicite si le compte existe, 404 sinon (le texte renvoyé est « Utilisateur introuvable » / « User not found » ; l'app affiche son propre texte). Un compte suspendu ou banni répond 404 comme un inconnu.
4. Il envoie `POST /recipient-invitations` avec `username`. L'invité accepte ou refuse comme pour les canaux numéro et e-mail.
5. Acceptation : la personne entre dans « Mes destinataires » de l'expéditeur, marquée « Dans Yadony », avec son numéro.
6. Refus : l'expéditeur reçoit `RECIPIENT_INVITATION_DECLINED` et l'invitation quitte « Invitations envoyées ».

### Points d'entrée API
- `PUT /auth/me/username` — change l'identifiant du compte connecté (la spec écrit `/users/me/username`, le code expose `/auth/me/username`).
  Même écart pour les deux autres chemins : la spec parle de `/users/me/username/availability` et d'une recherche sous `/users`, le code expose `/auth/me/username/availability` et `/recipient-invitations/lookup`. L'app doit appeler les chemins du code.
- `GET /auth/me/username/availability` — disponibilité et suggestions.
- `GET /recipient-invitations/lookup?username=` — recherche exacte, utilisateur authentifié.
- `POST /recipient-invitations` — champ `username` en plus de numéro et e-mail.

### Entités JPA impliquées
- `UserEntity` → table `users` — `username` (unique insensible à la casse), `username_changed_at`.
- `ReleasedUsernameEntity` → table `released_usernames` — `username` (PK), `released_at`, `former_user_id`.
- `RecipientInvitationEntity` (existante) — canal `USERNAME` ajouté à `InvitationChannel`.

### Logique métier critique
- Un changement par tranche de 30 jours, et l'ancien identifiant reste réservé 30 jours (anti-usurpation).
- Le format (3 à 20 caractères, a-z, 0-9, `.` et `_`) n'exclut pas à lui seul les noms propres : l'identifiant est choisi par l'utilisateur et visible des contreparties. Il refuse en revanche à la création et au changement (jamais à la recherche d'un compte existant) : les mots réservés et leurs équivalents (`admin`, `yadony`, `support`, `modera*`, `offici*`, `staff`, `equipe`, sosies 0/1/3 pour o/i/e) ; toute suite de 7 chiffres ou plus, pour qu'un identifiant ne passe pas pour un numéro de téléphone (422 `username-looks-like-phone`, raison `LOOKS_LIKE_PHONE` côté disponibilité), sauf la forme générée `user<chiffres>`.
- À la finalisation d'un compte supprimé (RGPD), l'identifiant est libéré : réservé 30 jours dans `released_usernames`, et la ligne reçoit un identifiant généré.
- Disponibilité : 120 vérifications par heure et par utilisateur (429 `username-availability-quota`), seau distinct de celui des recherches.
- « Invitations envoyées » montre l'identifiant courant de l'invité seulement une fois l'invitation acceptée ; en attente, le libellé stocké à l'envoi reste affiché (idem dans la notification de refus).
- Recherche et envoi partagent un seul quota de 30 par heure ; un identifiant de format invalide répond 404 sans consommer le quota. Nginx (`api_general`, 600 req/min par IP) s'ajoute mais ne borne pas l'énumération.
- Le pseudo courant d'un invité qui a bloqué l'expéditeur reste masqué.

### Events Spring publiés / écoutés
- Refus d'invitation : pas d'événement, appel direct du dispatcher, comme l'acceptation. `RecipientInvitationService.decline` écrit l'audit log puis appelle `notificationDispatcher.notifyUser(...)` de façon synchrone pour envoyer `RECIPIENT_INVITATION_DECLINED` (type FEED_ONLY, comme `ACCEPTED` : fil d'activité sans push). Seuls `RecipientInvitationCreatedEvent` et `RecipientInvitationRemovedEvent` existent dans `addressbook/invitation`.

### Pièges et points d'attention
- Le quota recherche + envoi vit dans un seau mémoire (`LookupRateLimiter`) : valable pour une seule JVM, à revoir si le back passe en plusieurs instances.
- `UsernamePurgeScheduler` supprime physiquement les lignes de `released_usernames` (table de réservation à durée limitée, aucune donnée à conserver) : si la purge s'arrête, les anciens identifiants restent bloqués.
- Le 404 explicite sur un identifiant inconnu est une décision du propriétaire (texte renvoyé : « Utilisateur introuvable » / « User not found ») : il permet de deviner l'existence d'un identifiant, d'où le quota et l'authentification.
- Index unique sur `lower(username)` (`ux_users_username_lower`), toutes lignes comprises, soft-deleted incluses. La migration V391 demande un contrôle préalable de doublons (requête en commentaire du fichier).
- Course sur l'index unique : une violation est convertie en erreur « identifiant pris », pas en 500.
- Les fiches de l'assistant décrivent le comportement voulu, mais la pastille du profil, la ligne « Identifiant Yadony » et l'onglet « Identifiant » n'existent pas encore dans l'app : elles ne le seront qu'avec la PR app. Les routes des fiches ne citent que `/profile/edit` et `/profile/recipients`, déjà présentes. Les fiches sont lues depuis le jar : effet après déploiement du back.

### Ordre de déploiement
Les fiches de l'assistant (`identifiant-yadony`, `destinataire`, `ecran-destinataires`) sont lues depuis le jar au démarrage et décrivent une interface de l'app (pastille de profil, ligne « Identifiant Yadony », onglet « Identifiant ») livrée dans la PR app. Le back ne doit pas être déployé en production avant la sortie de la version de l'app qui porte cette interface (le déploiement en production appartient au propriétaire). Ne pas retirer les fiches.

## Critères d'acceptation couverts
- [x] Modifier son identifiant une fois tous les 30 jours — `UsernameService`, test `UsernameServiceTest` et `AuthControllerUsernameIT`.
- [x] L'ancien identifiant reste réservé 30 jours — `released_usernames` et purge planifiée.
- [x] Rechercher un destinataire par identifiant — `UsernameLookupService`, `RecipientInvitationLookupIT`.
- [x] Identifiant inconnu : 404 explicite (« Utilisateur introuvable » / « User not found » ; l'app affiche son propre texte).
- [x] Inviter par identifiant — `POST /recipient-invitations` avec `username`.
- [x] Refus : l'expéditeur est notifié et l'invitation disparaît de la liste.
- [x] Acceptation : le destinataire rejoint le carnet, « Dans Yadony », avec son numéro.

## Tests
- Des tests ciblés ont été lancés à chaque tâche (classes listées plus haut). Les tests de la base de connaissances de l'assistant passent (183 tests, 0 rouge : `KnowledgeBase*Test` et `AssistantServiceTest`).
- Suite complète locale (21/11/2026, après fusion de `origin/main`, `./mvnw test jacoco:report`, 1 h 09) : 17 182 tests exécutés, 3 échecs et 99 erreurs, dont 93 erreurs `initdb` de PostgreSQL embarqué (le Mac de développement est limité à 32 segments de mémoire partagée, `kern.sysv.shmmni`, déjà occupés par d'autres applications ; les mêmes classes échouent même lancées seules, y compris `UsernameMigrationIT` qui passait seul auparavant). `CucumberE2ETest` échoue pour la même cause (contexte Spring `e2e` qui démarre aussi PostgreSQL embarqué : `initdb` failed). Restent 3 échecs d'assertion dans `V299V300TripCountryAndTimezoneMigrationTest` (fuseau attendu `Africa/Abidjan`, obtenu `Europe/Paris`) : un test de migration de trajets que cette branche ne touche pas, non rejoué localement faute de PostgreSQL disponible ; son verdict revient à la CI (Linux). Aucun test de cette branche ne ressort rouge hors cette limite d'environnement.
- Couverture JaCoCo : le pourcentage global local (45 % lignes) est faussé par ces erreurs d'environnement (contextes Postgres jamais démarrés) et n'est pas utilisable ; à relever sur le rapport de la CI. Couverture des classes introduites par cette branche (lignes, rapport local) : `UsernamePolicy`, `UsernameService`, `UsernameLookupService`, `LookupRateLimiter`, `UsernamePurgeScheduler`, `UsernameRateLimitConfig`, `ReleasedUsernameEntity` et les DTO : 100 % ; `UsernameSuggester` : 91 %.
- Tests ajoutés : voir la liste dans « Fichiers créés » et « Fichiers modifiés ».

## Décisions techniques
- Identifiant modifiable plutôt qu'un second champ « pseudo public » : un seul identifiant, déjà unique et déjà présent sur les comptes.
- Réservation de 30 jours de l'ancien nom en table dédiée plutôt qu'un historique dans `users` : purge simple, aucune donnée personnelle conservée.
- Quota partagé recherche + envoi en mémoire plutôt que Redis (hors périmètre MVP, Caffeine seulement).
- 404 explicite à l'inconnu plutôt qu'une réponse neutre : choix du propriétaire, atténué par le quota.
- Notification de refus FEED_ONLY, comme l'acceptation : pas de push pour un refus.
