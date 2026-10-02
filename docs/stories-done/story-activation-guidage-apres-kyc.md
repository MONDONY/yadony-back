# Guidage après le KYC (Backend)

**Date:** 2026-10-02
**Status:** ✅ Complète

## Résumé
34 % des comptes au KYC vérifié ne faisaient plus rien (PostHog, 01/10). Le backend connaît désormais l'intention de l'utilisateur (envoyer, voyager, les deux) et son pays visé, sait s'il a fait sa première action, calcule les trajets ou colis disponibles vers ce pays, et relance à J+1 puis J+3 ceux qui n'ont rien fait.

## Fichiers créés
- `db/migration/V287__activation_intent_et_relances.sql` — colonnes `users.intent`, `intent_destination_country`, `intent_source`, `intent_declared_at`, `kyc_verified_at`, `first_action_reminder_count`, `first_action_reminder_last_at` ; remplissage de l'existant (intention déduite de l'activité, date KYC depuis `kyc_verifications`).
- `activation/ActivationController.java` — `GET /users/me/activation`, `PUT /users/me/intent`.
- `activation/ActivationService.java` — lecture du statut, déclaration de l'intention (audit `INTENT_DECLARED`), opportunités sur 15 jours.
- `activation/ActivationRepository.java` — SQL natif : première action, trajets / colis vers un pays, candidats de relance.
- `activation/ActivationKycListener.java` — horodate `kyc_verified_at` sur `UserKycVerifiedEvent`.
- `activation/FirstActionReminderScheduler.java` — relance horaire (push `FIRST_ACTION_REMINDER`).
- `activation/ActivationZones.java` — fuseau d'envoi par pays (Europe/Paris par défaut).
- `activation/UserIntent.java`, `IntentSource.java`, `dto/ActivationResponse.java`, `dto/DeclareIntentRequest.java`.

## Fichiers modifiés
- `auth/UserEntity.java` — champs de V287.
- `notifications/NotificationTexts.java` — `firstActionReminder(Messages, variant, count)`.
- `notifications/NotificationDeeplink.java` — `FIRST_ACTION_REMINDER` → `yadony://first-steps`.
- `i18n/messages_fr.properties`, `messages_en.properties` — textes `notification.first-action.*` (vouvoiement, pluriel `.one/.other`).
- `application.yml` (coupé par défaut), `application-staging.yml` (activé), `application-test.yml` (cron coupé).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Inscription ou sheet de l'app → `PUT /users/me/intent` → `ActivationService.declareIntent` valide le pays (`CountryCatalog`), écrit les colonnes, audite.
2. L'app lit `GET /users/me/activation` : intention, `kycVerified`, `firstActionDone`, opportunités (`TRIPS` pour expéditeur et « les deux », `PACKAGES` pour voyageur, `NONE` si intention ou pays inconnus).
3. KYC vérifié → `UserKycVerifiedEvent` → `ActivationKycListener` horodate `kyc_verified_at` (une seule fois).
4. Toutes les heures (minute 15), `FirstActionReminderScheduler` prend les comptes vérifiés depuis ≥ 24 h (1re relance) ou ≥ 72 h (2e), sans première action, et leur envoie une push entre 10 h et 20 h locales.

### Points d'entrée API
- `GET /api/v1/users/me/activation` — utilisateur connecté (non invité).
- `PUT /api/v1/users/me/intent` — corps `{intent, destinationCountry?, source}` ; `source` ∈ `SIGNUP`, `PROMPT`, `SETTINGS` (`INFERRED` refusé, 422) ; pays non pris en charge → 422 ; enum illisible → 400.

### Logique métier critique
- **Première action** = trajet (hors brouillon), demande de colis (hors brouillon), bid, fil de négociation voyageur ou alerte de corridor, non supprimés.
- **Opportunités** : la recherche existante filtre par ville et `package_requests` n'a pas de pays ; le pays d'arrivée vient de `announcements.arrival_country_code`, sinon de `cities` (nom de ville en minuscules). Les trajets ou demandes de l'utilisateur lui-même sont exclus.
- **Relances** : compteur incrémenté et horodaté **avant** l'envoi (idempotence) ; hors fenêtre horaire, rien n'est écrit et l'envoi attend le passage suivant ; 2 envois maximum ; la requête de candidats relit la première action (un utilisateur qui vient d'agir sort de la liste).

### Events Spring publiés / écoutés
- Écoute `UserKycVerifiedEvent` (`AFTER_COMMIT`, `REQUIRES_NEW`).

### Pièges et points d'attention
- **Ids natifs lus en `CAST(id AS VARCHAR)`** : sous H2, un UUID natif remonte en `byte[]` (même motif qu'`AnnouncementRepository`).
- Les comptes déjà vérifiés reçoivent `kyc_verified_at` par V287 : dès l'activation de l'interrupteur, ceux sans première action reçoivent la relance J+1 puis J+3.
- Textes de push contraints par `NotificationTextsTest` (titre ≤ 28, corps ≤ 72, vouvoiement, pas de ville dans le titre).

## Critères d'acceptation couverts
- [x] L'intention et le pays visé sont enregistrés et audités — `ActivationServiceTest`, `ActivationControllerTest`.
- [x] Les comptes existants actifs ont une intention déduite — V287, validée sur Postgres.
- [x] `firstActionDone` reflète chacune des 5 actions — `ActivationRepositoryIT`.
- [x] Trajets / colis vers le pays visé sur 15 jours, 3 maximum + total — `ActivationRepositoryIT`, `ActivationServiceTest`.
- [x] Relance J+1 / J+3, 2 max, 10 h-20 h locales, audit `FIRST_ACTION_REMINDER_SENT` — `FirstActionReminderSchedulerTest`, `ActivationZonesTest`.

## Tests
- `./mvnw test` → 7 352 tests, 0 échec (7 ignorés existants).
- `./mvnw test jacoco:report` → package `activation` : 99,9 % des instructions.
- Tests ajoutés : `UserEntityActivationFieldsTest`, `ActivationKycListenerTest`, `ActivationRepositoryIT`, `ActivationServiceTest`, `ActivationControllerTest`, `ActivationZonesTest`, `FirstActionReminderSchedulerTest` ; `NotificationTextsTest` et `NotificationDeeplinkTest` étendus.

## Décisions techniques
- Endpoint dédié `/users/me/activation` plutôt qu'élargir `UserResponse` (record positionnel utilisé partout).
- Opportunités calculées côté serveur (pays non disponible dans les recherches par ville).
- Interrupteur activé par `application-staging.yml` plutôt que par une variable posée à la main sur le VPS.
