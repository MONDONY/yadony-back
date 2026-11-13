---
name: yadony-back-rules
description: Règles obligatoires pour le backend yadony (Spring Boot 3.4.x + Java 21). À lire AVANT toute modification du backend. Couvre architecture, entités, sécurité, migrations, gestion d'erreurs et patterns Spring.
---

# Règles Backend yadony — Spring Boot

> Lire ce fichier INTÉGRALEMENT avant toute modification du backend.
> Référence complémentaire : `/home/a-diakite/Desktop/MyProject/my_app/.ai-instructions.md`

---

## Stack

- **Framework :** Spring Boot 3.4.x (Java 21)
- **Base de données :** PostgreSQL 16 + Flyway migrations
- **Auth :** Firebase Authentication (stateless, token JWT vérifié à chaque requête)
- **Cache :** Caffeine uniquement — **JAMAIS Redis en MVP**
- **Paiements :** Stripe Connect (escrow manuel)
- **Stockage :** Hetzner Object Storage (S3-compatible)
- **Monitoring :** Sentry

---

## Structure des packages (Package-per-Feature)

```
com.yadony.api/
├── config/          # SecurityConfig, FirebaseConfig, CacheConfig
├── common/          # BaseEntity, StorageService, GlobalExceptionHandler
├── auth/            # UserEntity, UserRepository, FirebaseTokenFilter
├── kyc/             # Stripe Identity
├── matching/        # Annonces + offres (traveler ↔ sender)
├── cancellation/    # PACKAGE DÉDIÉ — jamais dans matching/
├── tracking/        # QR code + suivi colis
├── payments/        # Stripe escrow, virements
├── notifications/   # FCM + SMS fallback
├── disputes/        # Litiges
└── admin/           # Endpoints admin
```

### Règles d'architecture

- ✅ Un package = une feature métier
- ✅ Communication inter-packages via Spring Application Events UNIQUEMENT
- ❌ Ne JAMAIS injecter un service d'un autre package directement
- ✅ Logique partagée → `common/` uniquement (jamais `Utils.java` générique)
- ❌ La logique d'annulation N'EST PAS dans `matching/` → elle est dans `cancellation/`

---

## Entités et Base de données

### Règle entités

```java
// Toutes les entités DOIVENT étendre BaseEntity
// BaseEntity fournit : UUID id, createdAt, updatedAt, deletedAt

@Entity
@Where(clause = "deleted_at IS NULL")  // OBLIGATOIRE pour le soft-delete
public class UserEntity extends BaseEntity {
    // ...
}
```

### Soft Delete — OBLIGATOIRE

```java
// JAMAIS de DELETE physique sur les entités métier
// Toujours utiliser le soft-delete :
user.softDelete(); // met à jour deletedAt
userRepository.save(user);

// Jamais :
userRepository.delete(user); // ❌ INTERDIT
```

### Migrations Flyway

| Migration | Contenu |
|-----------|---------|
| V1 | `users`, `user_roles` |
| V2 | `kyc_schema`, `kyc_verifications` (colonnes chiffrées AES-256) |
| V3 | `announcements`, `bids` |
| V4 | `tracking_events` |
| V5 | `payments` |
| V6 | `disputes` |
| V7 | `audit_log` + trigger d'immuabilité |
| V8 | `cancellations`, `rematch_suggestions` |
| V9+ | Nouvelles migrations uniquement |

**RÈGLE ABSOLUE :** Ne JAMAIS modifier une migration existante.
Toujours créer une nouvelle migration V(n+1).

### Audit Log

- Table `audit_log` rendue immuable par trigger PostgreSQL
- Ne JAMAIS tenter de modifier ou supprimer des entrées d'audit
- Toujours créer des entrées d'audit pour les actions significatives

---

## Authentification et Sécurité

### Firebase Token Filter

```java
// FirebaseTokenFilter extends OncePerRequestFilter
// Vérifie le token Firebase sur CHAQUE requête (sauf endpoints publics)
// L'app est 100% stateless — jamais de sessions serveur
```

### Endpoints publics

```
/api/v1/auth/**
/actuator/health
/tracking/{token}
/api/v1/kyc/webhook
/api/v1/ratings/recipient
```

### RBAC

