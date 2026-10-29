# Sécurité du portefeuille, lot C2a : frontières de sécurité (Backend)

**Date:** 2026-10-29
**Status:** ✅ Complète

## Résumé
Quatrième lot des correctifs de l'audit du portefeuille du 28/10 : les points d'entrée par
lesquels un tiers pourrait créditer un solde ou toucher à l'argent sans y avoir droit.
- Les codes de recharge sont stockés hachés (HMAC) et ne se devinent plus par force brute.
- Les événements Stripe des comptes connectés ne peuvent plus créditer un solde.
- La signature des callbacks pawaPay est exigée partout hors poste local et tests.
- Deux règles d'autorisation, devenues fragiles, sont réécrites.

## Fichiers créés
- `payments/wallet/topupcode/TopupCodeHasher.java` : HMAC-SHA256 des codes, indice, garde de la clé au démarrage.
- `payments/wallet/topupcode/TopupCodeHashBackfill.java` : hache les codes antérieurs au démarrage, avant l'ouverture du serveur.
- `common/stripe/StripeConnectEvents.java` : types d'événements qu'un compte connecté a le droit d'envoyer.
- `db/migration/V360__wallet_topup_codes_hmac.sql` : colonnes `code_hash` et `code_hint`, contraintes, index unique.

## Fichiers modifiés
- `payments/wallet/topupcode/WalletTopupCodeEntity.java`, `WalletTopupCodeRepository.java` : recherche par empreinte, champ transitoire `issuedCode`.
- `payments/wallet/topupcode/WalletTopupCodeAdminService.java` : création hachée, code manuel de 10 lettres ou chiffres au moins, code généré `YD-XXXX-XXXX-XXXX`, audit réduit à l'indice.
- `payments/wallet/topupcode/WalletTopupCodeRedemptionService.java`, `WalletTopupCodeConfig.java`, `WalletTopupCodeController.java` : compteurs d'échecs par compte, par IP et global, alerte admin.
- `admin/AdminTopupCodeController.java`, `admin/dto/AdminTopupCodeResponse.java` : `code` seulement à la création, `codeHint` partout.
- `common/stripe/StripeWebhookIngestService.java`, `payments/PaymentStripeWebhookHandler.java` : filtre des événements Connect.
- `payments/pawapay/PawapayConfig.java`, `application.yml` : signature des callbacks par défaut et garde hors dev/test/e2e.
- `config/SecurityConfig.java` : `/auth/me/wallet-refund-request` en `authenticatedNonGuest()`.
- `admin/AdminWalletRefundController.java` : `hasRole('ADMIN')` répété sur chaque méthode.
- `ratelimit/RateLimitRule.java` : `TOPUP_CODE_REDEEM`, `TOPUP_CODE_REDEEM_IP`, suppléments et avenants dans les actions de paiement.
- `nginx/nginx.conf`, `nginx/nginx.staging.conf` : zone `wallet_code_redeem` (20 req/min par IP).
- `.github/workflows/deploy-prod.yml`, `deploy-staging.yml` : secret `TOPUP_CODE_HMAC_KEY` ; signature pawaPay à `true` par défaut en staging.
- Fiche assistant `code-recharge` (fr, en) : blocage après 5 essais ratés.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. **Création d'un code (admin)** :
   - le code saisi ou généré est normalisé, puis haché (`TopupCodeHasher.hash`) ;
   - l'empreinte et la fin du code (`hint`) sont stockées, jamais le texte ;
   - la réponse 201 porte `code` en clair : c'est la seule fois.
2. **Utilisation (app)** :
   - `POST /wallet/topup-codes/redeem` réserve une tentative sur trois compteurs : compte 5/h, IP 20/h, plateforme 200/h ;
   - le service cherche ensuite le code par son empreinte (`findByCodeHashForUpdate`) ;
   - un code trouvé libère les trois unités, un code inconnu les garde ;
   - un compteur plein renvoie 429 `topup-code-too-many-attempts`. Le seuil global lève une seule fois l'alerte `wallet-topup-code-bruteforce` par fenêtre d'une heure.
3. **Démarrage** : `TopupCodeHashBackfill` (un `SmartInitializingSingleton`) hache les lignes où `code_hash` est NULL et `code` non NULL, puis efface `code`. Cela se passe avant que le serveur web n'accepte une requête.
4. **Webhook Stripe** :
   - un événement validé par le secret Connect n'est stocké que s'il porte `account` et un type de compte (`account.updated`, `account.application.deauthorized`, `capability.updated`, `payout.*`) ;
   - au traitement, tout événement d'un compte connecté d'un autre type est ignoré, recharge de solde comprise.

