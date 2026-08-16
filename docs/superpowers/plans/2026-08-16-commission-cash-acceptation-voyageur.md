# Prélèvement de la commission cash à l'acceptation par le voyageur — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pour un accord de négociation réglé en espèces, l'expéditeur conclut toujours sans être bloqué par le solde du voyageur ; la commission Yadony est prélevée plus tard, quand le voyageur confirme la prise en charge, avec une invitation à recharger son portefeuille s'il est court.

**Architecture:** `finalizeInternal` cesse de prélever la commission et de lever 422 ; le bid matérialisé porte `commissionStatus = PENDING`. Un nouvel endpoint voyageur `POST /negotiations/{id}/settle-commission` prélève sur le thread via une méthode dédiée qui prend le net négocié en paramètre (jamais `computeBidCommission`, qui recalculerait sur le prix/kg de l'annonce), respecte `CommissionSource` (portefeuille d'abord, carte sur choix explicite) et renvoie le contrat `AcceptBidResponse` déjà consommé par l'app. Côté Flutter, le thread accepté expose une action « Régler la commission » qui réutilise l'UX « Solde insuffisant » existante.

**Tech Stack:** Spring Boot 3.4 / Java 21 / PostgreSQL 16 / Flyway (backend `dony-back`) — Flutter / flutter_bloc / GoRouter / Dio (frontend `dony_app`).

## Global Constraints

- Deux dépôts git **séparés** : `dony-back` (branche `worktree-offre-lie-trajet-back`) et `dony_app` (branche `worktree-offre-lie-trajet-front`). Une tâche ne touche jamais les deux à la fois. Ne jamais créer de branche, ne jamais commiter sur `main`.
- Ne jamais inclure `Co-Authored-By: Claude` dans un message de commit.
- Toute la copie visible par l'utilisateur est en français, sans tiret cadratin (`—`) dans les textes affichés : utiliser une virgule. Les commentaires de code sont exemptés.
- Le montant de la commission d'une négociation se calcule **toujours** depuis le net négocié (`thread.getCurrentPriceEur()`), jamais via `CashCommissionService.computeBidCommission`, qui se base sur `bid.weightKg × announcement.pricePerKg` et serait faux sur un trajet non dédié.
- Ne jamais réutiliser `POST /bids/{bidId}/accept-with-commission` pour un bid issu de négociation : il revérifie et redécrémente la capacité de l'annonce (déjà décomptée à la matérialisation, à 0 sur un trajet dédié) et republie `BidAcceptedEvent`.
- Aucune migration Flyway n'est nécessaire : `PENDING` est déjà autorisé par le `CHECK` posé sur `bids.commission_status` en V74, et `negotiation_threads.commission_status` n'a aucune contrainte. La dernière migration existante est V210 : si une tâche en ajoutait une malgré tout, ce serait V211.
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

## Task 3: L'expéditeur conclut sans être bloqué par le solde du voyageur

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java:987-1006`
- Delete: `src/main/java/com/yadony/api/requests/event/NegotiationCashCommissionFailedEvent.java`
- Create: `src/main/java/com/yadony/api/requests/event/NegotiationCashCommissionPendingEvent.java`
- Modify: `src/main/java/com/yadony/api/notifications/RequestEventsListener.java`
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- Test: `src/test/java/com/yadony/api/notifications/RequestEventsListenerTest.java`

**Interfaces:**
- Consumes: `PriceBreakdown.fromNet(net, rate).commission()`, `CommissionProperties.rate()`.
- Produces: `NegotiationCashCommissionPendingEvent(UUID threadId, UUID packageRequestId, UUID travelerId, UUID senderId, BigDecimal commissionAmount, String currency)`, publié par `finalizeInternal` juste après le passage du thread en `ACCEPTED`, pour un thread CASH uniquement.

- [ ] **Step 1: Écrire les tests qui échouent**

Dans `NegotiationServiceTest`, classe interne `FinalizeAfterPaymentTests`, remplacer le test `finalize_cashThread_commissionFails_publishesCashCommissionFailedEvent` (ajouté au commit précédent, il assertait l'ancienne règle) par :

```java
        // L'expéditeur ne doit jamais être bloqué par le solde du voyageur : le
        // règlement de la commission est désormais une étape ultérieure, à la main
        // du voyageur seul.
        @Test
        void finalize_cashThread_neverChargesCommission_andPublishesPendingEvent() {
            // given un thread CASH en AWAITING_PAYMENT, prix négocié 100.00 EUR
            // when finalizeAfterPayment(senderId, threadId, null, CASH)
            // then thread.status == ACCEPTED (aucune 422),
            //      verifyNoInteractions(cashGatePort),
            //      un NegotiationCashCommissionPendingEvent est publié avec
            //      commissionAmount == 100.00 × taux et currency == "EUR"
        }

        @Test
        void finalize_stripeThread_publishesNoCommissionPendingEvent() {
            // then aucun NegotiationCashCommissionPendingEvent parmi les events publiés
        }
