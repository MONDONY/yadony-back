# Accord en espèces suspendu au règlement de la commission — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pour un accord de négociation réglé en espèces, l'expéditeur conclut sans jamais être bloqué par le solde du voyageur, mais l'affaire n'est scellée que lorsque le voyageur confirme en réglant la commission Yadony, en rechargeant son portefeuille s'il est court. S'il renonce ou laisse passer le délai (2 h par défaut, réglable), le thread expire et la demande reste disponible pour un autre voyageur. Le paiement par carte est inchangé : il scelle l'accord immédiatement.

**Architecture:** Un nouveau statut de thread `AWAITING_COMMISSION` sépare « conclu par l'expéditeur » de « scellé ». En espèces, `finalizeInternal` s'arrête à ce statut : la demande reste `OPEN`, les offres concurrentes restent vivantes, aucun colis n'est matérialisé. Toute la finalisation est extraite dans `sealAcceptedThread`, appelée soit par le paiement carte de l'expéditeur, soit par le règlement de la commission du voyageur. Cet règlement passe par `POST /negotiations/{id}/settle-commission`, qui prélève via une méthode prenant le net négocié en paramètre (jamais `computeBidCommission`, qui recalculerait sur le prix/kg de l'annonce), respecte `CommissionSource` (portefeuille d'abord, carte sur choix explicite) et renvoie le contrat `AcceptBidResponse` déjà consommé par l'app. La demande restant ouverte, plusieurs voyageurs peuvent être en attente simultanément : le premier qui règle emporte la demande, d'où une garde de course avant tout débit. Côté Flutter, le fil expose le compte à rebours, l'action « Confirmer et régler » et l'UX « Solde insuffisant » existante.

**Tech Stack:** Spring Boot 3.4 / Java 21 / PostgreSQL 16 / Flyway (backend `dony-back`) — Flutter / flutter_bloc / GoRouter / Dio (frontend `dony_app`).

## Global Constraints

- Deux dépôts git **séparés** : `dony-back` (branche `worktree-offre-lie-trajet-back`) et `dony_app` (branche `worktree-offre-lie-trajet-front`). Une tâche ne touche jamais les deux à la fois. Ne jamais créer de branche, ne jamais commiter sur `main`.
- Ne jamais inclure `Co-Authored-By: Claude` dans un message de commit.
- Toute la copie visible par l'utilisateur est en français, sans tiret cadratin (`—`) dans les textes affichés : utiliser une virgule. Les commentaires de code sont exemptés.
- Le montant de la commission d'une négociation se calcule **toujours** depuis le net négocié (`thread.getCurrentPriceEur()`), jamais via `CashCommissionService.computeBidCommission`, qui se base sur `bid.weightKg × announcement.pricePerKg` et serait faux sur un trajet non dédié.
- Ne jamais réutiliser `POST /bids/{bidId}/accept-with-commission` pour un bid issu de négociation : il revérifie et redécrémente la capacité de l'annonce et republie `BidAcceptedEvent`.
- **Un accord en espèces ne scelle rien tant que Yadony n'a pas encaissé sa commission.** Tant que le thread est `AWAITING_COMMISSION` : la demande reste `OPEN`, les offres concurrentes ne sont ni acceptées ni refusées, aucun colis n'est matérialisé, aucune capacité n'est décomptée. Sans cela, il n'y aurait rien à rouvrir à l'expiration.
- **La demande reste ouverte pendant l'attente** : plusieurs threads peuvent être `AWAITING_COMMISSION` en même temps sur la même demande, et l'expéditeur peut en conclure d'autres. Le premier qui règle l'emporte. Toujours vérifier que la demande est encore `OPEN` **avant** de débiter un voyageur, jamais après.
- **Ajouter une valeur à `NegotiationThreadStatus` exige une migration Flyway** pour la contrainte PostgreSQL `chk_neg_thread_status` (posée en V61, étendue en V179). Sans elle, toute écriture échoue en production en 500, **et les tests ne le voient pas** : le profil `test` désactive Flyway et génère le schéma H2 depuis les entités. La dernière migration existante est V210, la prochaine est donc V211.
- **Tout chemin qui termine un thread portant un trajet dédié doit appeler `softDeleteOrphanedDedicatedTrip`**, sans quoi le trajet reste publié et inutilisable dans « Mes trajets » du voyageur. C'est le bug le plus facile à réintroduire sur cette feature.
- Le délai de règlement est réglable via `dony.negotiation.commission-window-minutes`, valeur par défaut `120`. Jamais de délai en dur dans le code.
- Backend : erreurs RFC 7807 via `GlobalExceptionHandler`, `@PreAuthorize` sur les endpoints, entrée `audit_log` pour toute action métier significative, listeners de paiement en `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`.
- Flutter : BLoC obligatoire (jamais `setState`), GoRouter (jamais `Navigator.push`), tout `DonyButton` d'un bottom sheet va dans `stickyBottom`, jamais dans le `child` scrollable.
- Tests obligatoires dans le même commit que le code. `./mvnw -o test` et `flutter test` doivent être verts avant de marquer une tâche terminée. Ne jamais supprimer un test pour le faire passer : le réécrire s'il assertait l'ancienne règle.
- Ne jamais lancer deux commandes Flutter en parallèle, ni `./mvnw compile` pendant qu'un `./mvnw test` tourne.
- `DonySnackbar` déduplique le même message pendant 5 secondes : dans un test widget qui vérifie l'apparition d'un snackbar après un test voisin produisant le même message, appeler `DonySnackbar.clearDedup()` en début de test.

---

## File Structure

**Backend (`dony-back`)**

| Fichier | Responsabilité |
|---|---|
| `src/main/java/com/yadony/api/payments/cash/CashCommissionService.java` | Ajout de `settleNegotiationCommission(...)` : prélèvement du thread piloté par `CommissionSource`, renvoyant `AcceptBidResponse`. `chargeNegotiationCommission` est supprimée (plus aucun appelant). |
| `src/main/java/com/yadony/api/requests/CashGatePort.java` | Le port perd `chargeNegotiationCashCommission` et gagne `settleNegotiationCommission`. |
| `src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java` | Adaptation à la nouvelle signature du port. |
| `src/main/java/com/yadony/api/requests/service/NegotiationService.java` | `finalizeInternal` ne prélève plus ; nouvelle méthode `settleCommission(callerId, threadId, source)` ; `toResponse` expose `commissionStatus`. |
| `src/main/java/com/yadony/api/requests/controller/NegotiationController.java` | Nouvel endpoint `POST /negotiations/{id}/settle-commission`. |
| `src/main/java/com/yadony/api/matching/ThreadAcceptedBidListener.java` | Le bid CASH naît en `commissionStatus = PENDING`. |
| `src/main/java/com/yadony/api/requests/event/NegotiationCashCommissionPendingEvent.java` | **Créé.** Remplace `NegotiationCashCommissionFailedEvent`, supprimé : la commission est désormais en attente, pas en échec. |
| `src/main/java/com/yadony/api/notifications/RequestEventsListener.java` | Notifie le voyageur qu'une commission est à régler. |
| `src/main/java/com/yadony/api/requests/service/CashCommissionReminderRunner.java` | **Créé.** Relance quotidienne des commissions en attente. |
| `src/main/java/com/yadony/api/payments/wallet/WalletCancellationListener.java` | Garde `!= CHARGED` pour ne pas logger un avertissement sur chaque bid en attente. |
| `src/main/java/com/yadony/api/requests/dto/NegotiationThreadResponse.java` | Champ `commissionStatus` exposé à l'app. |

**Frontend (`dony_app`)**

| Fichier | Responsabilité |
|---|---|
| `lib/features/package_request/data/models/negotiation_thread.dart` | Champ `commissionStatus`. |
| `lib/features/package_request/data/negotiation_repository.dart` | Appel `settleCommission`. |
| `lib/features/package_request/bloc/negotiation_bloc.dart` | Event + états du règlement de commission. |
| `lib/features/package_request/presentation/widgets/thread/thread_state_cta_bar.dart` | Bandeau et CTA « Régler la commission » côté voyageur. |
| `lib/features/package_request/presentation/widgets/commission_settlement_sheet.dart` | **Créé.** Sheet « Solde insuffisant » du parcours négociation. |
| `lib/features/notifications/notification_route_resolver.dart` | Route de la notification de relance. |

---

## Task 1: Le prélèvement de commission d'un thread devient pilotable et non bloquant

**Files:**
- Modify: `src/main/java/com/yadony/api/payments/cash/CashCommissionService.java:432-531`
- Test: `src/test/java/com/yadony/api/payments/cash/CashCommissionServiceNegotiationTest.java` (créer)

