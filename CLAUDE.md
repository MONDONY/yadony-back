# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project: yadony Backend (Spring Boot)

**yadony-back** est le backend REST API de la marketplace P2P yadony, permettant de connecter des voyageurs avec des expéditeurs de la diaspora africaine pour le transport de colis vers l'Afrique.

**Stack:**
- Framework: Spring Boot 3.4.x
- Java: 21
- Database: PostgreSQL 16
- Migrations: Flyway
- Auth: Firebase Authentication (token validation)
- Payments: Stripe Connect Marketplace + Stripe Identity
- Storage: Cloudflare R2 (S3-compatible)
- Cache: Caffeine (in-memory)
- Notifications: Firebase FCM + SMS (Africa's Talking / Twilio)
- Monitoring: Sentry

---

## Commands

### Development

```bash
# Start application in dev mode (requires Docker Compose for PostgreSQL)
source .env.dev && set +a && ./mvnw spring-boot:run -Dspring.profiles.active=dev  

# Start with live reload
source .env.dev && set +a && ./mvnw spring-boot:run -Dspring.profiles.active=dev -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=AuthControllerTest

# Run specific test method
./mvnw test -Dtest=AuthControllerTest#testRegisterUser

# Build JAR (skip tests)
./mvnw package -DskipTests

# Clean and rebuild
./mvnw clean install
```

### Docker & Database

```bash
# Start PostgreSQL via Docker Compose
docker compose -f docker-compose.dev.yml up -d

# Stop containers
docker compose -f docker-compose.dev.yml down

# View logs
docker compose -f docker-compose.dev.yml logs -f

# Access PostgreSQL shell
docker exec -it yadony_db psql -U yadony -d yadony_dev

# Check Flyway migration history
docker exec -it yadony_db psql -U yadony -d yadony_dev -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"

# Reset database (CAUTION: deletes all data)
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up -d
```

### Flyway Migrations

```bash
# Apply pending migrations
./mvnw flyway:migrate

# Get migration status
./mvnw flyway:info

# Validate migrations
./mvnw flyway:validate

# Clean database (CAUTION: deletes all data)
./mvnw flyway:clean
```

---

## Architecture

### Package Structure (Package-per-Feature)

```
com.yadony.api/
├── config/
│   ├── SecurityConfig.java          # Spring Security configuration
│   ├── FirebaseConfig.java          # Firebase Admin SDK initialization
│   ├── CacheConfig.java             # Caffeine cache configuration
│   ├── StorageConfig.java           # Configuration du client S3 (Cloudflare R2)
│   └── StripeConfig.java            # Stripe API configuration
├── common/
│   ├── BaseEntity.java              # Abstract entity with UUID, timestamps, soft delete
│   ├── PageResponse.java            # Paginated response wrapper
│   ├── GlobalExceptionHandler.java  # RFC 7807 error handling
│   └── StorageService.java          # S3 file upload/download/delete
├── auth/
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── UserEntity.java
│   ├── UserRepository.java
│   ├── FirebaseTokenFilter.java     # Token validation filter
│   └── dto/
├── kyc/
│   ├── KycController.java
│   ├── KycService.java
│   ├── KycVerificationEntity.java   # In kyc_schema with encrypted columns
│   ├── KycRepository.java
│   ├── StripeIdentityService.java   # Stripe Identity integration
│   └── dto/
├── matching/
│   ├── AnnouncementController.java
│   ├── AnnouncementService.java
│   ├── AnnouncementEntity.java
│   ├── BidController.java
│   ├── BidService.java
│   ├── BidEntity.java
│   └── dto/
├── cancellation/                     # DEDICATED package (not in matching/)
│   ├── CancellationController.java
│   ├── CancellationService.java
│   ├── CancellationEntity.java
│   ├── RematchService.java
│   └── dto/
├── tracking/
│   ├── TrackingController.java
│   ├── TrackingService.java
│   ├── TrackingEventEntity.java
│   ├── RecipientController.java     # Public HTML page for recipient
│   ├── events/
│   │   └── DeliveryConfirmedEvent.java
│   └── dto/
├── payments/
│   ├── PaymentController.java
│   ├── PaymentService.java
│   ├── PaymentEntity.java
│   ├── StripeService.java           # Stripe Connect escrow
│   ├── WaveService.java             # Wave API for African payouts
│   ├── OrangeMoneyService.java      # Orange Money API
│   ├── DeliveryEventListener.java   # Listens to DeliveryConfirmedEvent
│   └── dto/
├── notifications/
│   ├── NotificationController.java
│   ├── NotificationDispatcher.java  # Central notification orchestrator
│   ├── FcmService.java              # Firebase Cloud Messaging
│   ├── SmsService.java              # Africa's Talking + Twilio fallback
│   └── dto/
├── disputes/
│   ├── DisputeController.java
│   ├── DisputeService.java
│   ├── DisputeEntity.java
│   └── dto/
└── admin/
    ├── AdminController.java
    ├── AdminService.java
    └── dto/
```

**Règle fondamentale:** Un package = une feature métier. Pas de `Utils.java` générique - le code partagé va dans `common/`.

---

## Core Principles

### 1. Authentication & Authorization

#### FirebaseTokenFilter (OBLIGATOIRE)

Tous les endpoints protégés passent par `FirebaseTokenFilter extends OncePerRequestFilter`:

```java
@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) {
        String token = extractBearerToken(request);
        if (token != null) {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(token);
            String uid = decodedToken.getUid();

            // Load user and check status
            User user = userRepository.findByFirebaseUid(uid);
            if (user.getStatus() == UserStatus.SUSPENDED ||
                    user.getStatus() == UserStatus.BANNED) {
                throw new ForbiddenException("Account suspended");
            }

            // Set authentication in SecurityContext
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    user, null, getAuthorities(user)
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
```

**Points clés:**
- Le backend est 100% stateless (pas de session serveur)
- Le token Firebase est validé à chaque requête
- Le statut utilisateur (SUSPENDED/BANNED) est vérifié avant chaque action

#### Endpoints publics (sans authentification)

- `/api/v1/auth/**`
- `/actuator/health`
- `/tracking/{token}` (page HTML destinataire)
- `/api/v1/kyc/webhook` (webhook Stripe Identity)
- `/api/v1/ratings/recipient` (évaluation anonyme du destinataire)

#### RBAC (Role-Based Access Control)

3 rôles disponibles:
- `ROLE_SENDER` - Expéditeur de colis
- `ROLE_TRAVELER` - Voyageur transporteur
- `ROLE_ADMIN` - Administrateur plateforme

Un utilisateur peut cumuler SENDER + TRAVELER.

```java
@RestController
@RequestMapping("/api/v1/bids")
public class BidController {

    @PostMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<BidResponse> createBid(@RequestBody BidRequest request) {
        // Only travelers can create bids
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<BidResponse> getBid(@PathVariable UUID id) {
        // Both senders and travelers can view bids
    }
}

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")  // Applied to all endpoints
public class AdminController {
    // All admin endpoints require ADMIN role
}
```

### 2. Error Handling (RFC 7807 ProblemDetail)

**TOUJOURS** retourner `ProblemDetail` depuis `GlobalExceptionHandler`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setType(URI.create("https://yadony.app/errors/not-found"));
        problem.setTitle("Resource Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage()
        );
        problem.setType(URI.create("https://yadony.app/errors/validation"));
        problem.setTitle("Validation Error");
        problem.setProperty("violations", ex.getViolations());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
```

**Jamais de:**
- `return "Error message";`
- `return Map.of("error", "message");`
- Exception stack traces dans la réponse

### 3. JPA Entities

Toutes les entités héritent de `BaseEntity`:

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
```

**Soft Delete obligatoire:**

```java
@Entity
@Table(name = "users")
@Where(clause = "deleted_at IS NULL")
public class UserEntity extends BaseEntity {
    // Never do physical DELETE on business entities

    public void softDelete() {
        this.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
    }
}
```

### 4. Event-Driven Architecture

Communication entre packages via Spring Application Events:

```java
// tracking/events/DeliveryConfirmedEvent.java
public class DeliveryConfirmedEvent {
    private UUID bidId;
    private UUID senderId;
    private UUID travelerId;
    private LocalDateTime confirmedAt;
    // getters, constructor
}

// tracking/TrackingService.java
@Service
public class TrackingService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public void confirmDelivery(UUID bidId) {
        // ... business logic

        // Publish event
        eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                bidId, senderId, travelerId, LocalDateTime.now()
        ));
    }
}

// payments/DeliveryEventListener.java
@Component
public class DeliveryEventListener {
    @Autowired
    private PaymentService paymentService;

    @EventListener
    @Async
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        // Trigger escrow release
        paymentService.releaseEscrow(event.getBidId());
    }
}
```

**Règle critique:** Ne JAMAIS injecter directement `PaymentService` dans `TrackingService`. Utiliser les events pour découpler les packages.

### 5. Audit Log (Immutable)

Toute action métier significative doit créer une entrée dans `audit_log`:

```java
@Service
public class AuditService {
    @Autowired
    private AuditLogRepository auditLogRepository;

    public void logAction(String entityType, UUID entityId, String action, UUID actorId, Map<String, Object> payload) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action);
        entry.setActorId(actorId);
        entry.setPayload(payload); // JSONB column
        entry.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));

        auditLogRepository.save(entry);
    }
}
```

**La table `audit_log` a un trigger PostgreSQL qui interdit UPDATE et DELETE:**

```sql
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'Les entrées audit_log sont immuables';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();
```

### 6. Database Migrations (Flyway)

**Ordre impératif des migrations:**

| Migration | Tables créées |
|-----------|--------------|
| V1__create_users.sql | `users`, `user_roles` |
| V2__create_kyc_schema.sql | Schéma `kyc_schema`, `kyc_verifications` |
| V3__create_announcements_bids.sql | `announcements`, `bids` |
| V4__create_tracking_events.sql | `tracking_events` |
| V5__create_payments.sql | `payments` |
| V6__create_disputes.sql | `disputes` |
| V7__create_audit_log.sql | `audit_log` + trigger immutabilité |
| V8__create_cancellations.sql | `cancellations`, `rematch_suggestions` |

**Règles absolues:**
- Ne JAMAIS modifier une migration existante
- Toujours créer une nouvelle migration V(n+1) pour les changements
- Tester les migrations sur une base vide avant de commit

### 7. KYC Security

Les données KYC sont sensibles et isolées:

```java
@Entity
@Table(name = "kyc_verifications", schema = "kyc_schema")
public class KycVerificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "stripe_verification_session_id")
    private String stripeVerificationSessionId;

    @Column(name = "id_number", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)  // AES-256 encrypted
    private String idNumber;

    @Column(name = "selfie_url")
    private String selfieUrl;  // Presigned URL only, never direct URL

    @Column(name = "verification_status")
    @Enumerated(EnumType.STRING)
    private VerificationStatus status;
}
```

**Règles KYC:**
- Schéma PostgreSQL séparé `kyc_schema`
- Colonnes sensibles chiffrées AES-256
- Photos stockées dans `kyc/{userId}/` sur Cloudflare R2
- Toujours générer des URLs signées avec expiration courte (1h max)
- Ne JAMAIS exposer d'URL publique directe

### 8. Stripe Payments (Escrow)

Configuration obligatoire pour tous les PaymentIntents:

```java
@Service
public class StripeService {

    public PaymentIntent createEscrowPayment(UUID bidId, BigDecimal amount, UUID senderId, UUID travelerId) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount.multiply(BigDecimal.valueOf(100)).longValue()) // cents
                .setCurrency("eur")
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)  // OBLIGATOIRE
                .setApplicationFeeAmount(calculateFee(amount))  // 12% commission
                .putMetadata("bid_id", bidId.toString())
                .putMetadata("sender_id", senderId.toString())
                .putMetadata("traveler_id", travelerId.toString())
                .build();

        return PaymentIntent.create(params);
    }

    @EventListener
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        // Only capture after delivery confirmation
        PaymentIntent pi = getPaymentIntentForBid(event.getBidId());
        pi.capture();
    }
}
```

**Règles Stripe:**
- `capture_method: manual` obligatoire (mode escrow)
- Commission yadony dans `application_fee_amount` (12% configurable)
- Valider la signature des webhooks Stripe avant tout traitement
- **Montant net TOUJOURS recalculé côté serveur** (grid items snapshotés + poids × prix/kg) dans `PaymentService.createEscrow`. Un `totalNetEur` reçu du client est purement indicatif et recoupé (422 `amount-mismatch` sinon) — ne jamais le prendre comme source de vérité du montant.

**Invariant de capture (separate charges & transfers) :** dans le modèle actuel non-legacy, le `PaymentIntent` est **capturé dès l'acceptation du bid** (`BidAcceptedEventListener`) — l'autorisation Stripe expirant ~7 j, insuffisant pour un acheminement vers l'Afrique. La protection escrow tient au **Transfer** : le voyageur n'est payé qu'au `Transfer.create` déclenché par `DeliveryConfirmedEvent` (`DeliveryEventListener.releaseV2`) ou force-release admin J+48. L'invariant réel est donc « pas de **versement voyageur** sans `DeliveryConfirmedEvent` », pas « pas de capture ». (Legacy destination-charge : capture bien à la livraison.) Vérifier que les CGU / l'UI de paiement informent l'expéditeur qu'il est débité à l'acceptation.

### 9. File Storage (Cloudflare R2)

```java
@Service
public class StorageService {
    @Autowired
    private S3Client s3Client;

    public String uploadFile(MultipartFile file, String prefix) throws IOException {
        String key = prefix + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));

        return key;
    }

    public String generatePresignedUrl(String key, Duration expiration) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
```

**Préfixes obligatoires:**
- Photos QR scan: `tracking/{bidId}/{timestamp}_{eventType}.jpg`
- Documents KYC: `kyc/{userId}/{timestamp}_{documentType}.jpg`

### 10. Notifications

Architecture centralisée via `NotificationDispatcher`:

```java
@Service
public class NotificationDispatcher {
    @Autowired
    private FcmService fcmService;

    @Autowired
    private SmsService smsService;

    public void sendCriticalNotification(UUID userId, String title, String body, Map<String, String> data) {
        // Try FCM first
        boolean fcmSent = fcmService.sendNotification(userId, title, body, data);

        if (fcmSent) {
            // Schedule SMS fallback if no ACK in 60 seconds
            scheduleSmsFallback(userId, body, 60);
        } else {
            // FCM failed, send SMS immediately
            smsService.sendSms(userId, body);
        }
    }
}
```

**Providers:**
- Push: Firebase Cloud Messaging
- SMS principal: Africa's Talking (optimisé Afrique)
- SMS fallback: Twilio

### 11. Schedulers (Cron Jobs)

```java
@Component
@EnableScheduling
public class ScheduledTasks {

    // Alert H-2 before planned handover
    @Scheduled(fixedRate = 900000) // Every 15 minutes
    public void checkUpcomingHandovers() {
        // Find bids with handover_time in next 2 hours
        // Send reminder notifications
    }

    // Auto-release escrow after 48h without confirmation
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void processEscrowTimeouts() {
        // Find payments older than 48h without delivery confirmation
        // Force release and notify
    }

    // Auto-resolve disputes after 72h SLA
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void resolveExpiredDisputes() {
        // Find disputes older than 72h without resolution
        // Apply automated decision
    }

    // No-show detection for travelers
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void detectNoShows() {
        // Find bids with past handover_time and no scan
        // Trigger cancellation flow
    }
}
```

**Règle critique:** Chaque scheduler doit être idempotent - toujours vérifier l'état avant d'agir.

### 12. Cache (Caffeine)

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES));
        return cacheManager;
    }
}