```

Dans `RequestEventsListenerTest`, remplacer `onNegotiationCashCommissionFailed_notifiesTraveler` par :

```java
    @Test
    void onNegotiationCashCommissionPending_notifiesTravelerToSettle() {
        // then dispatcher.notifyUser appelé avec travelerId et un message
        //      contenant "commission", data["type"] == "negotiation_commission_pending"
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,RequestEventsListenerTest'`
Expected: FAIL, `NegotiationCashCommissionPendingEvent` n'existe pas.

- [ ] **Step 3: Implémenter**

Supprimer `NegotiationCashCommissionFailedEvent.java` et créer :

```java
package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un accord de négociation réglé en espèces vient d'être conclu : la commission
 * Yadony reste à régler par le voyageur. L'accord est acquis, rien n'est en
 * échec et rien ne s'annule ; le voyageur est simplement invité à la régler
 * depuis son fil de négociation, en rechargeant son portefeuille si besoin.
 */
public record NegotiationCashCommissionPendingEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID travelerId,
    UUID senderId,
    BigDecimal commissionAmount,
    String currency
) {}
```

Dans `NegotiationService.finalizeInternal`, remplacer entièrement le bloc `if (thread.getPaymentMethod() == PaymentMethod.CASH) { ... }` (lignes 987-1006) par un simple `else if (verifyEscrow)` conservant la vérification d'escrow Stripe, puis, après `threadRepo.save(thread)` (ligne ~1022), publier l'event pour les threads CASH :

```java
        // Le règlement de la commission n'est plus une condition de l'accord : il
        // appartient au voyageur, qui peut recharger son portefeuille à ce moment-là.
        // Bloquer l'expéditeur ici reviendrait à lui faire porter un solde qui n'est
        // pas le sien.
        if (thread.getPaymentMethod() == PaymentMethod.CASH) {
            BigDecimal commission = PriceBreakdown
                .fromNet(thread.getCurrentPriceEur(), commissionProperties.rate()).commission();
            eventPublisher.publishEvent(new NegotiationCashCommissionPendingEvent(
                thread.getId(), request.getId(), thread.getTravelerId(),
                request.getSenderId(), commission, thread.getCurrency()));
        }
```

Dans `RequestEventsListener`, remplacer `onNegotiationCashCommissionFailed` par :

```java
    /**
     * Accord en espèces conclu : la commission reste à régler par le voyageur.
     * Publié depuis une transaction qui commit, d'où l'AFTER_COMMIT — notifier
     * avant le commit exposerait à annoncer un accord qui n'existe pas.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCashCommissionPending(NegotiationCashCommissionPendingEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Commission à régler",
            String.format(
                "Votre accord est conclu. Réglez la commission de %.2f %s pour finaliser la prise en charge.",
                e.commissionAmount(), e.currency()),
            Map.of(
                "type", "negotiation_commission_pending",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }
```

Adapter enfin les tests existants qui référençaient `cashGatePort.chargeNegotiationCashCommission` dans `NegotiationServiceTest` : supprimer les stubs devenus inutiles plutôt que les tests.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,RequestEventsListenerTest,NegotiationControllerIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): l'accord en especes se conclut sans prelevement immediat"
```

---

## Task 4: Le bid matérialisé naît avec sa commission en attente

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/ThreadAcceptedBidListener.java:111-124`
- Modify: `src/main/java/com/yadony/api/payments/wallet/WalletCancellationListener.java:59-79`
- Test: `src/test/java/com/yadony/api/matching/ThreadAcceptedBidListenerTest.java`

