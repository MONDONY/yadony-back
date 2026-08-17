# Offre liée au trajet dès le début (Backend)

**Date:** 2026-08-16
**Status:** ✅ Complète

## Résumé

Inverse l'ordre dans lequel une offre du voyageur sur une demande d'envoi de colis se lie à un trajet. Avant : le voyageur négociait un prix sans trajet, le trajet n'était lié qu'après acceptation (étape `submit-trip` séparée, threads bloqués en `AWAITING_TRIP`). Maintenant : le voyageur lie un trajet (existant ou nouveau trajet dédié créé à la volée) **dès la création de l'offre**, avec ou sans négociation ultérieure — l'expéditeur voit donc le vrai trajet (date, corridor, capacité) dès la première offre reçue, avant tout paiement.

## Fichiers créés

- `src/main/java/com/yadony/api/requests/dto/NegotiationChangeTripRequest.java` — DTO du nouvel endpoint de changement de trajet
- `src/main/java/com/yadony/api/requests/event/NegotiationTripChangedEvent.java` — event notifiant l'expéditeur qu'un trajet déjà lié a changé
- `src/main/resources/db/migration/V210__cancel_awaiting_trip_threads_pre_offer_trip_link.sql` — migration de données annulant les threads orphelins de l'ancien flux, rouvrant leurs `package_requests`, et soft-deletant leurs trajets dédiés orphelins
- `src/test/java/com/yadony/api/migrations/V210MigrationTest.java`

## Fichiers modifiés

