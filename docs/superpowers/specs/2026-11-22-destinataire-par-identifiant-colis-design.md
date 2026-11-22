# Destinataire d'un colis désigné par identifiant Yadony (étape 2) — design

Date : 2026-11-22. Statut : à approuver. Dépôts : `dony-back` (PR à fusionner en premier), puis `dony_app`. Suite de l'étape 1 (PR back #778 et PR app #869, fusionnées) : design `2026-11-21-destinataire-par-pseudo-design.md`. Aucun code écrit à ce stade.

## Intention

L'expéditeur désigne, pour **un colis précis**, un utilisateur Yadony par son identifiant, sans connaître son numéro. Le colis continue pendant l'attente (le code de retrait reste valable). Le destinataire confirme ou refuse « c'est pour moi » ; au refus, l'expéditeur est notifié, le colis reste sans destinataire et il en désigne un autre (un utilisateur Yadony, un contact ou un numéro).

## Ce que le code fait déjà (relevé sur `origin/main`)

- À l'acceptation du colis (`BidAcceptedEvent`, paiement mobile money confirmé, fil matérialisé, changement de destinataire) `ReceptionLinkListener` appelle `ReceptionLinker.linkIfPossible(bidId)`, qui crée le lien `bid_recipient_links` entre le colis et le **compte** du destinataire (`PENDING`, ou `CONFIRMED` si l'expéditeur lui fait déjà confiance) et pousse « X vous envoie un colis ». Aujourd'hui le compte est retrouvé par le numéro `bids.recipient_phone` (Firebase).
- Le destinataire répond par `POST /receptions/{bidId}/confirm|decline` (`ReceptionService`) ; le refus prévient l'expéditeur et le voyageur, masque le destinataire au voyageur (`ReceptionLinkStatus.hidesRecipientFromTraveler`) et déclenche le panneau « Destinataire à remplacer » et `RecipientReplacementService`.
- `RecipientChangeService` (`PUT /bids/{bidId}/recipient`) change le destinataire d'un colis `ACCEPTED` à `ARRIVED` : renouvelle code de retrait et lien de suivi, retire l'ancien lien, publie `BidRecipientChangedEvent`.
- `bids.recipient_name` et `bids.recipient_phone` sont nullables. Les consommateurs du numéro sont : l'affichage (expéditeur toujours, voyageur à partir de la remise), le rattachement par numéro et le rattrapage par numéro. Aucun SMS n'est envoyé au destinataire à partir de ce champ.
- Étape 1 : `UsernameLookupService.resolveVisible` / `beginLookup` (normalisation, compte introuvable, supprimé, suspendu ou bloqué, quota partagé de 30 par heure), `LookupRateLimiter`, identifiants modifiables.

## Objectifs

1. À la **création du colis** et au **changement de destinataire**, l'expéditeur peut désigner un destinataire par son identifiant au lieu de nom + numéro.
2. Le colis est créé et continue sans numéro de destinataire.
3. À l'acceptation du colis, le destinataire désigné reçoit la demande de confirmation existante ; confirmer ou refuser réutilise tout le circuit actuel.
4. Le numéro du destinataire n'est connu du colis qu'**à sa confirmation** et seulement s'il ne l'a pas masqué (réglage « Masquer mon numéro ») : avant, ou s'il l'a masqué, le colis n'a que l'identifiant.

## Hors périmètre

- La demande négociée (`complete_details_screen`, `ThreadAcceptedBidListener`, `NegotiationService` qui exige nom et numéro) : elle garde nom + numéro.
- Une invitation liée au colis (entité `recipient_invitations` avec `bid_id`) : écartée, elle doublerait la confirmation et le refus qui existent au niveau du colis.
- Rappels ou expiration de l'attente au-delà de l'existant, page publique de suivi, écrans admin.
- Le carnet : l'invitation d'étape 1 reste dans « Mes destinataires ».

## Contre-objectifs (échec même si ça marche)

- Le numéro d'un destinataire qui a masqué son numéro apparaît chez l'expéditeur ou le voyageur, ou un numéro copié dans le colis avant que le destinataire ait confirmé.
- Un colis créé sans destinataire parce que l'ancien back a ignoré le champ `recipientUsername` en silence.
- Un utilisateur sert d'oracle d'existence de comptes par la création de colis (le quota de recherche de l'étape 1 doit s'appliquer).
- Un destinataire bloqué, suspendu, soi-même ou le voyageur du trajet désigné comme destinataire.

## Contraintes

- Règles du dépôt : migration `V(n+1)` libre au moment de coder (dernier : V391 ; `feature/telephone-declare` en prévoit un aussi, la collision se règle à la fusion), erreurs RFC 7807 localisées FR et EN, `audit_log`, événements Spring entre packages, fiches de l'assistant FR et EN dans la PR back, PR jumelles (back d'abord, l'app tolère l'ancien contrat), tests, couverture.
- Un seul seau de recherche de 30 par heure (étape 1) : la désignation à la création passe par `beginLookup` comme la recherche.

## Approches considérées

- **1. Le colis garde l'identifiant du compte destinataire (retenue).** `bids.recipient_user_id` ; `ReceptionLinker` l'utilise à la place de la recherche par numéro ; tout le reste existe.
- **2. Invitation liée au colis.** Écartée : double circuit de confirmation et de refus.
- **3. Carnet seulement.** Écartée : ne couvre pas « le colis continue pendant l'attente ».

## Décisions

### Données

