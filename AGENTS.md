# AGENTS.md

## Projet et stack

`dony-back` est l'API REST Spring Boot de dony, marketplace P2P qui met en relation
voyageurs et expéditeurs de la diaspora africaine.

- Java 21, Spring Boot 3.4.x et Maven Wrapper.
- PostgreSQL 16 avec Flyway.
- Firebase Authentication, Spring Security et RBAC.
- Stripe Connect/Identity, stockage S3 compatible Hetzner, Caffeine, FCM/SMS et Sentry.
- API stateless, préfixée par `/api/v1`.

Avant une story, lire sa définition dans
`../docs-claude/docs/stories/epic-XX-*.md` et identifier les entités JPA, les
événements Spring, les écritures d'audit et les critères Given/When/Then concernés.

## Commandes

```bash
# Démarrage dev avec export des variables de .env.dev
./start-dev.sh

# Démarrage dev explicite
set -a; source .env.dev; set +a
./mvnw spring-boot:run -Dspring.profiles.active=dev

# Tests et couverture
./mvnw test
./mvnw test -Dtest=AuthControllerTest
./mvnw test -Dtest=AuthControllerTest#testRegisterUser
./mvnw test jacoco:report
./mvnw verify

# Build complet
./mvnw clean install

# PostgreSQL local
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml down
docker compose -f docker-compose.dev.yml logs -f
docker exec -it dony_db psql -U dony -d dony_dev

# Flyway
./mvnw flyway:info
./mvnw flyway:validate
./mvnw flyway:migrate
```

Ne pas utiliser `-DskipTests`, sauf déploiement d'urgence explicitement autorisé.
Les opérations destructives de base (`flyway:clean`, suppression des volumes Docker)
nécessitent une demande explicite.

## Architecture package-per-feature

Le code vit sous `com.dony.api`. Un package correspond à une feature métier :
`auth/`, `kyc/`, `matching/`, `cancellation/`, `tracking/`, `payments/`,
`notifications/`, `disputes/`, `admin/`, etc.

- La logique d'annulation reste exclusivement dans `cancellation/`, jamais dans
  `matching/`.
- Le code réellement partagé va dans `common/`; ne pas créer de `Utils.java`
  générique.
- Contrôleurs, services, repositories, DTO et événements restent dans le package de
  leur feature.
- Ne pas injecter un service métier d'un package dans un autre. Utiliser des Spring
  Application Events.
- Caffeine est le seul cache du MVP; ne pas introduire Redis.

## Sécurité et autorisation

- `FirebaseTokenFilter` valide le token Firebase à chaque requête et reconstruit le
  `SecurityContext`; aucune session serveur.
- `FirebaseTokenFilter` vérifie le statut utilisateur avant chaque action. Refuser
  explicitement en 403 les comptes `SUSPENDED` ou `BANNED`.
- En cas d'indisponibilité de la base pendant le chargement utilisateur, vider le
  `SecurityContext` et répondre 503, jamais autoriser par défaut.
- Rôles : `ROLE_SENDER`, `ROLE_TRAVELER`, `ROLE_ADMIN`. Protéger les contrôleurs
  avec `@PreAuthorize`; tout `/admin/**` exige `hasRole('ADMIN')`.
- Vérifier l'ownership avant toute lecture ou mutation d'une ressource utilisateur.
- Ne jamais exposer `phoneNumber` ou une autre PII dans un DTO public.
- Valider toutes les données côté serveur avec Bean Validation; le client n'est
  jamais la source de vérité.
- Les secrets et drapeaux d'environnement restent dans les variables d'environnement.
  En production, `dony.kyc.enforce` et `dony.stripe.enforce` doivent être vrais.
- Pour `X-Forwarded-For`, prendre le **dernier** élément, ajouté par le proxy de
  confiance. Ne jamais prendre le premier élément : il est contrôlable par le client
  et permettrait de spoofer l'adresse IP.
- Rejeter en HTTP 422 toute valeur déclarée supérieure à 500 €.
- Valider `offlineTimestamp` côté serveur et rejeter toute date située dans le futur.
- Rate limiting Nginx attendu : 30 requêtes/min en général, 5 requêtes/min pour
  l'authentification et le KYC.

Endpoints publics attendus : `/api/v1/auth/**`, `/actuator/health`,
`/tracking/{token}`, `/api/v1/kyc/webhook` et
`/api/v1/ratings/recipient`. Toute extension de cette liste doit être justifiée.

## Erreurs RFC 7807

- Toutes les erreurs HTTP passent par `GlobalExceptionHandler`.
- Retourner un `ProblemDetail` RFC 7807 avec
  `Content-Type: application/problem+json`.
- Ne jamais retourner de `String`, `Map` d'erreur, stack trace ou exception brute
  depuis un contrôleur.
- Utiliser le statut précis : validation métier en 422, absence en 404, conflit en
  409, interdiction en 403, dépendance indisponible en 503.

## Entités, soft delete et Flyway