@Service
public class AnnouncementService {

    @Cacheable(value = "announcements", key = "#corridor + '_' + #page")
    public PageResponse<AnnouncementDTO> getAnnouncements(String corridor, int page) {
        // Read-only endpoint with high traffic
    }

    @CacheEvict(value = "announcements", allEntries = true)
    public AnnouncementDTO createAnnouncement(AnnouncementRequest request) {
        // Invalidate cache on mutation
    }
}
```

**Pas de Redis en MVP** - Caffeine uniquement (in-memory).

---

## Critical Rules

### NEVER:

1. ❌ Commit directly to `main` — always work on a feature branch and merge via PR
2. ❌ Store secrets (API keys, tokens, passwords) in code or git
3. ❌ Perform physical DELETE on business entities (soft delete only)
4. ❌ Modify existing Flyway migrations (create V(n+1) instead)
4. ❌ Modify or delete `audit_log` entries (immutable by design)
5. ❌ Inject services directly across packages (use Spring Events)
6. ❌ Put cancellation logic in `matching/` package (dedicated `cancellation/` package)
7. ❌ Return raw exceptions from controllers (use `GlobalExceptionHandler`)
8. ❌ Capture Stripe PaymentIntent without `DeliveryConfirmedEvent` (except admin force-release)
9. ❌ Expose direct public URLs for KYC files (presigned URLs only)
10. ❌ Use Redis in MVP (Caffeine only)
11. ❌ Create generic `Utils.java` (shared code goes in `common/`)
12. ❌ Skip `@PreAuthorize` on admin endpoints
13. ❌ Grant access when DB is unavailable in `FirebaseTokenFilter` — toujours retourner 503 et vider le `SecurityContext` si le chargement de l'utilisateur échoue
14. ❌ Exposer `phoneNumber` dans `TravelerProfileDto` ou tout DTO public — le numéro de téléphone ne doit jamais quitter le backend
15. ❌ Utiliser le premier élément de `X-Forwarded-For` pour résoudre l'IP client — toujours prendre le **dernier** élément (ajouté par le proxy de confiance), sinon le client peut spoofer son IP
16. ❌ Traiter un webhook Stripe sans vérifier d'abord dans `processed_stripe_events` — double traitement possible (Stripe rejoue les webhooks)
17. ❌ Créer un compte Stripe Connect sans verrou pessimiste (`findByIdForUpdate`) — race condition possible si deux requêtes simultanées
18. ❌ Utiliser `@EventListener` seul sur les listeners de paiement — toujours utiliser `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)` pour éviter de lire des données non encore commitées
19. ❌ Capturer un `PaymentIntent` sans passer par `markCapturedIfEscrow()` en premier — protection atomique contre la double capture
20. ❌ Laisser `yadony.kyc.enforce` ou `yadony.stripe.enforce` à `false` en prod — ces flags doivent être `true` en production
21. ❌ Commenter ou désactiver les vérifications KYC/Stripe avec un TODO — utiliser les flags `yadony.kyc.enforce` et `yadony.stripe.enforce` à la place
22. ❌ Laisser du code mort dans le repo — un service/controller/DTO sans aucun appelant (frontend ou backend) doit être supprimé, pas laissé "au cas où". Vérifier par `grep` avant de le garder ; supprimer aussi ses tests dédiés

### ALWAYS:

1. ✅ Validate data server-side even if validated client-side
2. ✅ Return RFC 7807 `ProblemDetail` for all errors
3. ✅ Check user owns resource before modification (prevent unauthorized access)
4. ✅ Create `audit_log` entry for significant business actions
5. ✅ Use Bean Validation (`@NotNull`, `@Size`, etc.) on DTOs
6. ✅ Verify Stripe webhook signatures before processing
7. ✅ Use `@Where(clause = "deleted_at IS NULL")` on soft-deletable entities
8. ✅ Make schedulers idempotent
9. ✅ Use events for cross-package communication
10. ✅ Isolate KYC data in `kyc_schema` with encryption
11. ✅ Vérifier l'ownership avant tout accès à une ressource — ex. `GET /payments/bid/{bidId}` doit confirmer que le caller est l'expéditeur ou le voyageur du bid
12. ✅ Enregistrer les events Stripe dans `processed_stripe_events` **avant** de les traiter (insert-first, then process) pour garantir l'idempotence
13. ✅ Demander les capacités `card_payments` ET `transfers` à la création d'un compte Stripe Connect Express — obligatoire pour le pattern `on_behalf_of`
14. ✅ Vérifier que `yadony.kyc.enforce` et `yadony.stripe.enforce` sont bien désactivés dans `application-test.yml` pour ne pas bloquer les tests d'intégration

---

## Testing — OBLIGATOIRE après tout changement

> Après chaque feature, bug fix ou modification de code : vérifier les tests, corriger les cassés, ajouter ceux qui manquent. Couverture minimale : **90 %**.

### Commandes

```bash
# Lancer tous les tests (obligatoire avant tout commit)
./mvnw test

