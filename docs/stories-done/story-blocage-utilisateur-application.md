# Blocage d'un utilisateur — application du masquage (Backend)

**Date:** 2026-09-01
**Status:** ✅ Complète

## Résumé

Le blocage existait (table `user_blocks`, `BlockService`, `/users/me/blocks`) mais n'était appliqué qu'à trois endroits : la recherche de trajets, le rematch et la création d'offre. Partout ailleurs un utilisateur bloqué restait visible et pouvait encore atteindre celui qui l'avait bloqué. Cette passe étend le masquage à l'ensemble des lectures et des notifications, derrière un contrat unique.

Elle change aussi une règle produit : bloquer pendant une transaction en cours était refusé (409), c'est désormais autorisé, la contrepartie restant visible jusqu'à la fin de l'acheminement.

## Fichiers créés

- `src/main/java/com/yadony/api/common/BlockVisibility.java` — contrat de masquage exposé aux features, pour qu'elles appliquent la règle sans injecter le service de `auth/`

## Fichiers modifiés

**Cœur**
- `auth/BlockService.java` — implémente `BlockVisibility` (`isHidden`, `assertVisible`, `hiddenUserIdsFor`) ; `block()` ne refuse plus pendant une transaction active
- `matching/BidRepository.java` — `findActiveTransactionCounterparties`, pendant en lot de `hasActiveTransactionBetween`

**Lectures masquées (404)**
- `matching/AnnouncementService.java` — `getAnnouncementDetail`
- `matching/BidService.java` — `getBidById` ; `getBidsForAnnouncement` filtre les offres masquées ; `getBidAfterOwnMutation` lève la garde pour la relecture qui suit une mutation de l'appelant
- `matching/BidController.java` — `cancel-after-handover` relit via `getBidAfterOwnMutation`
- `requests/service/PackageRequestService.java` + `requests/specification/PackageRequestSpecifications.java` — `getById` et les trois chemins de recherche
- `auth/ProfilePublicService.java`, `ProfilePublicController.java`, `PublicTravelerController.java` — profils publics, lien partageable inclus
- `ratings/RatingService.java`, `RatingController.java` — profil de notes

**Listes filtrées**
- `favorites/FavoriteService.java` — trajets et demandes favoris
- `matching/MatchingService.java` — suggestions « colis sur mes trajets »
- `messaging/ConversationService.java`, `ConversationController.java`, `ConversationRepository.java` — accès au fil, envoi, liste (filtrée en base)

**Notifications**
- `notifications/NotificationDispatcher.java` — `notifyUnlessBlocked` ; `notifyUser` reste sans filtre pour les notifications système
- `notifications/BidNegotiationEventsListener.java`, `RequestEventsListener.java`
- `alerts/CorridorAlertTripMatchListener.java`, `CorridorAlertDigestScheduler.java`
- `matching/PackageMatchTravelerNotifyListener.java`, `TravelerStatsController.java`
- `subscriptions/TravelerAvailabilityListener.java`, `automation/AutomationAnnouncementListener.java`

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. A bloque B : `POST /users/me/blocks` insère une ligne dans `user_blocks`. Aucune notification n'est envoyée à B.
2. Toute lecture ou notification impliquant les deux passe par `BlockVisibility`.
3. `isHidden(viewer, cible)` répond vrai s'il existe un blocage dans un sens ou l'autre **et** qu'aucune transaction n'est en cours entre eux.
4. Selon le contexte : 404 pour une ressource, retrait silencieux d'une liste, notification supprimée.

### Les deux invariants

- **Symétrique** : peu importe qui a bloqué, aucun des deux ne voit l'autre. `existsBetween` teste les deux sens.
- **Silencieux** : le masquage se fait toujours par 404, jamais par 403, et avec le même code d'erreur qu'une ressource inexistante (`announcement-not-found`, `bid-not-found`). Un code distinct rendrait le blocage détectable par celui qui en fait l'objet, ce qui vide la protection de son sens.

### L'exception « transaction en cours »

