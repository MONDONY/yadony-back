# Support : visibilité du fil et pièces jointes images

Date : 2026-09-06

Suite de `2026-09-05-support-messaging.md`, qui a livré le canal de support (tickets,
messages, panel admin). Ce document ne remplace pas le précédent : il en corrige deux
défauts d'usage constatés une fois la feature écrite.

## Problème

**Le fil support est introuvable.** `/support` n'est atteignable que par la FAQ
(`faq_screen.dart:491`), une tuile enfouie du profil (`profile_sections.dart:389`) et les
écrans de litige. L'entrée « Aide et support » du menu Activités
(`activites_menu_sheet.dart:184`) pointe même vers `/profile/help/faq`, pas vers les
tickets. Un utilisateur qui a ouvert un ticket doit donc redescendre deux à trois niveaux
pour lire la réponse.

**Le support ne notifie personne.** La seule alerte du package est un message Telegram
vers les admins à la création (`SupportTicketService.java:241`). Quand un admin répond,
l'utilisateur n'apprend rien : aucun FCM n'est émis. C'est le défaut le plus grave des
deux, parce qu'aucun placement, aussi visible soit-il, ne rattrape une réponse dont
l'utilisateur ignore l'existence.

**Le fil est en texte seul.** Un litige colis se règle avec une photo. Aujourd'hui
l'utilisateur doit décrire un dommage avec des mots, et le support ne peut pas renvoyer de
capture annotée.

## Décisions validées

- Les discussions support vivent dans l'onglet **Messages**, pas dans le profil.
- Une **seule ligne épinglée** « Support yadony » en tête de liste, qui ouvre l'écran
  `/support` existant. Pas une ligne par ticket : cinq tickets ouverts ne doivent pas
  noyer les vraies conversations.
- Pastille de **non-lu réel**, adossée à une colonne dédiée. Le compteur dérivé du statut
  `WAITING_USER` a été écarté : il resterait allumé après lecture tant que l'utilisateur
  n'a pas répondu, ce qui apprend à l'ignorer.
- Pièces jointes **dans les deux sens**, utilisateur et admin.
- **Jusqu'à quatre images par message**, texte facultatif dès qu'une image est jointe.
- Images servies par **URL présignée courte**, jamais par URL publique. Une photo de
  justificatif ou de relevé bancaire n'a pas à être devinable par quiconque connaît la
  clé d'objet.
- Purge des images à la suppression de compte, via le `deleteByPrefix` déjà en place pour
  le RGPD.

## Lot 1 — Backend

### Migration V245

Une seule migration couvre les deux sujets.

`support_tickets` reçoit `user_last_read_at TIMESTAMP NULL`. Null signifie « jamais
ouvert », donc tous les messages admin comptent comme non lus.

Nouvelle table `support_message_attachments` :

| Colonne | Type | Note |
|---|---|---|
| `id` | UUID | PK, `BaseEntity` |
| `message_id` | UUID | FK vers `support_messages`, NOT NULL |
| `object_key` | TEXT | clé R2, NOT NULL |
| `content_type` | VARCHAR(100) | NOT NULL |
| `size_bytes` | BIGINT | NOT NULL |
| `created_at` / `updated_at` / `deleted_at` | TIMESTAMP | `BaseEntity` |

Index sur `(message_id)`.

**Le numéro V245 est à revalider contre `origin/main` juste avant le commit, jamais contre
la branche.** C'est exactement le piège qui a failli casser la production au lot
précédent : `main` portait déjà un `V243` appliqué en base pendant que la branche
numérotait en `V241`, et le test de migration ne pouvait pas le voir puisqu'il part d'une
base vide. La branche pawaPay occupe par ailleurs V241 à V244 et devra elle aussi se
renuméroter.

### Non-lu

`unreadCount` par ticket = nombre de `support_messages` d'`author_type = ADMIN` dont
`created_at > user_last_read_at` (ou tous, si la colonne est nulle). Exposé dans
`SupportTicketResponse`.

Deux endpoints s'ajoutent :

- `POST /api/v1/support/tickets/{ticketId}/read` — pose `user_last_read_at = now()`.
  Idempotent. Appelé à l'ouverture du fil. Ownership vérifiée : ticket d'autrui → 404,
  jamais 403, conformément à la règle posée au lot précédent.