# Lancer un test spécifique
./mvnw test -Dtest=BidServiceTest
./mvnw test -Dtest=BidServiceTest#testCreateBid_Success

# Générer le rapport de couverture JaCoCo
./mvnw test jacoco:report
# Ouvrir : target/site/jacoco/index.html → couverture globale ≥ 90 %

# Vérifier la couverture en ligne de commande
./mvnw verify   # échoue si couverture < seuil configuré dans pom.xml
```

### Types de tests requis

| Code introduit | Test requis |
|---|---|
| Nouveau Service (logique métier) | Unit test avec `@ExtendWith(MockitoExtension.class)`, mock des repositories |
| Nouveau Controller (endpoint) | Integration test `@SpringBootTest` + `MockMvc`, mock `FirebaseAuth` |
| Nouvelle règle de validation | Unit test couvrant cas valide + chaque cas invalide |
| Nouvelle Flyway migration | Test sur base H2 ou PostgreSQL de test — migration appliquée sans erreur |
| Bug fix | Test de régression reproduisant le bug avant le fix |
| Nouveau Spring Event publié/écouté | Test vérifiant que l'event est publié et que le listener réagit correctement |

### Règles

1. **Tests cassés** → corriger avant tout commit. Ne jamais utiliser `@Disabled` sans raison documentée.
2. **Nouveau code sans test** → non accepté. Les tests vont dans le même commit.
3. **Couverture < 90 %** → ajouter des tests pour remonter avant de marquer la tâche complète.
4. **Ne jamais builder avec `-DskipTests`** sauf pour un déploiement d'urgence explicitement autorisé.
5. **Mocks** : `@MockBean` pour les dépendances Spring en integration test, `Mockito.mock()` en unit test. Ne jamais appeler une vraie DB/API externe dans les unit tests.
6. **Profil de test** : toujours `@ActiveProfiles("test")` pour les integration tests — utilise H2 in-memory ou base PostgreSQL dédiée.

### Structure des tests

```
src/test/java/com/yadony/api/
├── matching/
│   ├── AnnouncementServiceTest.java    # unit — mock AnnouncementRepository
│   ├── BidServiceTest.java             # unit — mock BidRepository, UserRepository
│   └── BidControllerIntegrationTest.java  # integration — MockMvc
├── tracking/
│   ├── TrackingServiceTest.java
│   └── TrackingControllerIntegrationTest.java
├── payments/
│   └── PaymentServiceTest.java
└── auth/
    └── AuthControllerIntegrationTest.java