**Interfaces:**
- Consumes: `CommissionSource` (`WALLET_FIRST`, `CARD`), `AcceptBidResponse` (`accepted()`, `requires3ds(clientSecret, paymentIntentId)`, `insufficientWallet(availableBalance, requiredCommission, hasCard, currency)`, `failed(error)`), `NegotiationThreadEntity`, `WalletService`, `CommissionRateResolver`, `AuditService`.
- Produces: `AcceptBidResponse settleNegotiationCommission(UUID travelerId, UUID senderId, UUID threadId, BigDecimal net, CommissionSource source)` sur `CashCommissionService`. Idempotente : renvoie `accepted()` si `thread.commissionStatus` vaut déjà `CHARGED`. `chargeNegotiationCommission` est supprimée.

Le comportement change sur un point essentiel : en `WALLET_FIRST`, un solde insuffisant ne déclenche **plus** un débit carte automatique. Il renvoie `INSUFFICIENT_WALLET` pour que le voyageur choisisse entre recharger et payer par carte. Le débit carte n'a lieu qu'en `CommissionSource.CARD`, choix explicite. C'est le même contrat que `acceptCashBid` pour le flux classique.

- [ ] **Step 1: Écrire les tests qui échouent**

Créer `src/test/java/com/yadony/api/payments/cash/CashCommissionServiceNegotiationTest.java`. S'inspirer du style des tests existants de ce package (`@ExtendWith(MockitoExtension.class)`, `@Mock` sur les repositories, `@InjectMocks` sur le service).

```java
package com.yadony.api.payments.cash;

import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CashCommissionServiceNegotiationTest {

    // Le portefeuille couvre la commission : débit immédiat, thread marqué CHARGED/WALLET.
    @Test
    void settleNegotiationCommission_walletCoversIt_debitsWalletAndMarksCharged() {
        // given un thread CASH de 100.00 EUR, taux 5 %, solde 20.00 EUR
        // when settleNegotiationCommission(..., WALLET_FIRST)
        // then status == ACCEPTED, walletService.debit appelé une fois avec 5.00,
        //      thread.commissionStatus == "CHARGED", thread.commissionChargedVia == "WALLET"
    }

    // Solde court en WALLET_FIRST : on ne touche JAMAIS la carte automatiquement.
    @Test
    void settleNegotiationCommission_walletShort_returnsInsufficientWithoutChargingCard() {
        // given solde 1.00 EUR pour une commission de 5.00 EUR, voyageur avec carte enregistrée
        // when settleNegotiationCommission(..., WALLET_FIRST)
        // then status == INSUFFICIENT_WALLET, requiredCommission == 5.00,
        //      availableBalance == 1.00, hasCard == true,
        //      verifyNoInteractions(stripeCashGateway),
        //      thread.commissionStatus reste null (ni CHARGED ni FAILED)
    }

    // Choix explicite de la carte par le voyageur après un solde insuffisant.
    @Test
    void settleNegotiationCommission_cardSource_chargesCardAndMarksCharged() {
        // given CommissionSource.CARD, voyageur avec commissionPaymentMethodId,
        //       PaymentIntent Stripe qui revient "succeeded"
        // then status == ACCEPTED, thread.commissionChargedVia == "CARD",
        //      thread.commissionPaymentIntentId renseigné
    }

    // Carte demandée mais aucune carte enregistrée : échec explicite, pas de NPE.
    @Test
    void settleNegotiationCommission_cardSourceWithoutCard_returnsFailed() {
        // then status == FAILED, error non nul, aucun appel Stripe
    }

    // Idempotence : rejouer un règlement déjà effectué ne redébite pas.
    @Test
    void settleNegotiationCommission_alreadyCharged_isIdempotent() {
        // given thread.commissionStatus == "CHARGED"
        // then status == ACCEPTED, verifyNoInteractions(walletService)
    }

    // Commission nulle ou négative (prix négocié à 0) : rien à prélever, succès.
    @Test
    void settleNegotiationCommission_zeroCommission_returnsAcceptedWithoutDebit() {
        // then status == ACCEPTED, verify(walletService, never()).debit(...)
    }
}
```

Remplacer chaque commentaire `// given/when/then` par le code réel, en suivant les stubs utilisés par les tests voisins du package pour `walletService.getBalance`, `commissionRateResolver.resolve`, `negotiationThreadRepository.findById` et `stripeCashGateway.createPaymentIntent`.

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest=CashCommissionServiceNegotiationTest`
Expected: FAIL, `settleNegotiationCommission` n'existe pas encore (erreur de compilation).

- [ ] **Step 3: Implémenter `settleNegotiationCommission`**

Dans `CashCommissionService`, remplacer la méthode `chargeNegotiationCommission` (ligne 432) par :

```java
    /**
     * Règle la commission Yadony (net × taux) d'un thread de négociation CASH, à la
     * demande du voyageur. Le montant se calcule depuis le net négocié passé en
     * paramètre, jamais depuis le prix/kg de l'annonce liée, qui n'a aucun rapport
     * avec le prix convenu dès que le trajet n'est pas dédié.
     *
     * <p>{@code WALLET_FIRST} ne bascule PAS sur la carte tout seul : un solde court
     * renvoie {@code INSUFFICIENT_WALLET} et laisse le voyageur choisir entre
     * recharger et payer par carte. Le débit carte n'a lieu que sur
     * {@code CommissionSource.CARD}, choix explicite.
     *
     * <p>Ne lève jamais sur un refus normal (solde court, carte refusée, 3DS
     * requis) : renvoie le statut correspondant.
     */
    @Transactional
    public AcceptBidResponse settleNegotiationCommission(
            UUID travelerId, UUID senderId, UUID threadId, BigDecimal net, CommissionSource source) {
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                negotiationThreadRepository.findById(threadId).orElseThrow();

        if (NEGO_COMMISSION_CHARGED.equals(thread.getCommissionStatus())) {
            return AcceptBidResponse.accepted();
        }

        BigDecimal rate = commissionRateResolver.resolve(travelerId, senderId);
        BigDecimal commission = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (commission.signum() <= 0) {
            markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_WALLET, travelerId, commission);
            return AcceptBidResponse.accepted();
        }

        if (source == CommissionSource.WALLET_FIRST) {
            BigDecimal balance = walletService.getBalance(travelerId, thread.getCurrency());
            if (balance.compareTo(commission) >= 0) {
                try {
                    walletService.debit(travelerId, thread.getCurrency(), commission,
                            WalletTransactionType.COMMISSION_DEDUCTED,
                            threadId.toString(), "nego_commission_wallet_" + threadId);
                    markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_WALLET, travelerId, commission);
                    return AcceptBidResponse.accepted();
                } catch (InsufficientWalletBalanceException e) {
                    // Race TOCTOU : le solde a chuté entre getBalance et debit. On ne
                    // bascule pas sur la carte sans consentement — le voyageur relancera.
                    balance = e.getAvailableBalance();
                }
            }
            UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
            return AcceptBidResponse.insufficientWallet(
                    balance, commission, traveler.getCommissionPaymentMethodId() != null, thread.getCurrency());
        }

        // CommissionSource.CARD : le voyageur a explicitement choisi sa carte.
        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            return AcceptBidResponse.failed("no-commission-card");
        }
        CurrencyAmount commissionAmount = CurrencyAmount.of(
                commission, SupportedCurrency.fromCodeOrDefault(thread.getCurrency()));
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(commissionAmount.minor())
                    .setCurrency(commissionAmount.currency().code())
                    .setCustomer(traveler.getStripeCustomerId())
                    .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                    .setOffSession(true)
                    .setConfirm(true)
                    .setDescription("Commission cash négociation " + threadId)
                    .putMetadata("negotiation_thread_id", threadId.toString())
                    .putMetadata("commission_purpose", "cash_negotiation")
                    .build();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("nego_commission_" + threadId)
                    .build();
            PaymentIntent pi = stripeCashGateway.createPaymentIntent(params, opts);
            if ("succeeded".equals(pi.getStatus())) {
                thread.setCommissionPaymentIntentId(pi.getId());
                markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_CARD, travelerId, commission);
                return AcceptBidResponse.accepted();
            }
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "card-status-" + pi.getStatus()));
            return AcceptBidResponse.failed("card-status-" + pi.getStatus());
            // NOTE (corrigée après revue de la Task 1) : le statut "requires_action"
            // doit être intercepté AVANT ce cas d'échec et renvoyer
            // AcceptBidResponse.requires3ds(pi.getClientSecret(), pi.getId()), comme
            // le fait chargeCommission pour le flux classique. Dans ce flux, c'est le
            // voyageur lui-même qui déclenche le règlement depuis son téléphone : il
            // est présent et peut authentifier, ce qui était impossible quand
            // l'expéditeur déclenchait tout.
        } catch (CardException e) {
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "card-declined", "code", e.getCode() != null ? e.getCode() : ""));
            return AcceptBidResponse.failed("card-declined");
        } catch (StripeException e) {
            log.error("Commission carte négociation thread {} : erreur Stripe {}", threadId, e.getMessage());
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "stripe-error"));
            return AcceptBidResponse.failed("stripe-error");
        }
    }

    private void markNegotiationCommissionCharged(
            com.yadony.api.requests.entity.NegotiationThreadEntity thread,
            String via, UUID travelerId, BigDecimal commission) {
        thread.setCommissionStatus(NEGO_COMMISSION_CHARGED);
        thread.setCommissionChargedVia(via);
        negotiationThreadRepository.save(thread);
        auditService.log("NEGOTIATION_THREAD", thread.getId(), "CASH_COMMISSION_CHARGED", travelerId,
                Map.of("commission", commission.toPlainString(), "via", via));
    }