- `GET /api/v1/support/unread-count` — rend `{ "count": n }`, somme des non-lus de tous
  les tickets de l'appelant. Sert le badge de l'onglet sans charger la liste.

Marquer lu ne change **pas** le statut du ticket. Lire n'est pas répondre : un ticket
`WAITING_USER` le reste jusqu'à ce que l'utilisateur écrive.

### Pièces jointes

Upload en deux temps, sur le patron déjà éprouvé de `StorageController`.

- `POST /api/v1/support/attachments` — multipart, un fichier, range sous
  `support/{userId}/{timestamp}_{uuid}` et rend `{ key, url }`.
- `POST /api/v1/admin/support/attachments` — idem sous `support/admin/{adminId}/`, gardé
  par `SUPPORT_TICKET_MANAGE`.

L'upload n'est volontairement **pas** rattaché à un ticket : c'est ce qui permet de joindre
une image au tout premier message, quand le ticket n'existe pas encore.

Les clés obtenues sont ensuite passées à la création :

- `CreateSupportTicketRequest` reçoit `attachmentKeys: List<String>` (0 à 4).
- `CreateSupportMessageRequest` reçoit le même champ, et `content` passe de obligatoire à
  « non vide **ou** au moins une clé ». La validation croisée vit dans le service, pas
  dans une annotation Bean Validation : elle porte sur deux champs à la fois.

À l'écriture du message, le service vérifie que chaque clé appartient bien à l'appelant
(préfixe `support/{userId}/` pour un utilisateur, `support/admin/` pour un admin) avant de
créer les lignes d'attachement. Sans ce contrôle, un utilisateur pourrait joindre à son
message la clé d'un fichier d'autrui.

`StorageService` valide déjà 10 Mo, JPEG/PNG/WebP et les magic bytes : rien à réécrire,
`support/` est simplement ajouté à `ALLOWED_PREFIXES`.

En lecture, `SupportMessageResponse` porte une liste d'attachements avec une URL
présignée d'une heure, générée à la volée. La clé brute ne sort jamais du backend.

### Purge des orphelins