```

### Exemple integration test (template)

```java
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public class BidControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private FirebaseAuth firebaseAuth;

    @Test
    void testCreateBid_Success() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(mockFirebaseToken());

        mockMvc.perform(post("/api/v1/bids")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void testCreateBid_DeclaredValueTooHigh_Returns422() throws Exception {
        // ... test de la règle métier > 500€
    }
}
```

---

## API Versioning

Tous les endpoints commencent par `/api/v1/`:

```yaml
# application.yml
server:
  servlet:
    context-path: /api/v1
```

---

## Security Checklist

Before deploying:

- [ ] All sensitive data (API keys, DB passwords) in environment variables
- [ ] All admin endpoints have `@PreAuthorize("hasRole('ADMIN')")`
- [ ] All user-owned resources check ownership before modification
- [ ] All Stripe webhooks validate signature
- [ ] All KYC files use presigned URLs (never direct public URLs)
- [ ] Rate limiting configured in Nginx (30 req/min general, 5 req/min auth/kyc)
- [ ] Max declared value enforced (500€ limit)
- [ ] Offline timestamps validated (reject future timestamps)
- [ ] CORS configured properly for production frontend domain
- [ ] Sentry configured for error monitoring
- [ ] `yadony.kyc.enforce=true` en prod — bloque bid/annonce sans KYC vérifié
- [ ] `yadony.stripe.enforce=true` en prod — bloque annonce sans compte bancaire Stripe configuré
- [ ] `INTERNAL_SHARED_SECRET` généré avec `openssl rand -hex 32` (jamais la valeur par défaut `local-dev-secret-change-me`)
- [ ] Webhook listeners utilisent `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`
- [ ] Table `processed_stripe_events` créée (migration V48) — idempotence webhooks
- [ ] Colonne `payments.captured_at` créée (migration V48) — protection double capture
- [ ] Colonne `users.version` créée (migration V49) — optimistic locking

---

## Documentation obligatoire à la fin de chaque story

**INSTRUCTION IMPÉRATIVE:** À la fin de l'implémentation de chaque story (quand elle est complète à 100%), Claude DOIT créer un fichier de documentation dans le dossier `docs/stories-done/` décrivant ce qui a été fait côté **backend**.

### Nom du fichier
`docs/stories-done/story-<epic>.<numero>-<slug>.md`

Exemple: `docs/stories-done/story-1.3-firebase-auth.md`

### Contenu obligatoire du fichier

```markdown
# Story <epic>.<numero> — <Titre de la story> (Backend)