```

Vérifier que `NEGO_COMMISSION_FAILED` n'est plus référencé ; si c'est le cas, supprimer la constante pour ne pas laisser de code mort.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest=CashCommissionServiceNegotiationTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/payments/cash/CashCommissionService.java src/test/java/com/yadony/api/payments/cash/CashCommissionServiceNegotiationTest.java
git commit -m "feat(commission): reglement de commission de negociation pilote par le voyageur"
```

---

## Task 2: Le port et son adaptateur exposent le règlement

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/CashGatePort.java`
- Modify: `src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java`
- Test: `src/test/java/com/yadony/api/payments/cash/CashGateAdapterTest.java` (créer si absent)

**Interfaces:**
- Consumes: `CashCommissionService.settleNegotiationCommission(...)` (Task 1).
- Produces: `AcceptBidResponse settleNegotiationCommission(UUID travelerId, UUID senderId, UUID threadId, BigDecimal netAmount, CommissionSource source)` sur `CashGatePort`. La méthode `chargeNegotiationCashCommission` disparaît du port.

- [ ] **Step 1: Écrire le test qui échoue**

```java
// src/test/java/com/yadony/api/payments/cash/CashGateAdapterTest.java
@Test
void settleNegotiationCommission_delegatesToCommissionService() {
    UUID traveler = UUID.randomUUID();
    UUID sender = UUID.randomUUID();
    UUID thread = UUID.randomUUID();
    when(cashCommissionService.settleNegotiationCommission(
            traveler, sender, thread, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST))
        .thenReturn(AcceptBidResponse.accepted());

    AcceptBidResponse resp = adapter.settleNegotiationCommission(
            traveler, sender, thread, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);

    assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw -o test -Dtest=CashGateAdapterTest`
Expected: FAIL, méthode inexistante.

- [ ] **Step 3: Implémenter**

Dans `CashGatePort`, supprimer `chargeNegotiationCashCommission` et ajouter :

```java
    /**
     * Règle la commission Yadony d'un thread de négociation CASH à la demande du
     * voyageur. Le montant dérive du net négocié passé en paramètre. Ne lève jamais
     * sur un refus normal : le statut de la réponse porte l'issue.
     */
    AcceptBidResponse settleNegotiationCommission(
            java.util.UUID travelerId, java.util.UUID senderId, java.util.UUID threadId,
            java.math.BigDecimal netAmount, com.yadony.api.payments.cash.CommissionSource source);
```

Dans `CashGateAdapter`, remplacer l'implémentation de `chargeNegotiationCashCommission` par la délégation correspondante.

- [ ] **Step 4: Lancer le test**

Run: `./mvnw -o test -Dtest=CashGateAdapterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yadony/api/requests/CashGatePort.java src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java src/test/java/com/yadony/api/payments/cash/CashGateAdapterTest.java
git commit -m "feat(commission): le port de paiement expose le reglement de commission"
```

---

## Task 3: Un accord en espèces attend sa commission avant d'être scellé

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/entity/NegotiationThreadStatus.java`
- Create: `src/main/resources/db/migration/V211__add_awaiting_commission_to_thread_status_check.sql`
- Test: `src/test/java/com/yadony/api/migrations/V211MigrationTest.java` (créer)

**Interfaces:**
- Produces: la valeur d'enum `NegotiationThreadStatus.AWAITING_COMMISSION`, et son autorisation dans la contrainte PostgreSQL `chk_neg_thread_status`.

**PIÈGE CRITIQUE, la raison d'être de cette tâche.** La table `negotiation_threads` porte une contrainte `CHECK (status IN (...))` posée en V61 et étendue en V179. Ajouter une valeur d'enum Java sans étendre cette contrainte fait échouer toute écriture en PostgreSQL, **et les tests ne le voient pas** : le profil `test` désactive Flyway et génère le schéma H2 depuis les entités JPA, sans contrainte. Le bug n'apparaîtrait qu'en production, en 500. C'est pourquoi cette tâche est isolée et testée sur un vrai PostgreSQL.

- [ ] **Step 1: Écrire le test de migration qui échoue**

Créer `src/test/java/com/yadony/api/migrations/V211MigrationTest.java` en s'inspirant très exactement de `V210MigrationTest.java` (même dépôt, même package) : EmbeddedPostgres + Flyway réel, `resetAndMigrateTo("210")` puis migration vers `"211"`.

```java
    // La contrainte CHECK doit accepter le nouveau statut, sinon toute écriture
    // échoue en production alors que les tests H2 (sans contrainte) restent verts.
    @Test
    void afterV211_awaitingCommissionStatusIsAccepted() {
        // given un thread inséré avec status = 'AWAITING_COMMISSION'
        // then l'insertion réussit
    }

    @Test
    void afterV211_previouslyAllowedStatusesStillAccepted() {
        // given un thread pour chacun des 8 statuts historiques
        // then toutes les insertions réussissent
    }

    @Test
    void afterV211_unknownStatusIsStillRejected() {
        // given un thread avec status = 'NOT_A_STATUS'
        // then l'insertion échoue (la contrainte protège toujours)
    }
```

Remplacer chaque commentaire par du code réel, sur le modèle de `V210MigrationTest`.

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw -o test -Dtest=V211MigrationTest`
Expected: FAIL, la migration V211 n'existe pas.

- [ ] **Step 3: Implémenter**

Créer `src/main/resources/db/migration/V211__add_awaiting_commission_to_thread_status_check.sql` :

```sql
-- AWAITING_COMMISSION : un accord en espèces est conclu par l'expéditeur mais reste
-- suspendu tant que le voyageur n'a pas réglé la commission Yadony. Sans cette
-- extension de la contrainte, toute transition vers ce statut échoue en PostgreSQL
-- alors que les tests H2 (profil test, Flyway désactivé) restent verts.
ALTER TABLE negotiation_threads DROP CONSTRAINT chk_neg_thread_status;
ALTER TABLE negotiation_threads ADD CONSTRAINT chk_neg_thread_status CHECK (
  status IN ('OPEN','AWAITING_TRIP','AWAITING_PAYMENT','AWAITING_COMMISSION','ACCEPTED','REJECTED','AUTO_REJECTED','EXPIRED','CANCELLED')
);
```

Dans `NegotiationThreadStatus`, ajouter la valeur et l'inclure dans `isActive()` :

```java
    /**
     * Accord en espèces conclu par l'expéditeur, en attente du règlement de la
     * commission Yadony par le voyageur. Rien n'est scellé à ce stade : la demande
     * reste ouverte, les offres concurrentes restent vivantes, aucun colis n'est
     * créé. Le premier voyageur qui règle emporte la demande ; passé le délai, le
     * thread expire.
     */
    AWAITING_COMMISSION,
```

```java
    public boolean isActive() {
        return this == OPEN || this == AWAITING_TRIP || this == AWAITING_PAYMENT
            || this == AWAITING_COMMISSION;
    }
```

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest=V211MigrationTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): statut AWAITING_COMMISSION et sa contrainte PostgreSQL"
```

---

## Task 4: Conclure en espèces suspend l'accord au lieu de le sceller

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java` (méthode `finalizeInternal`)
- Modify: `src/main/java/com/yadony/api/requests/CashGatePort.java`
- Modify: `src/main/java/com/yadony/api/payments/cash/CashGateAdapter.java`
- Modify: `src/main/java/com/yadony/api/payments/cash/CashCommissionService.java`
- Create: `src/main/java/com/yadony/api/requests/event/NegotiationCommissionPendingEvent.java`
- Modify: `src/main/java/com/yadony/api/notifications/RequestEventsListener.java`
- Modify: `src/main/java/com/yadony/api/requests/repository/NegotiationThreadRepository.java`
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- Test: `src/test/java/com/yadony/api/notifications/RequestEventsListenerTest.java`

**Trou à combler, découvert à la Task 3 :** deux requêtes du repository listent les statuts « actifs » en dur dans leur JPQL, sans passer par `NegotiationThreadStatus.isActive()` : `existsActiveByTravelerAnnouncementId` (lignes ~34) et `findActiveByPackageRequestIdAndTravelerId` (lignes ~67). Sans `AWAITING_COMMISSION` dans ces listes, un voyageur peut dépublier le trajet lié à un accord qui attend son règlement, et la détection de doublon d'offre laisse passer une seconde offre sur la même demande. Ajouter le statut aux deux listes, et un test de non-régression pour chacune. Ne pas toucher à la requête d'expiration `AWAITING_PAYMENT` (ligne ~90), qui vise un autre mécanisme.

**Interfaces:**
- Consumes: `NegotiationThreadStatus.AWAITING_COMMISSION` (Task 3).
- Produces: `NegotiationCommissionPendingEvent(UUID threadId, UUID packageRequestId, UUID travelerId, UUID senderId, BigDecimal commissionAmount, String currency, LocalDateTime expiresAt)` ; l'extraction de la finalisation en méthode privée réutilisable `sealAcceptedThread(thread, request, callerId, paymentIntentId)`, que la Task 5 appellera.

C'est le cœur du changement. Aujourd'hui, `finalizeInternal` scelle tout d'un bloc : thread `ACCEPTED`, demande `ACCEPTED`, threads concurrents `AUTO_REJECTED`, publication de `PackageRequestAcceptedEvent` qui matérialise le colis avec son QR et décompte la capacité du trajet. Pour un accord en espèces, plus rien de tout cela ne doit se produire tant que la commission n'est pas encaissée : sinon il n'y aurait rien à rouvrir quand le délai expire, et l'expéditeur se retrouverait avec un colis créé pour un voyageur qui n'a jamais confirmé.

**Le paiement par carte est strictement inchangé** : le paiement de l'expéditeur scelle l'accord immédiatement, comme aujourd'hui.

- [ ] **Step 1: Écrire les tests qui échouent**

Dans `NegotiationServiceTest`, classe interne `FinalizeAfterPaymentTests` :

```java
        // En espèces, conclure ne scelle plus rien : c'est le règlement de la
        // commission par le voyageur qui scellera, ou le délai qui libérera.
        @Test
        void finalize_cashThread_movesToAwaitingCommission_withoutSealing() {
            // then thread.status == AWAITING_COMMISSION
            //      && request.status reste OPEN (pas ACCEPTED)
            //      && aucun PackageRequestAcceptedEvent publié
            //      && les threads concurrents ne sont PAS passés AUTO_REJECTED
            //      && verifyNoInteractions(cashGatePort)
        }

        @Test
        void finalize_cashThread_publishesCommissionPendingEventWithDeadline() {
            // then un NegotiationCommissionPendingEvent est publié, portant
            //      commissionAmount == prix négocié × taux, currency, et un
            //      expiresAt situé dans le futur
        }

        // Non-régression : la carte scelle toujours immédiatement.
        @Test
        void finalize_stripeThread_stillSealsImmediately() {
            // then thread.status == ACCEPTED && request.status == ACCEPTED
            //      && PackageRequestAcceptedEvent publié
            //      && les threads concurrents passent AUTO_REJECTED
        }
```

Dans `RequestEventsListenerTest` :

```java
    @Test
    void onNegotiationCommissionPending_notifiesTravelerWithDeadline() {
        // then dispatcher.notifyUser appelé avec travelerId, un message mentionnant
        //      la commission, data["type"] == "negotiation_commission_pending"
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,RequestEventsListenerTest'`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

Dans `finalizeInternal`, supprimer entièrement le bloc de prélèvement de commission (`if (thread.getPaymentMethod() == PaymentMethod.CASH) { ... chargeNegotiationCashCommission ... }`) et sa 422 `negotiation/commission-charge-failed`.

Extraire tout ce qui suit le paiement (passage du thread en `ACCEPTED`, ouverture du surplus du trajet dédié, passage de la demande en `ACCEPTED`, boucle `AUTO_REJECTED` sur les threads concurrents, publication de `PackageRequestAcceptedEvent`, entrées `audit_log`) dans une méthode privée :

```java
    /**
     * Scelle définitivement un accord : c'est ici que la demande se ferme, que les
     * offres concurrentes tombent et que le colis est matérialisé. Appelée par le
     * paiement carte de l'expéditeur, et par le règlement de la commission du
     * voyageur pour les accords en espèces.
     */
    private void sealAcceptedThread(NegotiationThreadEntity thread, PackageRequestEntity request,
                                    UUID callerId, String paymentIntentId) {
        // corps repris tel quel de finalizeInternal, sans en modifier la logique
    }
```

Puis, dans `finalizeInternal`, brancher selon le mode :

```java
        if (thread.getPaymentMethod() == PaymentMethod.CASH) {
            // Un accord en espèces ne scelle rien : Yadony n'a pas encore encaissé sa
            // commission, et le voyageur peut encore renoncer. La demande reste donc
            // ouverte et les offres concurrentes vivantes, jusqu'au règlement ou à
            // l'expiration du délai.
            thread.setStatus(NegotiationThreadStatus.AWAITING_COMMISSION);
            thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
            threadRepo.save(thread);
            BigDecimal commission = PriceBreakdown
                .fromNet(thread.getCurrentPriceEur(), commissionProperties.rate()).commission();
            LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(negotiationProperties.commissionWindowMinutes());
            eventPublisher.publishEvent(new NegotiationCommissionPendingEvent(
                thread.getId(), request.getId(), thread.getTravelerId(),
                request.getSenderId(), commission, thread.getCurrency(), expiresAt));
            auditService.log("NEGOTIATION_THREAD", thread.getId(), "AWAITING_COMMISSION", callerId,
                Map.of("commission", commission.toPlainString()));
        } else {
            sealAcceptedThread(thread, request, callerId, paymentIntentId);
        }
```

Ajouter la propriété de configuration du délai, à l'image de celles qui existent déjà dans le projet (`dony.requests.awaiting-trip-hours` et consorts) : `dony.negotiation.commission-window-minutes`, valeur par défaut `120`, lue via un `@ConfigurationProperties` du package `requests`. Déclarer la valeur dans `application.yml` sous la forme `${DONY_NEGOTIATION_COMMISSION_WINDOW_MINUTES:120}`.

Créer l'event :

```java
package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * L'expéditeur a conclu en espèces : le voyageur doit régler la commission Yadony
 * avant {@code expiresAt} pour emporter la demande. Rien n'est scellé, la demande
 * reste ouverte et un autre voyageur peut la conclure entre-temps.
 */
public record NegotiationCommissionPendingEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID travelerId,
    UUID senderId,
    BigDecimal commissionAmount,
    String currency,
    LocalDateTime expiresAt
) {}
```

Dans `RequestEventsListener`, ajouter le listener correspondant, en `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` et `@Async` :

```java
    /**
     * Accord en espèces conclu par l'expéditeur : le voyageur doit régler la
     * commission pour l'emporter. AFTER_COMMIT, car annoncer un accord avant son
     * commit exposerait à notifier une transaction qui rollback ensuite.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCommissionPending(NegotiationCommissionPendingEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Confirmez votre prise en charge",
            String.format(
                "L'expéditeur a retenu votre offre. Réglez la commission de %.2f %s pour confirmer, sans quoi la demande repartira.",
                e.commissionAmount(), e.currency()),
            Map.of(
                "type", "negotiation_commission_pending",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }
```

Enfin, l'ancien chemin de prélèvement n'a plus aucun appelant : supprimer `chargeNegotiationCashCommission` de `CashGatePort` et de `CashGateAdapter`, `chargeNegotiationCommission` de `CashCommissionService`, et les tests qui ne testaient qu'elles. Dans `NegotiationServiceTest`, supprimer les stubs Mockito devenus `UnnecessaryStubbing` — les stubs, jamais les tests.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test`
Expected: PASS, suite complète verte.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): un accord en especes attend sa commission avant d'etre scelle"
```

---

## Task 5: Le règlement de la commission scelle l'accord

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java`
- Modify: `src/main/java/com/yadony/api/requests/controller/NegotiationController.java`
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- Test: `src/test/java/com/yadony/api/requests/controller/NegotiationControllerIT.java`

**Interfaces:**
- Consumes: `CashGatePort.settleNegotiationCommission(...)` (Task 2), `sealAcceptedThread(...)` (Task 4).
- Produces: `AcceptBidResponse settleCommission(UUID callerId, UUID threadId, CommissionSource source)` et `ConfirmAcceptanceResponse confirmCommission(UUID callerId, UUID threadId)` sur `NegotiationService` ; les endpoints `POST /api/v1/negotiations/{id}/settle-commission?commissionSource=WALLET_FIRST|CARD` et `POST /api/v1/negotiations/{id}/confirm-commission`, tous deux `@PreAuthorize("hasRole('TRAVELER')")`.

**La course, à traiter avec soin.** La demande restant ouverte, plusieurs threads peuvent être en `AWAITING_COMMISSION` en même temps sur la même demande, et l'expéditeur peut même en conclure un autre entre-temps. Le premier qui règle emporte la demande. Il faut donc vérifier que la demande est encore disponible **avant** de débiter le voyageur : débiter puis découvrir que la demande est prise obligerait à rembourser, et laisserait un voyageur payer pour rien.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    @Test
    void settleCommission_notTraveler_throws403() {
        // then 403 "negotiation/not-traveler"
    }

    @Test
    void settleCommission_threadNotAwaitingCommission_throws409() {
        // given un thread OPEN
        // then 409 "thread/not-awaiting-commission"
    }

    // La garde de course : on ne débite jamais un voyageur pour une demande déjà prise.
    @Test
    void settleCommission_requestAlreadyAccepted_throws409WithoutCharging() {
        // given la demande est passée ACCEPTED via un thread concurrent
        // then 409 "request/already-accepted" && verifyNoInteractions(cashGatePort)
        // Ajouter aussi le cas nominal : une demande en NEGOTIATING (l'état réel
        // d'une demande ayant reçu des offres) doit laisser passer le règlement.
    }

    @Test
    void settleCommission_success_sealsTheDeal() {
        // given cashGatePort renvoie accepted()
        // then thread.status == ACCEPTED && request.status == ACCEPTED
        //      && PackageRequestAcceptedEvent publié
        //      && les threads concurrents passent AUTO_REJECTED
    }

    @Test
    void settleCommission_insufficientWallet_leavesThreadAwaitingCommission() {
        // given cashGatePort renvoie insufficientWallet(1.00, 5.00, true, "EUR")
        // then la réponse porte INSUFFICIENT_WALLET, le thread reste
        //      AWAITING_COMMISSION et la demande reste OPEN
    }

    @Test
    void confirmCommission_after3ds_sealsTheDeal() {
        // given le thread porte un commissionPaymentIntentId et Stripe le dit "succeeded"
        // then l'accord est scellé comme ci-dessus
    }
```

Dans `NegotiationControllerIT` :

```java
    @Test
    void post_settleCommission_insufficientWallet_returns409WithAmounts() throws Exception {
        // then status 409, jsonPath("$.status") == "INSUFFICIENT_WALLET",
        //      jsonPath("$.requiredCommission") == 5.00, jsonPath("$.hasCard") == true
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,NegotiationControllerIT'`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

```java
    /**
     * Le voyageur règle la commission Yadony et emporte la demande. C'est ce
     * règlement qui scelle l'accord : tant qu'il n'a pas eu lieu, la demande reste
     * ouverte et un autre voyageur peut la conclure.
     */
    @Transactional
    public AcceptBidResponse settleCommission(UUID callerId, UUID threadId, CommissionSource source) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_COMMISSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-commission");
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Garde de course : ne jamais débiter pour une demande déjà emportée par un
        // autre voyageur. Le débit d'abord obligerait à rembourser ensuite.
        // PIÈGE : tester `== OPEN` ne marcherait JAMAIS en production. Dès la
        // première offre reçue, la demande passe en NEGOTIATING (cf.
        // NegotiationService ligne ~246) et n'est donc plus OPEN au moment où un
        // voyageur règle. C'est bien « pas encore emportée » qu'il faut tester.
        if (request.getStatus() == PackageRequestStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }
        if (request.getStatus() != PackageRequestStatus.OPEN
                && request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-available");
        }

        AcceptBidResponse resp = cashGatePort.settleNegotiationCommission(
            thread.getTravelerId(), request.getSenderId(), threadId, thread.getCurrentPriceEur(), source);

        if (resp.status() == AcceptanceStatusDto.ACCEPTED) {
            sealAcceptedThread(thread, request, callerId, null);
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_SETTLED", callerId,
                Map.of("via", String.valueOf(thread.getCommissionChargedVia())));
        }
        return resp;
    }

    /**
     * Confirme un règlement passé par une authentification 3D Secure : relit le
     * PaymentIntent auprès de Stripe et scelle l'accord s'il a abouti. Rappeler
     * {@code settleCommission} ne conviendrait pas, la clé d'idempotence Stripe
     * rejouerait la réponse « authentification requise ».
     */
    @Transactional
    public ConfirmAcceptanceResponse confirmCommission(UUID callerId, UUID threadId) {
        // mêmes gardes d'appartenance et de statut que ci-dessus, puis délégation à
        // cashGatePort pour relire le PaymentIntent, puis sealAcceptedThread en cas
        // de succès
    }
```

Ajouter au port et à l'adaptateur la méthode de confirmation nécessaire, en s'inspirant de `CashCommissionService.confirmCommissionAcceptance` (flux classique).

Dans `NegotiationController`, les deux endpoints, avec le mapping de statuts déjà utilisé par `CashCommissionController` : `ACCEPTED` → 200, `REQUIRES_3DS` → 202, `INSUFFICIENT_WALLET` → 409, `FAILED` → 422.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,NegotiationControllerIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): le reglement de la commission scelle l'accord"
```

---

## Task 6: Le voyageur peut renoncer, et le délai le fait à sa place

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java`
- Modify: `src/main/java/com/yadony/api/requests/controller/NegotiationController.java`
- Create: `src/main/java/com/yadony/api/requests/service/CommissionWindowExpiryRunner.java`
- Modify: `src/main/java/com/yadony/api/requests/repository/NegotiationThreadRepository.java`
- Modify: `src/main/java/com/yadony/api/notifications/RequestEventsListener.java`
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- Test: `src/test/java/com/yadony/api/requests/service/CommissionWindowExpiryRunnerTest.java` (créer)

**Interfaces:**
- Consumes: `NegotiationThreadStatus.AWAITING_COMMISSION` (Task 3), `softDeleteOrphanedDedicatedTrip(...)` (existant dans `NegotiationService`), `dony.negotiation.commission-window-minutes` (Task 4).
- Produces: `void declineCommission(UUID callerId, UUID threadId)` et l'endpoint `POST /api/v1/negotiations/{id}/decline-commission` (`@PreAuthorize("hasRole('TRAVELER')")`) ; le runner d'expiration ; `NegotiationThreadRepository.findExpiredAwaitingCommission(LocalDateTime cutoff)`.

**Piège connu de cette feature, à ne pas rouvrir.** Un thread portant un trajet dédié qui se termine sans être scellé laisse ce trajet orphelin, publié et jamais utilisable, dans « Mes trajets » du voyageur. `softDeleteOrphanedDedicatedTrip` doit être appelée depuis les deux sorties ajoutées ici, le renoncement et l'expiration, exactement comme elle l'est déjà depuis `cancelNegotiation`, `reject`, la boucle `AUTO_REJECTED` et `NegotiationExpiryRunner`.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    @Test
    void declineCommission_notTraveler_throws403() {
        // then 403 "negotiation/not-traveler"
    }

    @Test
    void declineCommission_releasesRequestAndSoftDeletesDedicatedTrip() {
        // given un thread AWAITING_COMMISSION portant un trajet dédié
        // then thread.status == CANCELLED, la demande reste OPEN,
        //      le trajet dédié est soft-deleted, l'expéditeur est notifié
    }

    @Test
    void declineCommission_wrongStatus_throws409() {
        // given un thread ACCEPTED
        // then 409 "thread/not-awaiting-commission"
    }
```

```java
    // CommissionWindowExpiryRunnerTest
    @Test
    void expire_pastDeadline_cancelsThreadAndSoftDeletesDedicatedTrip() {
        // given un thread AWAITING_COMMISSION dont lastActivityAt dépasse la fenêtre
        // then thread.status == EXPIRED, demande toujours OPEN, trajet dédié soft-deleted
    }

    @Test
    void expire_withinWindow_leavesThreadUntouched() {
        // then aucun changement de statut
    }

    @Test
    void expire_isIdempotent_onAlreadyExpiredThread() {
        // then aucun second traitement, aucune seconde notification
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,CommissionWindowExpiryRunnerTest'`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

`declineCommission` : vérifier l'appartenance et le statut `AWAITING_COMMISSION`, passer le thread en `CANCELLED`, appeler `softDeleteOrphanedDedicatedTrip`, écrire l'`audit_log`, publier un event notifiant l'expéditeur que le voyageur a renoncé et que sa demande reste disponible.

Le repository :

```java
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status = com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_COMMISSION
          AND t.lastActivityAt < :cutoff
    """)
    List<NegotiationThreadEntity> findExpiredAwaitingCommission(@Param("cutoff") LocalDateTime cutoff);
```

Le runner, sur le modèle de `NegotiationExpiryRunner` (même package, mêmes annotations, même style) : `@Scheduled` à intervalle court (toutes les 5 minutes, la fenêtre étant de 2 h par défaut), calcul du `cutoff` depuis `commissionWindowMinutes`, passage des threads en `EXPIRED`, `softDeleteOrphanedDedicatedTrip`, `audit_log`, et notification aux deux parties : au voyageur que le délai est passé, à l'expéditeur que sa demande est de nouveau disponible. Le runner doit être idempotent : il ne traite que les threads encore `AWAITING_COMMISSION`.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test`
Expected: PASS, suite complète.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): renoncement du voyageur et expiration du delai de commission"
```

---

## Task 7: L'app sait où en est l'accord

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/dto/NegotiationThreadResponse.java`
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java` (méthode `toResponse`)
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`

**Interfaces:**
- Produces: deux champs ajoutés en fin du record `NegotiationThreadResponse` : `String commissionStatus` (`"PENDING"`, `"REQUIRES_3DS"`, `"CHARGED"` ou `null`) et `LocalDateTime commissionDeadline` (échéance du règlement, `null` hors `AWAITING_COMMISSION`). Un constructeur de compatibilité sans ces deux champs est ajouté, à l'image de ceux qui existent déjà.

Le statut `AWAITING_COMMISSION` transite déjà par le champ `status` existant : c'est lui que l'app lira pour savoir qu'une action est attendue. Les deux nouveaux champs servent à afficher le montant restant dû et le compte à rebours.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void toResponse_awaitingCommissionThread_exposesStatusAndDeadline() {
        // given un thread AWAITING_COMMISSION, cash, lastActivityAt connu
        // then la réponse porte status == AWAITING_COMMISSION,
        //      commissionStatus == "PENDING" et un commissionDeadline non nul
    }

    @Test
    void toResponse_stripeThread_hasNoCommissionDeadline() {
        // then commissionDeadline == null
    }
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw -o test -Dtest=NegotiationServiceTest`
Expected: FAIL, méthodes inexistantes.

- [ ] **Step 3: Implémenter**

Ajouter les deux champs en fin de record, documentés, plus un constructeur de compatibilité qui délègue avec `null, null`. Renseigner dans `toResponse` : `commissionStatus` depuis `t.getCommissionStatus()`, et `commissionDeadline` calculé depuis `t.getLastActivityAt()` plus `commissionWindowMinutes` uniquement quand le statut est `AWAITING_COMMISSION`.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test`
Expected: PASS, suite complète.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): la reponse de fil expose l'etat et l'echeance de la commission"
```

---

## Task 8: Le modèle Flutter porte l'état de la commission

**Files:** (dépôt `dony_app`)
- Modify: `lib/features/package_request/data/models/negotiation_thread.dart`
- Test: `test/features/package_request/data/negotiation_thread_test.dart`

**Interfaces:**
- Consumes: le champ JSON `commissionStatus` (Task 6).
- Produces: `NegotiationThread.commissionStatus` (`String?`) et le getter `bool get needsCommissionSettlement => commissionStatus == 'PENDING'`.

- [ ] **Step 1: Écrire les tests qui échouent**

```dart
    test('fromJson lit commissionStatus et en déduit le besoin de règlement', () {
      final thread = NegotiationThread.fromJson({
        ...baseJson,
        'commissionStatus': 'PENDING',
      });
      expect(thread.commissionStatus, 'PENDING');
      expect(thread.needsCommissionSettlement, isTrue);
    });

    test('commissionStatus absent → aucun règlement attendu', () {
      final thread = NegotiationThread.fromJson(baseJson);
      expect(thread.commissionStatus, isNull);
      expect(thread.needsCommissionSettlement, isFalse);
    });

    test('commissionStatus CHARGED → aucun règlement attendu', () {
      final thread = NegotiationThread.fromJson({
        ...baseJson,
        'commissionStatus': 'CHARGED',
      });
      expect(thread.needsCommissionSettlement, isFalse);
    });
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `flutter test test/features/package_request/data/negotiation_thread_test.dart`
Expected: FAIL, champ inexistant.