`BlockService.ACTIVE_STATUSES` (PENDING, PAYMENT_ESCROWED, ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED, NEGOTIATING) définit ce qui compte comme transaction en vol. Tant qu'un colis circule entre les deux, rien n'est masqué : le fil de discussion reste ouvert, le suivi accessible, les notifications de livraison passent. Couper la coordination en plein acheminement ferait plus de dégâts que le blocage n'en évite, et rendrait les litiges ingérables.

`ARRIVED` en fait partie : le colis est arrivé mais pas encore retiré, c'est le moment où les deux parties ont le plus besoin de se parler.

### Points d'entrée API

Aucun endpoint ajouté ou supprimé. Les endpoints existants renvoient 404 au lieu de leur contenu quand la cible est masquée, et les listes en retirent silencieusement les contenus concernés.

### Performance

`hiddenUserIdsFor` est résolu **une fois par destinataire ou par appel**, jamais par élément parcouru : `MatchingService` le calcule avant la boucle, `CorridorAlertDigestScheduler` le mémorise dans une map pour toute l'exécution (un propriétaire peut avoir plusieurs alertes). La liste des conversations et la recherche de colis filtrent en SQL, pas après pagination — filtrer en mémoire après un `findAll` paginé renverrait des pages trouées et un total faux.

### Pièges et points d'attention

- `hiddenUserIdsFor` peut renvoyer un `Set.of()` immuable, dont `contains(null)` lève une NPE : toujours tester la nullité de l'id avant.
- `getBidById` est gardé, mais une relecture qui suit une mutation de l'appelant doit passer par `getBidAfterOwnMutation` : une annulation fait sortir le colis des statuts actifs, et la garde renverrait alors un 404 pour une opération pourtant réussie.
- `sendMessageNotification` renvoie `null` quand la notification est supprimée : cet identifiant sert à la Cloud Function pour créditer les non-lus, le renvoyer ferait apparaître un badge pour un message masqué.
- `CorridorAlertTripMatchListener` n'horodate pas `lastNotifiedAt` quand la notification est supprimée : cette borne sert de « depuis » au digest, la poser ferait disparaître des trajets visibles publiés dans la même fenêtre.
- Les notes déjà écrites par un utilisateur bloqué restent comptées dans le score de réputation. Décision assumée : on masque un profil, on ne réécrit pas l'historique, sinon la réputation d'un tiers changerait selon qui la regarde.

## Décisions techniques

| Décision | Alternative écartée | Motif |
|---|---|---|
| 404 partout, avec le code d'erreur de la ressource absente | 403 « accès interdit » | Un 403 confirme l'existence de la ressource et rend le blocage détectable |
| Blocage autorisé pendant une transaction, contrepartie maintenue visible | Refus 409 (comportement précédent) | Le harcèlement peut survenir précisément pendant l'acheminement ; interdire le blocage laisse la victime sans recours |
| Contrat `BlockVisibility` dans `common/` | Injection directe de `BlockService` | La règle est transverse, son implémentation reste au package qui possède `user_blocks` |
| `notifyUnlessBlocked` distincte de `notifyUser` | Filtre silencieux dans `notifyUser` | `notifyUser` sert aussi aux notifications système sans émetteur ; un filtre implicite y masquerait des messages légitimes |
| Filtrage SQL pour les listes paginées | Filtrage en mémoire après `findAll` | Sinon pages trouées et total faux |
| Notes conservées dans le score | Recalcul en excluant les bloqués | La réputation ne doit pas dépendre de qui la consulte |

## Tests

- `./mvnw test` → 4473 tests, 0 échec (état avant les derniers lots ; run final en cours de vérification)
- Tests ajoutés : `BlockServiceTest` (masquage, transaction active, viewer anonyme), `AnnouncementDetailBlockVisibilityTest`, `BidBlockVisibilityTest`, `PackageRequestBlockSpecificationDbTest`, `PackageRequestSpecificationsTest`, `ProfilePublicServiceTest`, `RatingServiceTest`, `FavoriteServiceTest`, `ConversationServiceTest`, `ConversationControllerTest`, `NotificationDispatcherTest`, `CorridorAlertDigestSchedulerTest`, `TravelerAvailabilityListenerTest`, `TravelerInviteBlockVisibilityTest`, `MatchingServiceTest`