**Date:** YYYY-MM-DD
**Status:** ✅ Complète

## Résumé
Une ou deux phrases décrivant ce qui a été implémenté et pourquoi.

## Fichiers créés
- `chemin/vers/fichier.java` — rôle du fichier dans l'architecture

## Fichiers modifiés
- `chemin/vers/fichier.java` — ce qui a changé et pourquoi

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
Décrire étape par étape ce qui se passe quand l'utilisateur déclenche cette feature :
1. Le client envoie X → l'endpoint Y reçoit
2. Le service fait Z (validation, logique métier)
3. La base de données est mise à jour via JPA
4. La réponse retournée est W

### Points d'entrée API
- `POST /api/v1/...` — ce que ça fait, qui peut l'appeler (rôle requis)
- `GET /api/v1/...` — idem

### Entités JPA impliquées
- `NomEntity` → table `nom_table` — champs importants et contraintes à connaître
- Relations entre entités (FK, cascades, soft delete)

### Logique métier critique
Expliquer les règles métier non évidentes :
- Pourquoi telle validation existe
- Pourquoi telle décision a été prise (et pas une autre)
- Les cas limites gérés et comment

### Events Spring publiés / écoutés
- `NomEvent` publié quand X → écouté par Y pour faire Z

### Pièges et points d'attention
Ce qu'il faut savoir pour ne pas casser cette feature en la modifiant :
- Contraintes de base de données à respecter
- Ordre d'opérations important
- Comportements Hibernate non évidents (ex: @Immutable, flush timing)
- Trigger PostgreSQL ou contrainte d'unicité à connaître