**Interfaces:**
- Consumes: `PackageRequestAcceptedEvent.paymentMethod()`, `CommissionStatus.PENDING`.
- Produces: un bid CASH issu de négociation porte `commissionStatus == CommissionStatus.PENDING` et `commissionChargedVia == null` jusqu'au règlement.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    // La commission n'est plus prélevée à la matérialisation : le bid naît en
    // attente, et c'est le règlement par le voyageur qui le fera passer CHARGED.
    @Test
    void onPackageRequestAccepted_cashBid_isCreatedWithPendingCommission() {
        // when onPackageRequestAccepted(event avec paymentMethod = CASH)
        // then bid.commissionStatus == CommissionStatus.PENDING
        //      && bid.commissionChargedVia == null
        //      && bid.status == BidStatus.ACCEPTED
    }

    @Test
    void onPackageRequestAccepted_stripeBid_leavesCommissionStatusUntouched() {
        // then bid.commissionStatus == null (la commission Stripe passe par application_fee)
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest=ThreadAcceptedBidListenerTest`
Expected: FAIL, le bid est créé en `CHARGED`.

- [ ] **Step 3: Implémenter**

Dans `ThreadAcceptedBidListener`, remplacer le bloc `if (e.paymentMethod() == CASH)` (lignes 111-124) par :

```java
        if (e.paymentMethod() == com.yadony.api.payments.cash.PaymentMethod.CASH) {
            // La commission d'un accord négocié en espèces se règle après coup, par
            // le voyageur. Le bid naît donc en attente : c'est le règlement qui le
            // fera passer CHARGED, avec le canal réellement utilisé.
            bid.setCommissionStatus(com.yadony.api.payments.cash.CommissionStatus.PENDING);
        }
```

Dans `WalletCancellationListener.processWalletRefundForBid`, ajouter la garde qui manque avant d'appeler `refundCommissionToWallet` :

```java
        // Une commission jamais prélevée n'a rien à rembourser. Sans cette garde,
        // chaque annulation touchant un bid en attente de règlement produit un
        // avertissement trompeur dans les logs.
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) {
            return;
        }
```

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest='ThreadAcceptedBidListenerTest,WalletCancellationListenerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(commission): le bid negocie en especes nait avec sa commission en attente"
```

---