- `src/main/java/com/yadony/api/requests/dto/NegotiationStartRequest.java` — ajout `createDedicatedTrip` (bool) + `dedicatedTrip` (payload nullable)
- `src/main/java/com/yadony/api/requests/service/NegotiationService.java` — `start()` exige désormais un trajet (existant ou dédié) ; `accept()` simplifié (le trajet est garanti présent) ; nouvelle méthode `changeTrip()` ; extraction de `buildDedicatedTripAnnouncement()` et `validateAndFetchExistingTrip()` réutilisables ; `softDeleteOrphanedDedicatedTrip()` désormais appelée depuis TOUS les chemins de sortie (voir Pièges)
- `src/main/java/com/yadony/api/requests/controller/NegotiationController.java` — `PATCH /negotiations/{id}/trip`
- `src/main/java/com/yadony/api/requests/service/NegotiationExpiryRunner.java` — nettoyage du trajet dédié orphelin sur expiration système
- `src/main/java/com/yadony/api/matching/AnnouncementService.java` — `unpublishAnnouncement` ne bloque plus que sur négociation **active** (pas n'importe quelle négociation historique)
- `src/main/java/com/yadony/api/requests/repository/NegotiationThreadRepository.java` — `findByTravelerAnnouncementIdAndStatus` (remplace `findByTravelerAnnouncementId`, la relation 1:1 ne tient que sur ACCEPTED) + `existsActiveByTravelerAnnouncementId`
- `src/main/java/com/yadony/api/notifications/RequestEventsListener.java` — listener `NegotiationTripChangedEvent` → notifie le sender

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. Le voyageur envoie `POST /negotiations` avec soit `travelerAnnouncementId` (trajet existant), soit `createDedicatedTrip=true` + `dedicatedTrip` (création à la volée) — exactement l'un des deux, sinon 422 `trip-required` / `trip-not-eligible-both`.
2. `start()` valide/crée le trajet, l'attache au thread dès `OPEN`, calcule `availablePaymentMethods`.
3. L'expéditeur voit le trajet réel dans `GET /negotiations/{id}` dès la première offre.
4. Le voyageur peut changer de trajet via `PATCH /negotiations/{id}/trip` tant que le thread est `OPEN` (verrouillé dès `AWAITING_PAYMENT`).
5. `accept()` passe directement à `AWAITING_PAYMENT` (le trajet est garanti présent, plus de détour par `AWAITING_TRIP`).
6. Le flux legacy `refuseTrip` → `AWAITING_TRIP` → `submitTrip` reste intact pour le cas où l'expéditeur refuse un trajet déjà verrouillé.

### Points d'entrée API

- `POST /api/v1/negotiations` — ouvre une négociation, trajet obligatoire (ROLE_TRAVELER)
- `PATCH /api/v1/negotiations/{id}/trip` — change le trajet lié, uniquement statut `OPEN` (ROLE_TRAVELER, ownership vérifié)
- `POST /api/v1/negotiations/{id}/submit-trip` — legacy, réservé à la reprise post-`refuseTrip` (statut `AWAITING_TRIP`)

### Logique métier critique

- **Un trajet dédié appartient exclusivement à sa `package_request`** (`linked_package_request_id`). Dès qu'il est détaché d'un thread sans avoir atteint `ACCEPTED`, il devient orphelin et doit être soft-deleted — sinon il reste `ACTIVE` pour toujours, une entrée morte dans "Mes trajets" du voyageur.
- **Capacité non réservée à l'offre** : `availableKg` du trajet dédié reste à 0 jusqu'à l'acceptation + paiement (réservation hard toujours au moment du paiement, inchangé).
- **Un trajet existant (non dédié) peut désormais backer plusieurs offres concurrentes OPEN simultanément** (le voyageur peut négocier sur plusieurs demandes avec le même trajet) — seule la relation ACCEPTED reste 1:1.

### Events Spring publiés / écoutés

- `NegotiationTripChangedEvent` publié par `changeTrip()` → écouté par `RequestEventsListener.onNegotiationTripChanged` → notifie le SENDER ("Trajet mis à jour"). Distinct de `NegotiationAwaitingTripEvent` (notifie le TRAVELER, sémantique inverse).

### Pièges et points d'attention

- **`softDeleteOrphanedDedicatedTrip` doit être appelée depuis TOUS les chemins qui détachent ou terminent un thread portant un trajet dédié** : `cancelNegotiation` (tous statuts, plus seulement `AWAITING_PAYMENT`), `reject`, la boucle `AUTO_REJECTED` de `finalizeInternal`, `changeTrip` (sur l'ancien trajet avant écrasement), `NegotiationExpiryRunner.expireThread`. Oublier un chemin ici est le bug le plus facile à réintroduire si cette feature est retouchée — trouvé et corrigé lors de la revue finale de branche (régression cross-tasks qu'aucune revue par tâche isolée n'aurait vue).
- **`NegotiationThreadRepository.findByTravelerAnnouncementIdAndStatus` doit toujours filtrer sur `ACCEPTED`** dans `openSurplus` — sans ce filtre, un trajet lié à plusieurs offres concurrentes fait lever `IncorrectResultSizeDataAccessException` (500 non RFC 7807) au lieu d'un 409 propre.
- **`changeTrip` refuse `AWAITING_TRIP`** (409 `negotiation-trip-locked`) — ce statut est réservé au flux legacy `submitTrip`, qui transitionne vers `AWAITING_PAYMENT` et recalcule `availablePaymentMethods` ; `changeTrip` ne fait ni l'un ni l'autre.
- **Déploiement** : `POST /negotiations` rejette désormais 422 `trip-required` tout client qui n'envoie pas de trajet — toute version Flutter antérieure à cette feature casse sur cet endpoint. Le backend doit être déployé APRÈS (ou en même temps que, via feature flag) la version front correspondante.

## Critères d'acceptation couverts

- [x] Trajet lié dès la création de l'offre, avec ou sans négociation ultérieure — `start()` exige `travelerAnnouncementId` XOR `createDedicatedTrip`
- [x] Trajet modifiable jusqu'au paiement, visible par l'expéditeur — `changeTrip()` + `NegotiationTripChangedEvent`
- [x] Trajet dédié créable à la volée si aucun trajet existant ne correspond — `createDedicatedTrip` dans `start()`
- [x] Threads `AWAITING_TRIP` pré-existants annulés au déploiement (pas migrés de force) — V210
- [x] Capacité non réservée à l'offre, seulement à l'acceptation/paiement — inchangé, vérifié par tests

## Tests

- `./mvnw test` → 3143 tests, 2 échecs (connus, pré-existants, sans rapport : `AnnouncementRepositoryCorridorTest`)
- Tests ajoutés : ~90 nouveaux cas dans `NegotiationServiceTest` (start/accept/changeTrip/cancel/reject/finalizeInternal), `NegotiationControllerIT`, `NegotiationExpiryRunnerTest`, `AnnouncementServiceTest`, `V210MigrationTest` (EmbeddedPostgres + Flyway réel)
- 3 scénarios Cucumber (`negociation.feature`) réécrits pour refléter le nouveau flux, dont 2 exercisant end-to-end la boucle legacy `refuseTrip`/`submitTrip`

## Décisions techniques

- **`changeTrip` restreint à `OPEN`** (pas `AWAITING_TRIP`) plutôt que de dupliquer la logique de transition de `submitTrip` — évite un état mort où le trajet est lié mais le thread reste bloqué.
- **Nouvel event `NegotiationTripChangedEvent`** plutôt que réutiliser `NegotiationAwaitingTripEvent` — ce dernier notifie le voyageur avec un message "choisissez un trajet", sémantiquement inverse du besoin ici (notifier l'expéditeur d'un changement).
- **Migration numérotée V210** — d'abord numérotée V208 (dernier numéro libre observé dans le worktree isolé au moment de l'écrire, plutôt que le V210 du plan, déjà obsolète). Après merge de main (qui avait entre-temps ajouté ses propres V208 et V209), collision réelle détectée seulement au démarrage local de l'app (Found more than one migration with version 208) — renumérotée en V210 après merge.
- **`findByTravelerAnnouncementId` remplacé par une variante filtrée sur `ACCEPTED`** — la relation 1:1 trajet↔thread supposée par le code existant (`openSurplus`) est cassée dès qu'un trajet peut backer plusieurs offres concurrentes, ce que cette feature autorise désormais pour les trajets existants (pas les dédiés, toujours 1:1 par construction).