- [ ] **Step 3: Implémenter**

Ajouter le champ au constructeur, à `fromJson` (`commissionStatus: json['commissionStatus'] as String?`), à `props` et au `copyWith` s'il existe :

```dart
  /// État du règlement de la commission Yadony pour un accord en espèces.
  /// `PENDING` tant que le voyageur ne l'a pas réglée, `CHARGED` ensuite, null
  /// pour les accords par carte dont la commission passe par Stripe.
  final String? commissionStatus;

  /// Vrai quand le voyageur doit encore régler la commission de cet accord.
  bool get needsCommissionSettlement => commissionStatus == 'PENDING';
```

- [ ] **Step 4: Lancer les tests**

Run: `flutter test test/features/package_request/data/negotiation_thread_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): le fil porte l'etat de la commission"
```

---

## Task 9: Le règlement de commission passe par le BLoC

**Files:** (dépôt `dony_app`)
- Modify: `lib/features/package_request/data/negotiation_repository.dart`
- Modify: `lib/features/package_request/bloc/negotiation_bloc.dart`
- Test: `test/features/package_request/bloc/negotiation_bloc_test.dart`

**Interfaces:**
- Consumes: `POST /negotiations/{id}/settle-commission?commissionSource=` (Task 5), `AcceptanceResponse` / `AcceptanceStatus` (`lib/features/matching/data/models/acceptance_response.dart`), déjà capables de parser ce contrat.
- Produces: `NegotiationRepository.settleCommission(String threadId, {String commissionSource = 'WALLET_FIRST'}) → Future<AcceptanceResponse>` ; `NegotiationRepository.confirmCommission(String threadId) → Future<ConfirmResponse>` ; l'event `NegotiationSettleCommissionRequested(threadId, {useCard = false})` ; les états `NegotiationCommissionSettled` et `NegotiationCommissionInsufficientWallet(availableBalance, requiredCommission, hasCard, currency, threadId)`.
- Ajouter également l'event `NegotiationDeclineCommissionRequested(threadId)` appelant `POST /negotiations/{id}/decline-commission`, et l'état `NegotiationCommissionDeclined` : le voyageur peut renoncer explicitement, ce qui libère immédiatement la demande de l'expéditeur au lieu de le faire attendre le délai.
- Le statut `AcceptanceStatus.requires3ds` doit être traité, exactement comme le fait `BidAcceptanceBloc._handleResponse` (`lib/features/matching/bloc/bid_acceptance_bloc.dart:51-102`) : appeler `Stripe.instance.handleNextAction(clientSecret)` puis `confirmCommission(threadId)`, et n'émettre `NegotiationCommissionSettled` qu'ensuite. Le voyageur est devant son téléphone à cet instant, l'authentification forte est donc réalisable. Ajouter un test de bloc pour ce chemin.