Un fichier uploadé puis abandonné (l'utilisateur ferme l'app avant d'envoyer) reste sur R2
sans ligne d'attachement. Un scheduler quotidien supprime les objets sous `support/`
créés il y a plus de 24 h et non référencés, sur le modèle de `BidPhotoCleanupScheduler`.
Idempotent, comme tout scheduler du projet.

### Notification de réponse

`support/` publie `SupportMessageCreatedEvent` (ticketId, messageId, ownerUserId,
authorType). `notifications/` l'écoute et, quand l'auteur est un admin, envoie un FCM au
propriétaire du ticket via `NotificationDispatcher`. Cross-package par événement Spring,
jamais par injection directe.

Le listener est `@TransactionalEventListener(phase = AFTER_COMMIT)` : notifier avant le
commit exposerait un push pointant vers un message que la base n'a pas encore.

Le deep link mène à `/support/tickets/{id}`. Push seulement, pas de SMS : une réponse du
support n'a pas la criticité d'un événement de livraison.

### Audit

`audit_log` reçoit une entrée pour l'ajout d'une pièce jointe par un admin (qui, quel
ticket, quelle clé). L'upload utilisateur n'est pas audité : le message lui-même en fait
foi.

## Lot 2 — Application Flutter

**Ligne épinglée.** L'écran de liste des conversations affiche en tête une entrée
« Support yadony », visuellement distincte (logo à la place de l'avatar, libellé fixe),
non déplaçable, qui pousse vers `/support`. L'aperçu montre le dernier message reçu, tous
tickets confondus. Elle s'affiche même sans aucun ticket, avec un aperçu d'invitation :
c'est là tout l'intérêt, découvrir le canal avant d'en avoir besoin.

**Badge.** `main_shell.dart:440` alimente aujourd'hui le badge de l'onglet Messages depuis
`totalUnreadStream()` (Firestore). Une seconde source REST s'y ajoute et le badge devient
la somme des deux. Les deux sources restent séparées dans le code : pas de couche
d'abstraction commune, juste une addition. Le compteur support se rafraîchit à
l'ouverture de l'onglet et à la réception d'un push.

**Marquage lu.** `SupportTicketDetailScreen` appelle `POST .../read` à l'ouverture, puis
émet un événement qui décrémente le compteur local. Pas de `setState` : tout passe par
`SupportBloc`.

**Envoi d'images.** Le sélecteur et la compression existent déjà pour les photos de
demande (`package_request_photo_upload.dart`, `bid_photo_upload.dart`) : ils sont
réutilisés plutôt que réécrits. Le champ de saisie gagne un bouton trombone, les images
choisies s'affichent en vignettes au-dessus du champ avec leur état (envoi, prêt, échec).
Le bouton d'envoi s'active quand deux conditions sont réunies : il y a du texte **ou** au
moins une image prête, **et** plus aucun upload n'est en cours. Une vignette en échec ne
bloque pas l'envoi, elle est retirée du message.

**Rendu.** Les images d'un message s'affichent en grille dans la bulle, et un tap ouvre la
visionneuse plein écran déjà utilisée ailleurs dans l'app.

**Un ticket résolu reste verrouillé** : ni texte ni image. Le refus est appliqué avant
tout appel réseau, et le backend le refait de toute façon.

## Lot 3 — Back-office admin

Le fil `SupportTicketThread.vue` affiche les images des messages en vignettes, avec
ouverture en grand. Un bouton d'ajout d'images accompagne le champ de réponse, soumis aux
mêmes règles que côté app : quatre au maximum, texte facultatif si image présente.

L'ajout est gardé par `SUPPORT_TICKET_MANAGE` et n'apparaît que sur un ticket assigné à
l'admin courant, comme le champ de réponse actuel.

**Les composants Vue doivent être testés dans le même lot.** Le seuil de couverture de ce
dépôt est global : une feature livrée sans tests de composants fait rougir tout le build,
pas seulement sa propre ligne du rapport. C'est ce qui a bloqué la PR précédente à
85,92 % de branches sous un seuil de 90 %.

## Hors périmètre

- Pas de temps réel : le fil se rafraîchit à l'ouverture et au push, pas par websocket.
- Pas de pièces jointes non-image (PDF, vidéo). Images seulement.
- Pas de pagination des messages d'un ticket : les fils support restent courts.
- Pas de réouverture d'un ticket résolu, décision inchangée.
- Pas de SMS de repli sur la réponse support.

## Tests

Backend : test de migration V245 sur base éphémère, tests unitaires du calcul de
non-lus (colonne nulle, message admin antérieur et postérieur, message utilisateur ignoré),
de la validation croisée contenu/pièces jointes, et du contrôle de propriété des clés.
Tests d'intégration MockMvc sur les quatre nouveaux endpoints, dont les cas de refus.
Test de publication et de réception de `SupportMessageCreatedEvent`, y compris le cas
« auteur utilisateur » qui ne doit rien notifier. Couverture ≥ 90 %.

Flutter : tests de bloc sur le marquage lu, l'agrégation du compteur et le cycle
d'upload (succès, échec, envoi bloqué pendant l'upload) ; tests de widget sur la ligne
épinglée, l'état verrouillé d'un ticket résolu et l'activation du bouton d'envoi.

Admin : tests de composant sur le rendu des vignettes, le gating par permission et le
plafond de quatre images.

## Pièges connus

- **Numéro de migration** : le figer contre `origin/main` au dernier moment. Le test de
  migration part d'une base vide et ne verra jamais un conflit d'ordre avec la production.
- **`permissionCoverage.spec` du dépôt admin** remonte l'arborescence pour trouver un
  dossier `dony-back` et tombe sur le checkout principal plutôt que sur le worktree. Il
  échoue en local sans que rien ne soit cassé, et il est passé en CI.
- **Rien de ce qui précède n'est encore déployé.** Le lot support fusionné sur `main` n'a
  jamais tourné ailleurs qu'en test : le déploiement en recette est en attente, et le
  back-office admin est parti en production sans son API. Empiler ces trois lots avant de
  valider le parcours actuel sur un appareil réel reviendrait à construire sur du code
  jamais exécuté.

## Séquencement

1. Débloquer le déploiement en recette du lot support existant et valider le parcours sur
   un appareil réel.
2. Lot 1 backend, fusionné et déployé en premier : l'app et le back-office dépendent tous
   deux de ses endpoints.
3. Lots 2 et 3 en parallèle une fois l'API disponible.