## Critères d'acceptation couverts
- [x] Given/When/Then 1 — comment c'est implémenté
- [x] Given/When/Then 2 — comment c'est implémenté

## Tests
- `./mvnw test` → tous les tests passent (0 rouge)
- `./mvnw test jacoco:report` → couverture ≥ 90 % (vérifiée sur le rapport)
- Tests ajoutés : liste des classes de test créées ou modifiées

## Décisions techniques
Pour chaque décision non triviale : le choix fait, les alternatives écartées, et pourquoi.
```

### Règles
- Ne pas créer le fichier avant que la story soit 100% complète
- Créer le dossier `docs/stories-done/` s'il n'existe pas
- Toujours inclure les critères d'acceptation de la story originale
- **`./mvnw test` doit passer à 0 rouge avant de créer ce fichier**
- **Couverture JaCoCo ≥ 90 % obligatoire — indiquer le % dans la section Tests**
- La section "Comment ça fonctionne" doit être assez détaillée pour qu'un développeur puisse maintenir la feature sans avoir à lire tout le code

---

## Documentation

**Référence complète:** `/docs-claude/CLAUDE.md` (règles détaillées en français)

**Architecture:** `/docs-claude/docs/planning-artifacts/architecture.md`

**Stories:** `/docs-claude/docs/stories/epic-*.md`