Le datasource doit accepter les réponses 409 et 422 comme des `AcceptanceResponse` valides, à l'image de `bid_remote_datasource.dart:205-244` : ce ne sont pas des erreurs réseau mais des issues métier porteuses des montants à afficher.

- [ ] **Step 1: Écrire les tests qui échouent**

```dart
    blocTest<NegotiationBloc, NegotiationState>(
      'règlement accepté → NegotiationCommissionSettled',
      setUp: () => when(() => repository.settleCommission(any(),
              commissionSource: any(named: 'commissionSource')))
          .thenAnswer((_) async => const AcceptanceResponse(
              status: AcceptanceStatus.accepted)),
      build: () => bloc,
      act: (b) => b.add(const NegotiationSettleCommissionRequested('t1')),
      expect: () => [
        isA<NegotiationActionInProgress>(),
        isA<NegotiationCommissionSettled>(),
      ],
    );

    blocTest<NegotiationBloc, NegotiationState>(
      'solde insuffisant → état porteur des montants, pas une erreur',
      setUp: () => when(() => repository.settleCommission(any(),
              commissionSource: any(named: 'commissionSource')))
          .thenAnswer((_) async => const AcceptanceResponse(
              status: AcceptanceStatus.insufficientWallet,
              availableBalance: 1.0,
              requiredCommission: 5.0,
              hasCard: true,
              currency: 'EUR')),
      build: () => bloc,
      act: (b) => b.add(const NegotiationSettleCommissionRequested('t1')),
      expect: () => [
        isA<NegotiationActionInProgress>(),
        isA<NegotiationCommissionInsufficientWallet>()
            .having((s) => s.requiredCommission, 'commission', 5.0)
            .having((s) => s.hasCard, 'hasCard', true),
      ],
    );

    blocTest<NegotiationBloc, NegotiationState>(
      'useCard: true envoie commissionSource CARD',
      // vérifie via verify() que le repository reçoit 'CARD'
    );
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `flutter test test/features/package_request/bloc/negotiation_bloc_test.dart`
Expected: FAIL, event inexistant.

- [ ] **Step 3: Implémenter**

Dans `NegotiationRepository` :

```dart
  /// Le voyageur règle la commission Yadony d'un accord conclu en espèces.
  /// Les 409 (solde insuffisant) et 422 (échec carte) portent un corps
  /// exploitable : ce sont des issues métier, pas des erreurs de transport.
  Future<AcceptanceResponse> settleCommission(
    String threadId, {
    String commissionSource = 'WALLET_FIRST',
  }) async {
    try {
      final response = await _apiClient.dio.post<Map<String, dynamic>>(
        '/negotiations/$threadId/settle-commission',
        queryParameters: {'commissionSource': commissionSource},
      );
      return AcceptanceResponse.fromJson(response.data!);
    } on DioException catch (e) {
      // PIÈGE : ne JAMAIS détecter une issue métier par la présence d'un champ
      // `status` dans le corps. Le backend répond en RFC 7807, et toute erreur
      // générique (403, 404, 409 de la garde de course) porte un `status`
      // NUMÉRIQUE. Le parser comme une réponse métier ferait planter le cast en
      // String et masquerait le vrai motif derrière une erreur réseau opaque,
      // précisément dans le cas de course que le backend signale proprement.
      // Se fier au code HTTP, comme le fait bid_remote_datasource.dart.
      final code = e.response?.statusCode;
      final data = e.response?.data;
      if ((code == 409 || code == 422) && data is Map<String, dynamic>) {
        return AcceptanceResponse.fromJson(data);
      }
      rethrow;
    }
  }