- Roles : `ROLE_SENDER`, `ROLE_TRAVELER`, `ROLE_ADMIN`
- Un utilisateur peut avoir plusieurs rôles (SENDER + TRAVELER)
- `@PreAuthorize("hasRole('ROLE')")` sur les méthodes controller
- Tous les endpoints `/api/v1/admin/**` → `@PreAuthorize("hasRole('ADMIN')")`

### Règles de sécurité

- Toujours valider côté serveur (même si validé côté client)
- Valeur déclarée max : 500€ → rejeter avec HTTP 422
- Vérifier la propriété des ressources avant modification
- Rate limiting géré dans Nginx (pas Spring Boot)

---

## Gestion des erreurs

```java
// TOUJOURS retourner ProblemDetail (RFC 7807)
// JAMAIS retourner String ou Map brut pour les erreurs
// Content-Type: application/problem+json

// Exemple dans GlobalExceptionHandler :
@ExceptionHandler(ResourceNotFoundException.class)
public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setTitle("Ressource non trouvée");
    return problem;
}
```

**Règle :** Toutes les exceptions passent par `GlobalExceptionHandler` dans `common/`.
Jamais de `throw` brut qui sort d'un Controller.

---

## Patterns Spring Events (communication inter-packages)

```java
// Package tracking/ publie :
@Component
public class TrackingService {
    private final ApplicationEventPublisher eventPublisher;

    public void confirmDelivery(String bidId) {
        // ... logique tracking
        eventPublisher.publishEvent(new DeliveryConfirmedEvent(bidId));
    }
}

// Package payments/ écoute :
@Component
public class DeliveryEventListener {
    @EventListener
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        // Capturer le PaymentIntent
    }
}

// Autres events du projet :
// - BidAcceptedEvent
// - TripCancelledEvent
// - DisputeOpenedEvent
```

---

## Stripe Payments

- `capture_method: manual` pour TOUS les PaymentIntents (mode escrow)
- Commission : 12% via `application_fee_amount`
- **JAMAIS capturer sans `DeliveryConfirmedEvent`** (sauf admin force-release à J+48, et colis bateau figé DEPOSIT : versé au `ParcelDepositedEvent`, puis aucun remboursement automatique, 409 `deposit-paid-no-refund`)
- Toujours valider la signature webhook avant traitement

---

## Stockage fichiers (Hetzner S3)

- Service : `StorageService.java` dans `com.yadony.api.common`
- QR photos : `tracking/{bidId}/{timestamp}_{eventType}.jpg`
- KYC docs : `kyc/{userId}/{timestamp}_{documentType}.jpg`
- **TOUJOURS utiliser des presigned URLs** — jamais d'URLs directes publiques

---

## Endpoint Auth — logique métier

### `DELETE /auth/me` — Suppression de compte

```java
// Soft-delete en base + suppression Firebase Auth
public void deleteAccount(String firebaseUid) {
    UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    user.softDelete();
    userRepository.save(user);
    firebaseAuth.deleteUser(firebaseUid); // suppression Firebase
}
```

### `POST /auth/register` — Inscription

- Si l'utilisateur Firebase existe déjà en base → retourner l'utilisateur existant (idempotent)
- Créer une nouvelle entrée uniquement si `firebase_uid` absent de la base

### `GET /auth/me` — Profil utilisateur

- Retourne 404 si l'utilisateur Firebase n'est pas inscrit en backend
- Ce 404 est utilisé par le frontend pour détecter un nouveau compte

---

## Commandes utiles

```bash
# Démarrer en mode dev
./mvnw spring-boot:run -Dspring.profiles.active=dev

# Lancer les tests
./mvnw test

# Build JAR
./mvnw package -DskipTests

# PostgreSQL Docker
docker compose -f docker-compose.dev.yml up -d
docker exec -it yadony_db psql -U yadony -d yadony_dev

# Vérifier migrations Flyway
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

---

## Checklist avant tout commit backend

- [ ] Entités métier → soft delete uniquement
- [ ] Migrations → nouvelle V(n+1) si changement de schéma
- [ ] Erreurs → `ProblemDetail` RFC 7807
- [ ] Communication inter-packages → Spring Events
- [ ] Actions significatives → entrées `audit_log`
- [ ] Endpoints admin → `@PreAuthorize("hasRole('ADMIN')")`
- [ ] Fichiers KYC → presigned URLs uniquement
- [ ] Pas de capture PaymentIntent sans `DeliveryConfirmedEvent`, sauf colis bateau figé DEPOSIT : versé au `ParcelDepositedEvent`