## Task 5: Le voyageur règle sa commission depuis son fil de négociation

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java`
- Modify: `src/main/java/com/yadony/api/requests/controller/NegotiationController.java`
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- Test: `src/test/java/com/yadony/api/requests/controller/NegotiationControllerIT.java`

**Interfaces:**
- Consumes: `CashGatePort.settleNegotiationCommission(...)` (Task 2), `BidRepository.findByLinkedNegotiationThreadId(UUID)`.
- Produces: `AcceptBidResponse settleCommission(UUID callerId, UUID threadId, CommissionSource source)` sur `NegotiationService`, et l'endpoint `POST /api/v1/negotiations/{id}/settle-commission?commissionSource=WALLET_FIRST|CARD`.

Après un règlement réussi, le bid matérialisé doit passer `CHARGED` avec le canal utilisé : sans cela, un remboursement ultérieur (annulation, no-show) verrait `PENDING` et n'aurait rien à rembourser, alors que le voyageur a bien payé.

- [ ] **Step 1: Écrire les tests qui échouent**

Dans `NegotiationServiceTest` :

```java
    @Test
    void settleCommission_notTraveler_throws403() {
        // when settleCommission(senderId, threadId, WALLET_FIRST)
        // then ResponseStatusException 403 "negotiation/not-traveler"
    }

    @Test
    void settleCommission_threadNotAccepted_throws409() {
        // given un thread OPEN
        // then 409 "thread/not-accepted"
    }

    @Test
    void settleCommission_notCash_throws409() {
        // given un thread ACCEPTED dont paymentMethod == STRIPE
        // then 409 "commission/not-cash"
    }

    @Test
    void settleCommission_success_propagatesChargedToMaterializedBid() {
        // given cashGatePort renvoie accepted() et thread.commissionChargedVia == "WALLET"
        // then le bid lié passe commissionStatus CHARGED et commissionChargedVia WALLET
    }

    @Test
    void settleCommission_insufficientWallet_leavesBidPending() {
        // given cashGatePort renvoie insufficientWallet(1.00, 5.00, true, "EUR")
        // then la réponse porte INSUFFICIENT_WALLET et le bid reste PENDING
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
Expected: FAIL, `settleCommission` n'existe pas.

- [ ] **Step 3: Implémenter**

Dans `NegotiationService` :

```java
    /**
     * Le voyageur règle la commission Yadony d'un accord conclu en espèces. C'est le
     * seul moment où son solde compte : l'accord est déjà acquis et ne peut plus être
     * remis en cause, un solde insuffisant ne fait que retarder le règlement.
     */
    @Transactional
    public AcceptBidResponse settleCommission(UUID callerId, UUID threadId, CommissionSource source) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-accepted");
        }
        if (thread.getPaymentMethod() != PaymentMethod.CASH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "commission/not-cash");
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        AcceptBidResponse resp = cashGatePort.settleNegotiationCommission(
            thread.getTravelerId(), request.getSenderId(), threadId, thread.getCurrentPriceEur(), source);

        // Report du règlement sur le bid matérialisé : les remboursements ultérieurs
        // (annulation, no-show) se basent sur le statut du bid, pas sur celui du thread.
        if (resp.status() == AcceptanceStatusDto.ACCEPTED) {
            bidRepository.findByLinkedNegotiationThreadId(threadId).ifPresent(bid -> {
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                if (thread.getCommissionChargedVia() != null) {
                    bid.setCommissionChargedVia(
                        CommissionChargedVia.valueOf(thread.getCommissionChargedVia()));
                }
                bidRepository.save(bid);
            });
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_SETTLED", callerId,
                Map.of("via", String.valueOf(thread.getCommissionChargedVia())));
        }
        return resp;
    }
```

Injecter `BidRepository bidRepository` dans le constructeur de `NegotiationService` si absent.

Dans `NegotiationController` :

```java
    @PostMapping("/{id}/settle-commission")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<AcceptBidResponse> settleCommission(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "WALLET_FIRST") CommissionSource commissionSource) {
        AcceptBidResponse resp = service.settleCommission(requireUserId(), id, commissionSource);
        return switch (resp.status()) {
            case ACCEPTED -> ResponseEntity.ok(resp);
            case REQUIRES_3DS -> ResponseEntity.accepted().body(resp);
            case INSUFFICIENT_WALLET -> ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
            case FAILED -> ResponseEntity.unprocessableEntity().body(resp);
        };
    }
```

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest='NegotiationServiceTest,NegotiationControllerIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): endpoint de reglement de commission pour le voyageur"
```

---

## Task 6: L'app sait qu'une commission reste à régler

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/dto/NegotiationThreadResponse.java`
- Modify: `src/main/java/com/yadony/api/requests/service/NegotiationService.java` (méthode `toResponse`)
- Test: `src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`

**Interfaces:**
- Produces: `NegotiationThreadResponse.commissionStatus` (`String`, dernier champ du record), valant `"PENDING"`, `"CHARGED"` ou `null`. Un constructeur de compatibilité sans ce champ est ajouté, à l'image de ceux qui existent déjà, pour ne pas retoucher tous les tests.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void toResponse_cashAcceptedThread_exposesCommissionStatus() {
        // given un thread ACCEPTED, CASH, commissionStatus "PENDING" en base
        // then la réponse porte commissionStatus() == "PENDING"
    }
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw -o test -Dtest=NegotiationServiceTest`
Expected: FAIL, méthode `commissionStatus()` inexistante.

- [ ] **Step 3: Implémenter**

Ajouter en dernier champ du record `NegotiationThreadResponse` :

```java
    ,
    // État du règlement de la commission Yadony pour un accord en espèces :
    // "PENDING" tant que le voyageur ne l'a pas réglée, "CHARGED" ensuite.
    // Null pour les accords réglés par carte, dont la commission passe par
    // l'application_fee Stripe.
    String commissionStatus
```

Ajouter juste après le dernier constructeur de compatibilité existant un constructeur reprenant tous les paramètres sauf `commissionStatus`, qui délègue au canonique avec `null`. Renseigner le champ dans `toResponse` depuis `t.getCommissionStatus()`.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test`
Expected: PASS, l'intégralité de la suite (3153 tests actuellement) reste verte.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(negociation): la reponse de fil expose l'etat de la commission"
```

---

## Task 7: Relance quotidienne des commissions en attente

**Files:**
- Create: `src/main/java/com/yadony/api/requests/service/CashCommissionReminderRunner.java`
- Modify: `src/main/java/com/yadony/api/requests/repository/NegotiationThreadRepository.java`
- Test: `src/test/java/com/yadony/api/requests/service/CashCommissionReminderRunnerTest.java` (créer)

**Interfaces:**
- Consumes: `NegotiationCashCommissionPendingEvent` (Task 3) — republié à chaque relance, ce qui réutilise la notification déjà écrite.
- Produces: `NegotiationThreadRepository.findAcceptedCashThreadsWithUnsettledCommission()` et le runner planifié.

Rien ne s'annule jamais : la relance est le seul mécanisme de rappel, conformément à la décision produit.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    @Test
    void remind_publishesPendingEventForEachUnsettledThread() {
        // given 2 threads ACCEPTED/CASH sans commission réglée
        // then 2 NegotiationCashCommissionPendingEvent publiés
    }

    @Test
    void remind_skipsThreadsAlreadyCharged() {
        // given un thread dont commissionStatus == "CHARGED"
        // then aucun event publié
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw -o test -Dtest=CashCommissionReminderRunnerTest`
Expected: FAIL, classe inexistante.