```

Ajouter l'event, les deux états et le handler correspondant dans `NegotiationBloc`, en suivant le style des handlers voisins (`emit(NegotiationActionInProgress(...))` puis l'état final, `unawaited(_analytics.logEvent(...))` pour le succès). Déclarer le nom d'event analytics dans `AnalyticsEvents` (`static const negotiationCommissionSettled = 'negotiation_commission_settled';`) et l'ajouter au tableau des events de `dony_app/CLAUDE.md`.

- [ ] **Step 4: Lancer les tests**

Run: `flutter test test/features/package_request/bloc/negotiation_bloc_test.dart`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): reglement de commission dans le bloc"
```

---

## Task 10: Le voyageur voit et règle sa commission

**Files:** (dépôt `dony_app`)
- Create: `lib/features/package_request/presentation/widgets/commission_settlement_sheet.dart`
- Modify: `lib/features/package_request/presentation/widgets/thread/thread_state_cta_bar.dart:188-221`
- Modify: `lib/features/package_request/presentation/screens/shared/negotiation_thread_screen.dart`
- Modify: `lib/features/notifications/notification_route_resolver.dart`
- Test: `test/features/package_request/presentation/commission_settlement_test.dart` (créer)
- Test: `test/features/notifications/notification_route_resolver_test.dart`