- `bids.recipient_user_id UUID NULL` + index. À la désignation, un colis a soit un destinataire par numéro (comme aujourd'hui), soit un destinataire par compte (`recipient_user_id`, `recipient_phone` nul), soit aucun.
- Tant que le destinataire n'a pas confirmé, `recipient_name` vaut `@<identifiant>` (libellé existant affiché partout) et `recipient_phone` reste nul.
- **À la confirmation** (`ReceptionService.confirm`) : `recipient_name` devient le nom public du compte (`publicDisplayName()`, « Prénom N. »), et **le numéro du compte est copié dans `recipient_phone` s'il ne l'a pas masqué** (`hide_phone_number` faux, numéro lu comme le fait déjà `ReceptionLinker.catchUp`) ; s'il l'a masqué, ou si le compte n'a pas de numéro, `recipient_phone` reste nul et seul le nom change. `recipient_user_id` reste renseigné, ce qui permet au colis d'avoir à la fois le compte et le numéro. Le numéro copié suit ensuite les règles d'affichage existantes (expéditeur toujours ; voyageur à partir de la remise, et `recipientPhoneHiddenFromTraveler` pour un destinataire confirmé qui masque son numéro).
- Si le destinataire masque son numéro **après** la confirmation, le numéro déjà copié reste dans le colis (même effet qu'un numéro saisi par l'expéditeur) ; seul le masquage vis-à-vis du voyageur s'applique.

### Contrats

- `BidCheckoutRequest` et `BidNegotiationStartRequest` gagnent `recipientUsername` (facultatif). Règle : soit nom + numéro (comportement actuel), soit `recipientUsername`, jamais les deux (422 sinon). Un colis sans aucun des deux reste permis comme aujourd'hui.
- `ChangeRecipientRequest` (`PUT /bids/{bidId}/recipient`) : `recipientName` et `recipientPhone` deviennent facultatifs et `recipientUsername` s'ajoute, avec la même règle d'exclusivité.
- Validation de `recipientUsername` : `UsernameLookupService.beginLookup` (format puis quota partagé, 429 `recipient-lookup-quota`) puis `resolveVisible` ; introuvable, supprimé, suspendu ou bloqué : 404 `user-not-found` ; soi-même : 422 `recipient-invitation-self` ; voyageur du trajet : 422 `recipient-is-traveler` (nouvelle clé FR et EN).
- `BidResponse` expose déjà l'état du lien destinataire et `recipientDeclined` ; ajouter ce qu'il faut pour que l'app distingue « destinataire désigné par identifiant, en attente » (lien `PENDING`, ou pas encore de lien) de « confirmé » et « refusé » (point laissé au constructeur après lecture de `BidService.toResponse`).

### Rattachement et réponse

- `ReceptionLinker.linkIfPossible` : si `recipient_user_id` est renseigné, il remplace la recherche Firebase par numéro et applique les mêmes garde-fous (soi-même, voyageur, bloqué, confiance, mode recette) puis la même logique `CONFIRMED` / `PENDING` et le même push. `ReceptionService.confirm` renomme le colis au nom public ; `decline` reste inchangé (panneau « Destinataire à remplacer », `RecipientReplacementService`, notifications existantes).
- `RecipientChangeService` : en mode identifiant, mêmes effets qu'un changement de numéro (code et lien renouvelés, ancien lien retiré, événement publié) avec `recipient_user_id` renseigné, `recipient_phone` nul et le libellé `@identifiant` ; passer d'un identifiant à un numéro vide `recipient_user_id`. L'ancien destinataire perd l'accès immédiatement, comme aujourd'hui.
- Si l'identifiant change ou si le compte est supprimé avant l'acceptation, le lien se fait sur l'id du compte (pas sur le texte) ; un compte supprimé ou devenu inéligible ne crée aucun lien : le colis reste sans destinataire confirmé, remplaçable par l'expéditeur.
- `audit_log` : `RECIPIENT_DESIGNATED_BY_USERNAME` (colis, id du compte, jamais le numéro).

### App (dony_app)

- Dans la création de colis (`create_bid_bottom_sheet` via `RecipientSection`) et le changement de destinataire (`recipient_change_sheet`), le choix du destinataire propose « Un utilisateur Yadony » : saisie de l'identifiant, recherche à la validation (le composant de l'étape 1, extrait pour être réutilisé), aperçu « Compte trouvé, prénom commençant par X », puis sélection. Le colis part avec `recipientUsername` ; l'écran affiche `@identifiant` et « en attente de confirmation ».
- Cette entrée n'est atteignable qu'après une recherche réussie : l'ancien back (405 sur la recherche) masque déjà l'onglet via le drapeau de session de l'étape 1, donc `recipientUsername` n'est jamais envoyé à un back qui l'ignorerait.
- Écran du colis : « Colis & destinataire » montre l'état (en attente, confirmé, refusé) ; au refus, le panneau existant « Destinataire à remplacer » offre déjà le remplacement, y compris par un autre identifiant.
- L'entrée « Ajouter un destinataire Yadony » du sélecteur (invitation au carnet, étape 1) reste dans « Mes destinataires » ; le sélecteur d'un colis propose la désignation pour ce colis.
- l10n FR et EN, boutons courts, sheets avec `stickyBottom`, BLoC et GoRouter, analytics (désignation, échecs), fiches de l'assistant FR et EN (dans la PR back).

## À régler par le constructeur (mes choix)

- Numéro de migration, noms de colonnes et de champs, clés l10n et d'analytics.
- Forme exacte du champ ajouté à `BidResponse`.
- Où vit la validation partagée de `recipientUsername` (un service du package `matching/reception`, qui appelle `UsernameLookupService` par une interface exposée dans `common/`, jamais d'injection croisée directe).

## À valider par le propriétaire

1. *(Tranché par le propriétaire le 22/11)* Le numéro peut être visible une fois le destinataire confirmé, s'il ne l'a pas masqué. Avant la confirmation, le colis affiche `@identifiant`, puis le nom public (« Prénom N. »).
2. Les colis de la demande négociée restent hors de l'étape 2.