- [ ] **Step 3: Implémenter**

Dans `NegotiationThreadRepository` :

```java
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status = com.yadony.api.requests.entity.NegotiationThreadStatus.ACCEPTED
          AND t.paymentMethod = com.yadony.api.payments.cash.PaymentMethod.CASH
          AND (t.commissionStatus IS NULL OR t.commissionStatus <> 'CHARGED')
    """)
    List<NegotiationThreadEntity> findAcceptedCashThreadsWithUnsettledCommission();
```

Créer le runner, sur le modèle de `NegotiationExpiryRunner` (même package, même style d'annotations) : `@Component`, méthode annotée `@Scheduled(cron = "0 0 9 * * *")` (une relance par jour à 9 h), qui parcourt les threads renvoyés par la requête, recalcule la commission via `PriceBreakdown.fromNet(...)` et republie `NegotiationCashCommissionPendingEvent`. Le runner est idempotent par construction : il ne modifie rien, il notifie.

- [ ] **Step 4: Lancer les tests**

Run: `./mvnw -o test -Dtest=CashCommissionReminderRunnerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(commission): relance quotidienne des commissions en attente"
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
- Produces: `NegotiationRepository.settleCommission(String threadId, {String commissionSource = 'WALLET_FIRST'}) → Future<AcceptanceResponse>` ; l'event `NegotiationSettleCommissionRequested(threadId, {useCard = false})` ; les états `NegotiationCommissionSettled` et `NegotiationCommissionInsufficientWallet(availableBalance, requiredCommission, hasCard, currency, threadId)`.

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
      final data = e.response?.data;
      if (data is Map<String, dynamic> && data['status'] != null) {
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
- Consumes: `NegotiationThread.needsCommissionSettlement` (Task 8), `NegotiationSettleCommissionRequested` et `NegotiationCommissionInsufficientWallet` (Task 9).
- Produces: `showCommissionSettlementSheet(BuildContext context, {required double requiredCommission, required double availableBalance, required bool hasCard, required String currency, required void Function({required bool useCard}) onRetry})`.

Le CTA n'apparaît que pour le voyageur (`thread.travelerId == viewerUserId`) : l'expéditeur n'a rien à régler et ne doit pas voir un bouton qui ne le concerne pas.

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
            // Le voyageur est seul concerné par la commission : l'expéditeur, lui,
            // règle en espèces à la remise et n'a rien à faire ici.
            if (!_isSender && thread.needsCommissionSettlement) ...[
              const SizedBox(height: DonySpacing.sm),
              DonyButton(
                label: 'Régler la commission',
                onPressed: () => context.read<NegotiationBloc>().add(
                  NegotiationSettleCommissionRequested(thread.id),
                ),
              ),
            ],
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
- « L'expéditeur doit pouvoir payer » → Task 3 (plus aucun prélèvement ni 422 au finalize).
- « Le voyageur valide et accepte » → Task 5 (endpoint) + Task 10 (CTA), sans possibilité de refus, conformément au choix « confirmer seulement ».
- « C'est au moment d'accepter qu'il recharge » → Task 1 (`INSUFFICIENT_WALLET` au lieu d'un débit carte automatique) + Task 10 (sheet de recharge).
- « Si le voyageur ne fait rien, ça reste en attente avec relance » → Task 7, aucune expiration nulle part.

**Risques identifiés à l'exploration, tous couverts :** base de calcul de la commission (contrainte globale + Task 1), réutilisation dangereuse de l'endpoint bid (contrainte globale), report sur le bid matérialisé (Task 5), avertissement de log parasite (Task 4), invisibilité côté exploitation (Task 11), absence de migration nécessaire (contrainte globale).

**Cohérence des noms entre tâches :** `settleNegotiationCommission` (service et port, Tasks 1-2), `settleCommission` (service de négociation et endpoint, Task 5), `commissionStatus` (DTO Task 6, modèle Dart Task 8), `needsCommissionSettlement` (Task 8, consommé Task 10), `NegotiationCashCommissionPendingEvent` (Task 3, republié Task 7), `negotiation_commission_pending` (type de notification, Tasks 3 et 10).