**Interfaces:**
- Consumes: le statut `NegotiationThreadStatus.awaitingCommission` sur le fil, `NegotiationThread.commissionDeadline`, `NegotiationSettleCommissionRequested`, `NegotiationDeclineCommissionRequested` et `NegotiationCommissionInsufficientWallet` (Task 9).
- Produces: `showCommissionSettlementSheet(BuildContext context, {required double requiredCommission, required double availableBalance, required bool hasCard, required String currency, required void Function({required bool useCard}) onRetry})`.

**Ce que chaque partie doit voir, sur un fil en attente de commission :**
- Le **voyageur** : un bandeau lui disant que l'expéditeur a retenu son offre et qu'il doit confirmer avant l'échéance, le montant de la commission, le temps restant calculé depuis `commissionDeadline`, un bouton principal « Confirmer et régler » et une action discrète pour renoncer. Le renoncement demande une confirmation, il libère la demande.
- L'**expéditeur** : un bandeau indiquant qu'il attend la confirmation du voyageur, et surtout que sa demande reste ouverte, qu'il peut continuer à recevoir et accepter d'autres offres entre-temps. Ne jamais lui laisser croire que l'affaire est conclue.

Le statut du fil est `awaitingCommission`, pas `accepted` : c'est un cas distinct dans le `switch` de `thread_state_cta_bar.dart`, à ajouter à côté des autres, sans toucher au cas `accepted` existant qui reste celui des accords scellés.

**À reprendre, signalé par les tâches précédentes :**
- `thread_state_cta_bar.dart` et `thread_hero_card.dart` ont reçu un traitement minimal du nouveau statut, juste de quoi ne pas casser la compilation. C'est ici qu'ils reçoivent leur vraie UX : le bandeau, le compte à rebours, les actions. `thread_hero_card.dart` mappe aujourd'hui le nouveau statut sur la variante visuelle du paiement en attente, à revoir si une variante propre est plus juste.
- `my_negotiations_screen.dart`, `package_request_detail_body.dart` et `negotiation_filter_cubit.dart` ne traitent pas encore `awaitingCommission` dans leurs filtres et leurs libellés. Un fil en attente de commission doit y apparaître avec un libellé exact, pas retomber dans une catégorie par défaut trompeuse. Vérifier chacun.
- Le compte à rebours se calcule depuis `commissionDeadline`, que le backend émet en UTC suffixé (le sérialiseur du projet ajoute le `Z`) : comparer à `DateTime.now().toUtc()`, jamais à une heure locale. Afficher un temps restant, pas une heure absolue.
- Quand l'échéance est dépassée alors que l'app est ouverte, ne pas laisser un compte à rebours négatif : afficher que le délai est écoulé et laisser le rafraîchissement du fil rendre l'état réel.

- [ ] **Step 1: Écrire les tests qui échouent**

```dart
    testWidgets('voyageur, commission en attente → CTA de règlement', (t) async {
      // given un thread ACCEPTED, cash, commissionStatus PENDING, viewer = voyageur
      // then find.text('Régler la commission') findsOneWidget
    });

    testWidgets('expéditeur, même thread → aucun CTA de règlement', (t) async {
      // then find.text('Régler la commission') findsNothing
    });

    testWidgets('commission déjà réglée → aucun CTA', (t) async {
      // given commissionStatus CHARGED
      // then find.text('Régler la commission') findsNothing
    });

    testWidgets('solde insuffisant → sheet avec recharge et carte', (t) async {
      // when l'état NegotiationCommissionInsufficientWallet est émis
      // then find.text('Solde insuffisant'), find.text('Recharger mon portefeuille'),
      //      find.text('Payer par carte') si hasCard
    });

    test('notification de commission en attente → route vers le fil', () {
      expect(
        resolveNotificationRoute('negotiation_commission_pending', {
          'threadId': threadId,
        }),
        '/negotiations/$threadId',
      );
    });
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `flutter test test/features/package_request/presentation/commission_settlement_test.dart test/features/notifications/notification_route_resolver_test.dart`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

Créer `commission_settlement_sheet.dart` en reprenant l'UX déjà éprouvée de `_showWalletInsufficientSheet` (`lib/features/matching/presentation/screens/bid_detail_screen.dart:162-239`) : titre « Solde insuffisant », commission requise et solde affichés via `formatPriceIn`, puis en `stickyBottom` un `DonyButton` « Recharger mon portefeuille » qui pousse `/payments/wallet/topup/method` et rappelle `onRetry(useCard: false)` si la recharge a réussi, suivi de « Payer par carte » (`onRetry(useCard: true)`) ou « Ajouter une carte » (`/payments/commission-method`) selon `hasCard`. Aucun bouton de refus : l'accord est acquis.

Dans `thread_state_cta_bar.dart`, `case NegotiationThreadStatus.accepted`, ajouter avant le bouton « Voir mon envoi » :

```dart
      case NegotiationThreadStatus.awaitingCommission:
        // Rien n'est scellé tant que la commission n'est pas réglée : le voyageur
        // doit confirmer, et l'expéditeur doit savoir que sa demande court toujours.
        return _isSender
            ? ThreadStateBanner(
                iconAsset: 'clock',
                tint: cs.warning,
                message: 'En attente de confirmation du voyageur',
                subtitle: 'Votre demande reste ouverte, vous pouvez toujours recevoir d\'autres offres.',
              )
            : Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ThreadStateBanner(
                    iconAsset: 'clock',
                    tint: cs.warning,
                    message: 'Confirmez votre prise en charge',
                    subtitle: 'Réglez la commission avant l\'échéance pour emporter ce colis.',
                  ),
                  const SizedBox(height: DonySpacing.sm),
                  DonyButton(
                    label: 'Confirmer et régler',
                    onPressed: () => context.read<NegotiationBloc>().add(
                      NegotiationSettleCommissionRequested(thread.id),
                    ),
                  ),
                ],
              );
```

Dans `negotiation_thread_screen.dart`, ajouter au `listener` du `BlocConsumer` la branche qui ouvre la sheet sur `NegotiationCommissionInsufficientWallet` et affiche un `DonySnackbar` de succès sur `NegotiationCommissionSettled`.

Dans `notification_route_resolver.dart`, remplacer la route `negotiation_cash_commission_failed` (devenue sans objet, l'event n'existe plus) par :

```dart
    // Commission en attente : le fil porte le CTA de règlement, qui gère lui-même
    // la recharge si le solde est court.
    'negotiation_commission_pending' when _isUuid(threadId) =>
      '/negotiations/$threadId',