- Les entités métier héritent de `BaseEntity` : UUID, `createdAt`, `updatedAt`,
  `deletedAt`.
- Suppression logique uniquement, avec filtre
  `@Where(clause = "deleted_at IS NULL")`; aucune suppression physique.
- `audit_log` est append-only et protégé par un trigger d'immutabilité : ne jamais
  `UPDATE` ni `DELETE`.
- Créer une entrée d'audit pour chaque action métier significative, sans secret ni
  PII inutile dans le payload.
- Ne jamais modifier une migration Flyway existante. Créer la prochaine migration
  `V(n+1)__description.sql` d'après la version la plus haute du dépôt.
- Valider la migration sur une base vide et sur le chemin d'upgrade avant commit.
- Les données KYC restent dans `kyc_schema`, avec les colonnes sensibles chiffrées
  AES-256.

## Événements inter-packages

- Publier des Spring Application Events pour toute communication entre features.
- Événements métier structurants : `BidAcceptedEvent`,
  `DeliveryConfirmedEvent`, `TripCancelledEvent` et `DisputeOpenedEvent`.
- Les listeners de paiement utilisent
  `@TransactionalEventListener(phase = AFTER_COMMIT)` et une transaction
  `REQUIRES_NEW` lorsque le traitement doit être isolé.
- Les listeners et schedulers sont idempotents et vérifient l'état avant d'agir.
- Tester à la fois la publication de l'événement et la réaction du listener.

## Paiements, KYC et stockage

- Les `PaymentIntent` carte utilisent `capture_method: manual`.
- Le montant est recalculé côté serveur; une valeur envoyée par le client est
  seulement indicative.
- Dans le flux actuel « separate charges and transfers », la capture a lieu à
  l'acceptation du bid afin d'éviter l'expiration de l'autorisation. Le versement au
  voyageur (`Transfer`) n'a lieu qu'après `DeliveryConfirmedEvent`, ou force-release
  admin J+48. Ne pas confondre capture et versement.
- Pas de capture ni de versement sans `DeliveryConfirmedEvent`, SAUF : force-release admin,
  colis non réclamé, et colis bateau figé DEPOSIT (`bids.payout_trigger = DEPOSIT`, figé à
  l'acceptation) : versé au `ParcelDepositedEvent` (scan DEPART avec QR de l'expéditeur et
  photo). Après ce versement, aucun remboursement automatique : annulation, refus du colis
  et annulation du trajet répondent 409 `deposit-paid-no-refund` (support, litige). Ne
  jamais verser au dépôt un colis DELIVERY.
- La commission dony est de 12 % configurable. Respecter le modèle de paiement en
  place; ne pas ajouter `application_fee_amount` à un flux qui utilise les separate
  charges and transfers.
- Vérifier la signature des webhooks Stripe puis enregistrer l'identifiant dans
  `processed_stripe_events` avant traitement.
- Avant une capture, utiliser la garde atomique `markCapturedIfEscrow()`.
- La création d'un compte Stripe Connect est verrouillée et demande les capacités
  `card_payments` et `transfers`.
- KYC : schéma séparé, chiffrement AES-256 et URLs présignées à durée courte; aucune
  URL publique directe.
- Stockage via `common/StorageService`. Préfixes :
  `tracking/{bidId}/{timestamp}_{eventType}.jpg` et
  `kyc/{userId}/{timestamp}_{documentType}.jpg`.

## Git

- Ne jamais commit directement sur `main`.
- Utiliser une branche `feature/<nom>`, `fix/<nom>` ou `chore/<nom>`.
- Ne jamais ajouter de ligne `Co-Authored-By: Codex`; les commits restent au nom du
  développeur.
- Ne pas inclure de secret, fichier d'environnement ou changement hors périmètre.

## Tests et couverture

Après toute feature, correction ou modification de code :

```bash
./mvnw test
./mvnw test jacoco:report
./mvnw verify
```

La couverture globale exigée est d'au moins 90 %. Ajouter dans le même commit :

- tests unitaires Mockito pour services et validateurs;
- tests d'intégration `@SpringBootTest`, `MockMvc` et `@ActiveProfiles("test")`
  pour les contrôleurs;
- test de régression pour chaque bug;
- tests d'application et d'upgrade pour les migrations;
- tests de publication/écoute pour les événements Spring.

Ne jamais masquer un échec avec `@Disabled`, `-DskipTests` ou une baisse du seuil.

## Definition of Done

- Tous les Given/When/Then de la story sont couverts.
- Ownership, RBAC, audit, RFC 7807, soft delete et sécurité KYC/Stripe sont vérifiés.
- Les interactions inter-packages passent par événements.
- Les migrations existantes et `audit_log` n'ont pas été altérés.
- Tous les tests passent et le rapport JaCoCo confirme une couverture globale ≥ 90 %.
- Tout nouveau code a ses tests dans le même commit.
- La documentation de story terminée est créée dans
  `docs/stories-done/story-<epic>.<numero>-<slug>.md` seulement après ces
  vérifications.
