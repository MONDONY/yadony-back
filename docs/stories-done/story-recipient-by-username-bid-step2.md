# Story destinataire par identifiant Yadony, étape 2 : colis (Backend)

**Date:** 2026-11-22
**Status:** Étape 2 back complète, app à venir (PR jumelle)

## Résumé
L'expéditeur désigne, pour un colis précis, un utilisateur Yadony par son identifiant, sans connaître son numéro. Le colis continue sans numéro de destinataire. Le destinataire confirme ou refuse « c'est pour moi » ; au refus, l'expéditeur est prévenu et désigne un autre destinataire (identifiant, contact ou numéro). Spec : `docs/superpowers/specs/2026-11-22-destinataire-par-identifiant-colis-design.md`. Suite de l'étape 1 (`story-recipient-username-step1.md`).

## Comment ça fonctionne (pour la maintenance)

### Flux
1. À l'envoi (`POST /bids/checkout`, `POST /announcements/{id}/bids` espèces et mobile money, négociation) ou au changement (`PUT /bids/{id}/recipient`), le client envoie `recipientUsername` au lieu de nom + numéro. `BidRecipientDesignation.apply` résout le compte via `RecipientAccountResolver` (interface de `common/`, quota de recherche partagé de l'étape 1), refuse soi-même (422 `recipient-invitation-self`) et le voyageur du trajet (422 `recipient-is-traveler`). Compte introuvable, supprimé, suspendu ou bloqué : 404 `user-not-found`.
2. Le colis est enregistré avec `bids.recipient_user_id`, `recipient_name = "@identifiant"` et `recipient_phone` nul. Audit `RECIPIENT_DESIGNATED_BY_USERNAME` (id du compte, jamais le numéro).
3. À l'acceptation du colis par le voyageur, `ReceptionLinker.linkIfPossible` utilise `recipient_user_id` à la place de la recherche Firebase par numéro, avec les mêmes garde-fous (soi-même, voyageur, bloqué, confiance, recette), puis le lien `PENDING` ou `CONFIRMED` et le push existants. Le code de retrait reste valable pendant l'attente.
4. À la confirmation (ou lien d'emblée `CONFIRMED`), `DesignatedRecipientNaming.apply` renomme le colis au nom public du compte (« Prénom N. ») et copie son numéro dans `recipient_phone` seulement s'il ne l'a pas masqué (`hide_phone_number` faux) et s'il est connu.
5. Au refus : circuit existant (expéditeur et voyageur prévenus, panneau « Destinataire à remplacer », `RecipientReplacementService`).
6. Changement de destinataire (`RecipientChangeService`) : code et lien de suivi renouvelés, ancien lien retiré, `BidRecipientChangedEvent` publié ; passer d'un identifiant à un numéro vide `recipient_user_id`.

### Endpoints modifiés
- `PUT /bids/{id}/recipient` : `recipientName` et `recipientPhone` facultatifs, `recipientUsername` ajouté.
- `POST /bids/checkout` et `POST /announcements/{id}/bids` : `recipientUsername` facultatif (`@Size(max=64)`) ; `recipientName` et `recipientPhone` ne sont plus `@NotBlank` : ni identifiant ni nom + numéro donne 422 `recipient-required` (service).
- `GET` de l'aperçu par identifiant (`UsernameLookupResponse`) : champ `parcelDesignation` (toujours `true` côté ce back), signal de capacité : l'app n'offre « Un utilisateur Yadony » dans un colis que s'il est vrai ; un ancien back sans l'étape 2 ne le renvoie pas.
- Négociation (`BidNegotiationStartRequest`) : `recipientUsername` facultatif.
- `BidResponse` : deux champs ajoutés. `recipientDesignatedByUsername` (booléen) : vrai pour l'expéditeur quand `bids.recipient_user_id` est renseigné, quel que soit le lien ; toujours faux pour le voyageur. `recipientUsername` (chaîne, null explicite) : identifiant ACTUEL du compte désigné, servi à l'expéditeur seulement tant que le lien n'est pas `CONFIRMED` (absent, `PENDING` ou `DECLINED`), sinon null (aussi pour un colis par numéro, un compte supprimé, et le voyageur). L'état du destinataire reste lu dans `recipientAppStatus` (inchangé). Le compte désigné est préchargé par `prefetchFor` : pas de requête par colis dans les listes.

### Base de données
- `bids.recipient_user_id UUID NULL` + index (migration V396).

### Décisions
- Le colis garde l'id du compte plutôt qu'une invitation liée au colis (double circuit de confirmation écarté) ni le carnet seul.
- Numéro copié à la confirmation seulement : avant, le colis n'a que `@identifiant` ; un numéro masqué n'est jamais copié.
- Un numéro déjà copié reste si le destinataire le masque ensuite (même effet qu'un numéro saisi) ; seul le masquage vis-à-vis du voyageur s'applique.
- Le lien se fait sur l'id du compte, pas sur le texte : un identifiant changé entre-temps ne casse rien ; un compte supprimé ou inéligible ne crée aucun lien.

### Pièges
- Exclusivité : nom + numéro ou identifiant, jamais les deux (422 `recipient-mode-conflict`).
- En négociation et au changement de destinataire, un 400 est devenu un 422 `recipient-required` quand ni identifiant ni nom + numéro ne sont fournis.
- Le numéro n'est copié qu'à la confirmation et seulement s'il n'est pas masqué : ne jamais le recopier plus tôt.
- Le rattrapage (`catchUp`) par numéro quand Firebase est indisponible n'est pas traité pour ce mode.
- Verrou : `ReceptionService.confirm` et `ReceptionLinker.linkIfPossible` verrouillent le colis (`bidRepository.lockForUpdate`) avant toute lecture, car le nommage réécrit le colis (pas de `@Version` sur `BidEntity`) ; ordre colis puis lien.
- Re-désigner le même identifiant après la confirmation ne réécrit rien (nom public et numéro copiés conservés) ; avant, seul le libellé est rafraîchi. Le compte est résolu avant toute écriture (`BidRecipientDesignation.resolve`).
- `UsernameRecipientResolver` refuse tout compte non `ACTIVE` (suspendu, banni, en cours de suppression : 404 `user-not-found`).
- Les fiches de l'assistant (`destinataire`, `identifiant-yadony`, `ecran-fiche-colis`, `ecran-destinataires`, FR et EN) décrivent l'interface de l'app (choix « Un utilisateur Yadony »). Elles sont lues depuis le jar au démarrage.

### Ordre de déploiement
Back d'abord, puis app (la PR app cite la PR back et tolère l'ancien contrat). Les fiches décrivent l'UI de l'app : le back prod ne doit pas précéder la sortie de la release app qui porte le choix « Un utilisateur Yadony » (le déploiement en production appartient au propriétaire).

## Tests
- Base de connaissances (`KnowledgeBase*Test`, `AssistantServiceTest`) : 183 tests, 0 rouge.
- Résultat de la suite complète : _à remplir_ (la vague de correctifs n'a été vérifiée que par tests ciblés).