```

Mettre à jour l'icône correspondante dans `notification_bottom_sheet.dart`.

- [ ] **Step 4: Lancer les tests**

Run: `flutter test`
Expected: PASS, l'intégralité de la suite (6090 tests actuellement) reste verte.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): le voyageur regle sa commission depuis son fil"
```

---

## Task 11: L'admin voit les commissions impayées

**Files:** (dépôt `dony-back`)
- Modify: `src/main/java/com/yadony/api/admin/AdminBidsController.java:163`
- Test: `src/test/java/com/yadony/api/admin/AdminBidsControllerTest.java`

**Interfaces:**
- Consumes: `BidEntity.getCommissionStatus()`.
- Produces: le champ `commissionStatus` dans la réponse de la liste admin des bids.

Sans cela, une commission jamais réglée reste totalement invisible côté exploitation : aucun écran, aucun filtre, aucune alerte n'expose cet état aujourd'hui.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void list_exposesCommissionStatus_soUnsettledCashBidsAreVisible() {
        // given un bid CASH avec commissionStatus PENDING
        // then jsonPath("$.content[0].commissionStatus") == "PENDING"
    }
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw -o test -Dtest=AdminBidsControllerTest`
Expected: FAIL, champ absent.

- [ ] **Step 3: Implémenter**

Ajouter `commissionStatus` au DTO de la ligne de liste et le renseigner depuis l'entité à la ligne 163.

- [ ] **Step 4: Lancer le test**

Run: `./mvnw -o test -Dtest=AdminBidsControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(admin): la liste des envois expose l'etat de la commission"
```

---

## Task 12: Documentation de story

**Files:** (dépôt `dony-back`)
- Create: `docs/stories-done/story-commission-cash-acceptation-voyageur.md`

- [ ] **Step 1: Lancer les deux suites complètes**

Run: `./mvnw -o test` puis, séparément, `flutter test` dans `dony_app`
Expected: PASS des deux côtés.

- [ ] **Step 2: Rédiger la documentation**

Suivre le gabarit imposé par `dony-back/CLAUDE.md`, section « Documentation obligatoire à la fin de chaque story » : résumé, fichiers créés et modifiés, « Comment ça fonctionne » (flux étape par étape, points d'entrée API, entités, logique métier critique, events publiés/écoutés), pièges et points d'attention, critères d'acceptation, tests, décisions techniques.

Y consigner impérativement les pièges suivants, qui ont motivé la conception :
- ne jamais calculer la commission d'une négociation via `computeBidCommission` (base prix/kg de l'annonce, sans rapport avec le prix négocié dès que le trajet n'est pas dédié) ;
- ne jamais router un bid de négociation vers `POST /bids/{id}/accept-with-commission` (revérifie et redécrémente la capacité, republie `BidAcceptedEvent`) ;
- le règlement doit être reporté sur le bid matérialisé, sinon les remboursements ultérieurs ne trouvent rien à rembourser ;
- `WALLET_FIRST` ne bascule jamais sur la carte sans choix explicite du voyageur.

- [ ] **Step 3: Commit**

```bash
git add docs/stories-done/story-commission-cash-acceptation-voyageur.md
git commit -m "docs: story reglement de commission a l'acceptation"
```

---

## Self-Review

**Couverture de la décision produit :**
- « L'expéditeur doit pouvoir conclure sans être bloqué par le solde du voyageur » → Task 4 (plus aucun prélèvement ni 422 au moment de conclure).
- « Le voyageur valide derrière et recharge si besoin » → Task 5 (règlement) + Task 10 (CTA et sheet de recharge).
- « S'il refuse ou ne paie pas sous 2 h, c'est annulé et l'expéditeur choisit quelqu'un d'autre » → Task 6 (renoncement explicite et expiration réglable).
- « La demande ne se termine pas tant que Yadony n'a pas encaissé » → Task 3 (statut dédié) + Task 4 (la finalisation est extraite et différée) + Task 5 (c'est le règlement qui scelle).
- « Premier arrivé premier servi » → Task 5, garde de course avant tout débit.
- « Pour les autres modes, c'est le paiement carte de l'expéditeur qui conclut » → Task 4, branche inchangée, avec un test de non-régression explicite.

**Risques couverts :** contrainte PostgreSQL sur le statut (Task 3, le piège invisible aux tests), trajet dédié orphelin sur les deux nouvelles sorties (Task 6), base de calcul de la commission (contrainte globale), débit d'un voyageur pour une demande déjà prise (Task 5), invisibilité côté exploitation (Task 11).

**Cohérence des noms entre tâches :** `AWAITING_COMMISSION` (Task 3, consommé Tasks 4-7 et 10), `sealAcceptedThread` (Task 4, appelé Task 5), `settleCommission` / `confirmCommission` / `declineCommission` (Task 5-6, consommés Task 9), `commissionStatus` et `commissionDeadline` (Task 7, modèle Dart Task 8, UI Task 10), `NegotiationCommissionPendingEvent` (Task 4), `dony.negotiation.commission-window-minutes` (Task 4, lu Tasks 6-7).

**Note d'exécution :** les Tasks 1, 2 et 8 ont été implémentées et relues avant ce changement de conception, et restent valides telles quelles. La Task 8 doit toutefois être complétée par `commissionDeadline` au moment de la Task 10.

---

## Task 13: Le fil Flutter comprend le nouveau statut, et survit à ceux qu'il ne connaît pas

**Files:** (dépôt `dony_app`)
- Modify: `lib/features/package_request/data/models/negotiation_thread.dart`
- Test: `test/features/package_request/data/models/negotiation_thread_test.dart`

**Interfaces:**
- Produces: `NegotiationThreadStatus.awaitingCommission` (valeur de fil `'AWAITING_COMMISSION'`), incluse dans `isActive` ; `NegotiationThread.commissionDeadline` (`DateTime?`) ; un `fromJson` qui ne lève plus sur un statut inconnu.

**Pourquoi cette tâche existe, et pourquoi elle doit passer AVANT le déploiement du backend.** `NegotiationThreadStatus.fromJson` fait aujourd'hui un `firstWhere` sans `orElse` : sur une valeur de statut qu'elle ne connaît pas, elle lève une `StateError` et le fil de négociation devient impossible à ouvrir. Le jour où le backend commence à renvoyer `AWAITING_COMMISSION`, toutes les versions de l'app déployées avant cette tâche cassent sur ce fil. Deux conséquences : cette valeur doit être ajoutée au modèle, et le parsing doit devenir tolérant pour que le prochain statut ajouté ne reproduise pas la panne.

- [ ] **Step 1: Écrire les tests qui échouent**

```dart
    test('AWAITING_COMMISSION est reconnu et compté comme actif', () {
      expect(
        NegotiationThreadStatus.fromJson('AWAITING_COMMISSION'),
        NegotiationThreadStatus.awaitingCommission,
      );
      expect(NegotiationThreadStatus.awaitingCommission.isActive, isTrue);
    });

    // Garde-fou de compatibilité : un statut inconnu ne doit jamais rendre un fil
    // impossible à ouvrir. Avant ce correctif, firstWhere levait une StateError.
    test('un statut inconnu ne lève pas et retombe sur open', () {
      expect(NegotiationThreadStatus.fromJson('STATUT_DU_FUTUR'),
          NegotiationThreadStatus.open);
    });

    test('fromJson lit commissionDeadline', () {
      final thread = NegotiationThread.fromJson({
        ...baseJson,
        'commissionDeadline': '2026-08-16T14:30:00',
      });
      expect(thread.commissionDeadline, DateTime.parse('2026-08-16T14:30:00'));
    });

    test('commissionDeadline absent reste null', () {
      expect(NegotiationThread.fromJson(baseJson).commissionDeadline, isNull);
    });
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `flutter test test/features/package_request/data/models/negotiation_thread_test.dart`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

```dart
  awaitingCommission('AWAITING_COMMISSION'),
```

```dart
  /// Un statut inconnu ne doit jamais empêcher d'ouvrir un fil : le backend peut
  /// en ajouter avant que cette version de l'app ne soit installée. On retombe
  /// sur `open`, l'état le moins engageant, plutôt que de lever.
  static NegotiationThreadStatus fromJson(String s) =>
      NegotiationThreadStatus.values.firstWhere(
        (e) => e.wireName == s,
        orElse: () => NegotiationThreadStatus.open,
      );
```

Inclure `awaitingCommission` dans `isActive`, ajouter le champ `commissionDeadline` (`DateTime?`) au constructeur, à `fromJson` (`json['commissionDeadline'] == null ? null : DateTime.parse(json['commissionDeadline'] as String)`) et à `props`.

- [ ] **Step 4: Lancer les tests**

Run: `flutter test test/features/package_request/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): le fil comprend le statut d'attente de commission"
```
