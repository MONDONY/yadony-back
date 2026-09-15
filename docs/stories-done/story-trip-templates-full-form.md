# Modèles de trajet complets (Backend)

**Date:** 2026-09-14
**Status:** ✅ Complète

## Résumé

Le modèle de trajet (`trip_templates`) mémorise tout le formulaire de création d'un trajet : devise figée, mode de tarification (kilo ou grille + kilo), moyens de paiement, prix négociable, catégories refusées, note aux expéditeurs, adresses optionnelles, heure de départ, délai de remise relatif en jours et codes pays. Au passage, les trajets générés par les récurrences appliquent enfin les conditions que la récurrence stockait déjà (devise, négociable, note, refusés, mode, délai), et proposent le mobile money quand devise et compte le permettent.

Spec et plan : dépôt `docs-claude`, `docs/specs/2026-09-14-modeles-trajet-complets-design.md`, `docs/plans/2026-09-14-modeles-trajet-complets-back.md`.

## Fichiers créés

- `src/main/resources/db/migration/V257__trip_templates_full_form.sql` : colonnes du formulaire complet, `price_per_kg` nullable, backfill de `accepted_payment_methods` depuis `cash_accepted`, contrainte `chk_trip_templates_handover_lead_days` (0 à 7).
- `src/main/java/com/yadony/api/triptemplate/dto/TripTemplatePayload.java` : interface des 27 champs communs aux requêtes de création et de mise à jour ; le service ne lit que cette interface.
- `src/test/java/com/yadony/api/migrations/V257MigrationTest.java` : PostgreSQL embarqué + vrai Flyway, ligne insérée avant la migration pour prouver le backfill.

## Fichiers modifiés

- `triptemplate/TripTemplateEntity.java` : 16 champs ajoutés ; coordonnées d'adresse en `BigDecimal(precision 9, scale 6)` comme `AnnouncementEntity` (Hibernate `ddl-auto: validate` refuse un `Double` sur `NUMERIC(9,6)`).
- `triptemplate/dto/CreateTripTemplateRequest.java`, `UpdateTripTemplateRequest.java` : 12 champs optionnels, `pricePerKg` sans `@NotNull`, `handoverLeadDays` `@Min(0) @Max(7)`, codes pays `@Pattern("[A-Za-z]{2}")`.
- `triptemplate/dto/TripTemplateDto.java` : 30 champs, `cashAccepted` conservé en miroir.
- `triptemplate/TripTemplateService.java` : validation et mapping (voir ci-dessous), `YadonyBusinessException` au lieu de `ResponseStatusException`.
- `matching/TripRecurrenceService.java` : `buildRequest` lit les champs de la récurrence.
- Tests : `TripTemplateServiceTest` (13 cas ajoutés), `TripTemplateControllerIntegrationTest` (round-trip complet et ancien contrat), `TripRecurrenceServiceTest` (4 cas ajoutés).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. L'app envoie `POST /trip-templates` (ou `PUT /trip-templates/{id}`) avec le formulaire complet, ou, pour un build antérieur, l'ancien corps (`cashAccepted` seul).
2. `TripTemplateService.applyFields` résout la devise (celle du modèle, sinon la devise active du profil via `ActiveCurrencyResolver`), dérive ou valide les moyens de paiement, borne le prix dans cette devise, vérifie les adresses, puis copie les 27 champs sur l'entité.
3. L'entité est sauvegardée, une entrée `audit_log` `TRIP_TEMPLATE_CREATED` / `TRIP_TEMPLATE_UPDATED` est écrite.
4. Le DTO de sortie rend les 30 champs, `cashAccepted` compris (dérivé de la présence de `CASH`).

### Points d'entrée API

- `GET /trip-templates`, `POST /trip-templates`, `PUT /trip-templates/{id}`, `DELETE /trip-templates/{id}` : rôle `TRAVELER`, inchangés dans leur forme.

### Entités JPA impliquées

- `TripTemplateEntity` → `trip_templates`. Listes jointes par virgule (`accepted_categories`, `refused_types`, `accepted_payment_methods`), normalisation `ContentCategoryNormalizer` à l'écriture. `cash_accepted` est un miroir de `accepted_payment_methods` (contient `CASH`) : le service l'écrit toujours, jamais l'inverse.
- `TripRecurrenceEntity` → `trip_recurrences` : aucune colonne nouvelle.

### Logique métier critique

