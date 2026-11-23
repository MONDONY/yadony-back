# Création de trajet idempotente : en-tête Idempotency-Key (Backend)

**Date:** 2026-11-23
**Status:** ✅ Complète

## Résumé

`POST /api/v1/announcements` et `POST /api/v1/announcements/trips` n'avaient aucune déduplication : quand le
trajet était créé mais que la réponse se perdait (réseau mobile, délai de 30 s dépassé, 502), l'app affichait
une erreur, le voyageur renvoyait le formulaire et un second trajet identique était créé. Les deux routes
acceptent désormais un en-tête facultatif `Idempotency-Key` : la même clé renvoyée par le même compte rend le
trajet déjà créé au lieu d'en créer un autre.

## Contrat

- En-tête `Idempotency-Key`, facultatif : 8 à 64 caractères `[A-Za-z0-9_-]` (un UUID convient).
- Absent ou vide : comportement d'avant, strictement inchangé (anciennes versions de l'app).
- Mal formé : 400 `ProblemDetail`, `code` = `idempotency-key-invalid`.
- Première requête avec une clé : création normale, 201, la clé est gardée avec le trajet.
- Même compte, même clé : 201, aucun nouveau trajet, aucun effet de bord rejoué (audit, événements, compteurs),
  même corps que la création (annonce relue ; pour `/trips`, les étapes du voyage dans l'ordre) et en-tête de
  réponse `Idempotent-Replayed: true`.
- La clé est propre au compte : la même clé envoyée par un autre compte crée son trajet.
- Clé déjà utilisée par l'autre route (trajet direct contre voyage à étapes) : 409 `idempotency-key-reused`.
- La clé n'est jamais rendue dans une réponse ni écrite dans les journaux.

## Fichiers créés

- `src/main/java/com/yadony/api/matching/TripCreationIdempotency.java` : lit et valide la clé, rejoue avant toute
  création, rattrape la course perdue.
- `src/main/resources/db/migration/V400__announcements_idempotency_key.sql` : colonne `announcements.idempotency_key`
  (`varchar(64)`, nullable) et index unique partiel `uq_announcements_traveler_idempotency_key` sur
  `(traveler_id, idempotency_key) WHERE idempotency_key IS NOT NULL AND deleted_at IS NULL`.
- Tests : `TripCreationIdempotencyTest`, `TripGroupServiceIdempotencyTest`, `TripCreationIdempotencyIntegrationTest`
  (MockMvc, H2), `TripCreationIdempotencyConcurrencyIT` (PostgreSQL embarqué),
  `V400AnnouncementsIdempotencyKeyMigrationTest`.

## Fichiers modifiés

- `AnnouncementController` : lit l'en-tête sur les deux routes, pose `Idempotent-Replayed: true` sur un rejeu.
- `AnnouncementService` : `createIdempotentAnnouncement`, `createTripLeg(..., idempotencyKey)`,
  `findIdempotentAnnouncement` ; avec une clé, l'insertion part tout de suite (`saveAndFlush`).
- `TripGroupService` : `createIdempotentTrip` (clé sur la première étape seulement), `findIdempotentTrip`.
- `AnnouncementEntity`, `AnnouncementRepository` : colonne et recherche par `(travelerId, idempotencyKey)`.
- `SecurityConfig` : `Idempotency-Key` autorisé et `Idempotent-Replayed` exposé en CORS (portails web).
- `messages_fr/en.properties` : `problem.trip-idempotency-key-invalid`, `problem.trip-idempotency-key-reused`.

## Comment ça fonctionne (pour la maintenance)

1. Le contrôleur passe l'en-tête brut à `TripCreationIdempotency`. Sans clé : appel historique du service.
2. Avec une clé : relecture du trajet `(voyageur, clé)` AVANT la création. S'il existe, il est rendu tel quel :
   aucun contrôle n'est rejoué, sinon la limite mensuelle, la limite de brouillons, la date ou la capacité,
   atteintes à cause de la première requête, refuseraient la seconde à tort.
3. Sinon, création ordinaire ; la clé est écrite avec le trajet et l'insertion est flushée avant la grille de
   prix, l'audit et les événements.
4. Requête concurrente de même clé : l'index unique la fait échouer (violation d'unicité, ou refus de la limite
   mensuelle que la gagnante vient d'atteindre : le verrou de ligne utilisateur la fait attendre). Toute erreur
   d'une création à clé est suivie d'une relecture dans une transaction NEUVE (`REQUIRES_NEW`, la transaction
   de la perdante est condamnée) : si le trajet de la gagnante est là, il est rendu en rejeu ; sinon l'erreur
   d'origine remonte.

### Pièges et points d'attention

- La garantie vient de l'index unique, pas d'un cache mémoire : elle tient sur plusieurs instances.
- Un trajet supprimé (soft delete) libère sa clé : la même clé recrée un trajet.
- H2 (profil `test`) n'a pas l'index partiel : la course n'est testée que sur PostgreSQL
  (`TripCreationIdempotencyConcurrencyIT`).
- `TripCreationIdempotency` doit être appelé hors transaction (contrôleur) : chaque création a la sienne.
- La validation Bean du corps (`@Valid`) passe avant le rejeu : un formulaire renvoyé après minuit pour un départ
  « aujourd'hui » est refusé par `@FutureOrPresent` avant d'atteindre le rejeu.
- Routes de création non couvertes : `POST /trip-recurrences` (une récurrence en double génère ses trajets en
  double), `POST /package-requests` et `POST /trip-templates` (doublons de demande ou de modèle) n'ont aucune
  déduplication. `POST /announcements/{id}/series` refuse une date déjà occupée (contrôle sans verrou ni index
  unique : deux envois simultanés peuvent encore doubler) ; `POST /negotiations` refuse un second fil actif
  (409 `negotiation/duplicate-thread`) : un renvoi après réponse perdue y reçoit une erreur, pas un doublon.