### Points d'entrée API
- `POST /api/v1/admin/topup-codes` : 201, `code` en clair et `codeHint` ; nouveau 400 `topup-code-too-short`.
- `GET /api/v1/admin/topup-codes` et `/{id}` : `code` absent (champ nul omis), `codeHint` présent.
- `POST /api/v1/wallet/topup-codes/redeem` : 429 étendu (IP, global) ; limites applicatives `topup-code-redeem` (10/h par compte) et `topup-code-redeem-ip` (60/h par IP).
- `POST /api/v1/auth/me/wallet-refund-request` : 401 sans jeton, 403 pour un invité.

### Entités JPA impliquées
- `WalletTopupCodeEntity` → `wallet_topup_codes` :
  - `code_hash` : 64 caractères hexadécimaux, unique y compris sur les lignes soft-deleted ;
  - `code_hint` ;
  - `code` : désormais nullable, réservé aux lignes antérieures.
  - CHECK : `code_hash` ou `code` présent.

### Logique métier critique
- **HMAC et non SHA-256 nu** : un code est court et son alphabet connu. Sans clé secrète, une empreinte s'énumère hors ligne.
- **Indice** : 4 caractères pour un code d'au moins 16 caractères, 2 en dessous. Un ancien code court n'est donc jamais révélé, ni réduit à quelques essais.
- **Clé `TOPUP_CODE_HMAC_KEY`** : obligatoire hors dev/test/e2e, et au moins 32 caractères même en dev. La changer rend tous les codes en cours inutilisables.

### Events Spring publiés / écoutés
- Aucun.

### Pièges et points d'attention
- `redeem(userId, code)` délègue à `redeem(userId, code, ip)` par `this` : les deux sont `@Transactional`, sinon le verrou de recherche s'exécute hors transaction (vu en IT Postgres).
- Les profils de la garde pawaPay viennent de l'`Environment` : `spring.profiles.active` est vide quand les tests posent leurs profils par `@ActiveProfiles`.
- Le secret Connect ne vaut que pour la source PAYMENTS. Ajouter un type de compte connecté exige de compléter `StripeConnectEvents.ACCOUNT_TYPES`.
- La compilation incrémentale de Maven peut garder des tests périmés : `./mvnw clean` avant de conclure que les tests compilent.

## Critères d'acceptation couverts
- [x] Aucun code de recharge en clair en base, dans l'audit ni dans la liste admin : `WalletTopupCodeRedemptionPostgresIT`, `WalletTopupCodeAdminServiceTest`, `AdminTopupCodeControllerIT`.
- [x] Codes existants hachés au démarrage et toujours utilisables : `WalletTopupCodeRedemptionPostgresIT`.
- [x] Code manuel d'au moins 10 lettres ou chiffres : `WalletTopupCodeAdminServiceTest`.
- [x] Échecs bornés par compte, IP et plateforme, avec une alerte unique : `WalletTopupCodeRedemptionServiceTest`, `RateLimitInterceptorTest`.
- [x] Un événement de compte connecté ne crédite jamais un solde : `StripeWebhookIngestServiceTest`, `PaymentStripeWebhookHandlerConnectEventsTest`.
- [x] pawaPay activé sans signature : refus de démarrer hors dev/test/e2e : `PawapayConfigGuardTest`.
- [x] Remboursement avant suppression refusé à un invité : `WalletRefundRequestSecurityIT`. Autorités sans `ROLE_ADMIN` refusées sur les remboursements admin : `AdminWalletRefundControllerTest`.

## Tests
- `./mvnw clean test jacoco:report` → 14 229 tests, 0 échec, 0 erreur (7 ignorés, préexistants).
- Couverture JaCoCo des lignes : 96,46 % (nouvelles classes : StripeConnectEvents 100 %, TopupCodeHasher 91,7 %, TopupCodeHashBackfill 94,1 % ; services de codes 98,8 à 100 %).
- Tests ajoutés : `TopupCodeHasherTest`, `WalletRefundRequestSecurityIT` ; complétés : `WalletTopupCodeRedemptionServiceTest`, `WalletTopupCodeAdminServiceTest`, `WalletTopupCodeRedemptionPostgresIT`, `AdminTopupCodeControllerIT`, `StripeWebhookIngestServiceTest`, `PaymentStripeWebhookHandlerConnectEventsTest`, `PawapayConfigGuardTest`, `RateLimitInterceptorTest`, `AdminWalletRefundControllerTest`.

## Décisions techniques
- **Rattrapage au démarrage plutôt qu'en SQL** : la clé n'est pas en base. Le faire avant l'ouverture du serveur évite une période où un ancien code serait introuvable.
- **Champ transitoire `issuedCode`** plutôt qu'un type de retour dédié : l'entité créée reste le contrat du service, et le texte ne peut pas être persisté par erreur.
- **Compteur global dans un cache séparé** : un afflux d'IP ne peut pas l'évincer.
- Codes existants gardés et secret posé par Claude : décisions du propriétaire du 29/10.