- **Devise du modèle prioritaire** : le plafond du prix (`CurrencyBounds.maxPricePerKg`) et les rails de paiement (`CurrencyPaymentRails.allows`) suivent la devise du modèle, pas celle du profil. Un modèle « Abidjan → Paris » en XOF accepte 2 000 F CFA/kg et le mobile money même si le portefeuille est en euros.
- **Moyens de paiement** : un moyen explicitement demandé mais interdit par la devise est refusé (422 `trip-template/payment-method-not-available`) : un modèle est un mémo réutilisé, mieux vaut échouer franchement, là où l'annonce filtre en silence. Le chemin « client ancien » (`acceptedPaymentMethods` absent) est dérivé de `cashAccepted` puis filtré par `AnnouncementPaymentRails.restrictToCurrency` (repli espèces). Seuls `STRIPE`, `CASH`, `MOBILE_MONEY` sont acceptés (422 `trip-template/payment-method-invalid` pour un rail legacy). Un jeton inconnu lu en base est ignoré à la lecture.
- **Prix** : mode `KG` exige un prix strictement positif (422 `trip-template/price-required`) ; mode `MIXED` l'accepte nul et normalise `<= 0` à `null`. Plafond : 422 `trip-template/price-out-of-bounds`.
- **Adresses** : complètes (libellé + lat + lng) ou absentes (422 `trip-template/address-incomplete`).
- **Devise inconnue** : 422 `currency-unsupported`, même code que l'annonce.
- **Récurrences** : `buildRequest` applique `currency`, `negotiable`, `description`, `refusedCategories`, `pricingMode`, et `handoverDeadline = départ - handoverLeadDays` ; si cette date est déjà passée à la génération, repli sur l'heure du départ (une limite expirée ouvrirait le signalement de no-show dès l'acceptation). Carte et mobile money sont proposés dès que la devise et les comptes du voyageur le permettent (`AnnouncementPaymentRails.offerable`), espèces selon `cashAccepted`, jamais vide.

### Events Spring publiés / écoutés

Aucun.

### Pièges et points d'attention

- `NUMERIC(9,6)` en base impose `BigDecimal` dans l'entité : le profil de test H2 (`ddl-auto: create`) ne le voit pas, seuls le profil e2e (Testcontainers, Flyway, `validate`) et la prod le détectent. Lancer `CucumberE2ETest` après toute modification de mapping.
- Sémantique PUT « remplacement total » : un build d'app antérieur (60) qui édite un modèle créé par un build récent en efface les nouveaux champs (il ne les envoie pas). À couvrir en recette pendant la période de flotte mixte.
- `handoverLeadDays` : 0 à 7 côté modèle, 0 à 3 côté récurrence (`TripRecurrenceRequest`). Aligner quand l'écran de récurrence sera étendu.
- `cash_accepted` sera retiré par une migration ultérieure, une fois le build d'app qui envoie `acceptedPaymentMethods` en boutique.

## Critères d'acceptation couverts

- [x] Un modèle mémorise devise, mode de tarification, moyens de paiement, négociable, refusés, note, adresses, heures, délai et codes pays : `create_fullForm_roundTripsEveryField`.
- [x] Un ancien client (corps `cashAccepted` seul) est accepté avec les défauts du formulaire vierge : `create_legacyBody_isAcceptedWithDefaults`.
- [x] Les bornes de prix et les rails suivent la devise du modèle : `create_priceBoundedInTemplateCurrencyNotActiveOne`, `create_mobileMoneyOutsideCfa_throws422`, `create_legacyClientInCfaCurrency_dropsCardKeepsCash`.
- [x] Les récurrences publient avec leurs conditions : `generate_usesTheRecurrenceOwnConditions`, `generate_cfaRecurrenceWithMobileMoneyAccount_offersMobileMoney`, `generate_handoverLeadBeyondHorizon_fallsBackToDeparture`.
- [x] Migration additive prouvée sur PostgreSQL : `V257MigrationTest`.

## Tests

- `./mvnw test` → 4426 tests, 0 échec, 0 erreur, 7 skippés.
- `./mvnw test jacoco:report` → couverture globale lignes 92,68 % (instructions 91 %).
- Tests ajoutés ou modifiés : `V257MigrationTest`, `TripTemplateServiceTest`, `TripTemplateControllerIntegrationTest`, `TripRecurrenceServiceTest`.

## Décisions techniques

- **Jours plutôt qu'heures** pour le délai de remise : le formulaire de création choisit un jour limite (converti à l'heure du départ le jour même, 23 h 59 sinon) et la récurrence stocke déjà des jours ; des heures auraient été inexprimables à l'application.
- **Récurrence sur ses propres champs** plutôt que relire le modèle source : la récurrence est déjà la copie éditable du modèle, relire le modèle créerait une double source de vérité.
- **`TripTemplatePayload`** : les deux records de requête sont identiques ; l'interface évite un `applyFields` à 27 paramètres positionnels sans introduire de classe de base.
- **Erreurs métier en `YadonyBusinessException`** : l'ancien `ResponseStatusException` ne produisait pas de `ProblemDetail` avec `code` ; le passage aligne le modèle sur le reste de l'API.
