# Sécurité du portefeuille, lot B : argent perdu ou incohérent sans alerte (Backend)

**Date:** 2026-10-28
**Status:** ✅ Complète

## Résumé
Second lot des correctifs de l'audit du portefeuille du 28/10. Les mouvements portés par un
écouteur après commit (recrédit de commission, crédit d'une recharge mobile money, récompense de
parrainage) se perdaient sans reprise quand ils échouaient. Un litige carte sur une recharge ne
déclenchait qu'une alerte, et le solde restait dépensable. Une confirmation pawaPay tardive
n'était qu'une ligne de log. À la suppression d'un compte, un échec sur une devise annulait les
forfaits des autres.

## Fichiers créés
- `payments/cash/job/CommissionRefundRecoveryJob.java` — rattrapage des commissions restées CHARGED sur un colis clos.
- `payments/wallet/WalletDisputeService.java`, `WalletDisputeHoldEntity.java`, `WalletDisputeHoldRepository.java`, `WalletDisputeHoldStatus.java` — litige sur une recharge : reprise, gel, recrédit si gagné.
- `payments/wallet/WalletCreditRecoveryJob.java` — rattrapage des recharges mobile money et récompenses de parrainage jamais créditées.
- `payments/pawapay/PawapayLateCompletionAlerter.java`, `events/PawapayLateCompletionEvent.java` — alerte critique sur une confirmation tardive.
- `db/migration/V357__wallet_topup_dispute_holds.sql` — types `TOPUP_DISPUTED` / `TOPUP_DISPUTE_REVERSED`, table `wallet_dispute_holds`.
- `db/migration/V358__referral_invitations_unique_referee.sql` — index unique partiel du filleul (seulement sans doublon historique).

## Fichiers modifiés
- `payments/cash/BidCancelledCommissionRefundListener.java` — `recover(bidId)` pour le rattrapage.
- `matching/BidRepository.java` — colis clos dont la commission est restée CHARGED.
- `payments/integrity/MoneyInvariants.java` — INV-10 couvre l'absence, le colis refusé (depuis le 19/10) et les colis négociés ; INV-03 connaît les nouveaux types.
- `payments/wallet/WalletService.java` — gel par litige (`wallet-dispute-hold`), `debitDisputedTopup`.
- `payments/wallet/WalletRefundAllocator.java` — la reprise vide d'abord la recharge contestée ; le recrédit la rend remboursable.
- `payments/wallet/WalletSelfRefundService.java` — aucune demande de remboursement pendant un litige.
- `payments/chargeback/ChargebackService.java` — branche le litige de recharge à l'ouverture et à la clôture.
- `payments/wallet/WalletTopupOutcomeListener.java` — la notification ne peut plus annuler le crédit.
- `payments/wallet/WalletTransactionRepository.java` — requêtes des crédits manqués.
- `payments/pawapay/PawapayOperationService.java`, `reconciliation/PawapayReconciler.java` — événement de confirmation tardive ; rapprochement des SUBMIT_REJECTED.
- `referral/ReferralService.java` — verrou du filleul avant le test « déjà parrainé ».
- `payments/UserFinalizedPaymentsListener.java` — une transaction par devise.
- Fiche assistant `rembourser-solde` (fr, en).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. **Commission non rendue** : toutes les 30 min, `CommissionRefundRecoveryJob` relit les colis espèces annulés, refusés, absents ou au colis refusé depuis 1 h à 7 jours, encore CHARGED, et appelle `recover` (une transaction par colis). Le recrédit verrouille le colis et relit son statut (lot A) : jamais de double recrédit.
2. **Litige sur une recharge** : `charge.dispute.created` sans paiement de colis → `WalletDisputeService.onDisputeOpened` retrouve la recharge par son PaymentIntent, reprend `min(contesté, solde)` (`TOPUP_DISPUTED`, clé `dispute-<id>`) et enregistre le litige OPEN (la devise est gelée). `charge.dispute.closed` → WON : recrédit `TOPUP_DISPUTE_REVERSED` (clé `dispute-won-<id>`), LOST : seulement le dégel.
3. **Confirmation tardive pawaPay** : `apply` refuse toujours la transition, mais publie `PawapayLateCompletionEvent` ; l'alerte `pawapay-late-completed-<opération>` est levée une seule fois.
4. **Crédits perdus** : toutes les 15 min, `WalletCreditRecoveryJob` crédite avec leur clé d'origine les dépôts pawaPay COMPLETED sans ligne `pawapay-topup-<id>` (7 jours) et les récompenses CHOSEN/WALLET_CREDIT sans ligne `referral-reward:<id>` (30 jours).
5. **Suppression** : le forfait du non-cash de chaque devise vit dans sa propre transaction `REQUIRES_NEW`.

### Points d'entrée API
- Aucun nouvel endpoint. Nouveau code d'erreur 422 `wallet-dispute-hold` sur tout débit et toute demande de remboursement d'une devise en litige.

### Entités JPA impliquées
- `WalletDisputeHoldEntity` → `wallet_dispute_holds` : `stripe_dispute_id` unique, `debited_amount ≤ disputed_amount`, statut OPEN/WON/LOST.
- `wallet_transactions` : types `TOPUP_DISPUTED` (négatif) et `TOPUP_DISPUTE_REVERSED` (positif), contraintes de type et de signe étendues.

### Logique métier critique
- Tous les rattrapages rejouent avec la clé d'origine : un écouteur qui aboutit en même temps ne produit jamais deux crédits.
- Les fenêtres bornées (7 jours, 30 jours) gardent hors d'atteinte les données antérieures aux règles actuelles, que seuls les invariants signalent.
- La reprise d'un litige est au plus le solde : le reste dû est signalé à l'admin (`remainingDue`).

### Events Spring publiés / écoutés
- `PawapayLateCompletionEvent` publié par `PawapayOperationService#apply`, écouté par `PawapayLateCompletionAlerter`.

### Pièges et points d'attention
- `hibernate.jdbc.time_zone: UTC` décale les `LocalDateTime` liés : une donnée écrite hors Hibernate (SQL brut) doit en tenir compte.
- V358 ne pose pas l'index s'il existe déjà des filleuls en double (avertissement en base) : le verrou du filleul tient la règle en attendant.
- `CommissionRefundRecoveryJob` et `WalletCreditRecoveryJob` ne doivent pas devenir `@Transactional` (tests de garde).

## Critères d'acceptation couverts
- [x] Une commission non rendue est rattrapée automatiquement et signalée — `CommissionRefundRecoveryJobTest`, `MoneyIntegrityMonitorIT`.
- [x] Litige sur une recharge : reprise dans la limite du solde, gel, recrédit si gagné, rejeu sans effet — `WalletDisputeServiceTest`, `WalletRefundIT`.
- [x] Confirmation pawaPay tardive : alerte critique unique — `PawapayOperationServiceTest`, `PawapayLateCompletionAlerterTest`.
- [x] Recharge mobile money et récompense de parrainage jamais créditées : rattrapées une seule fois — `WalletCreditRecoveryJobTest`, `WalletRefundIT`.
- [x] Suppression : un échec sur une devise n'annule pas les autres — `UserFinalizedPaymentsListenerTest`.

## Tests
- `./mvnw clean test jacoco:report` → 14 149 tests, 0 échec, 0 erreur (7 ignorés, préexistants).
- Couverture JaCoCo des lignes : 96,43 % (nouvelles classes 100 %, WalletRefundAllocator 95,7 %, ChargebackService 96,3 %).

## Décisions techniques
- **Table de litiges plutôt qu'un drapeau sur le portefeuille** : elle garde le montant repris, nécessaire au recrédit, et l'historique de chaque litige.
- **Événement plutôt qu'une dépendance** pour l'alerte pawaPay : le constructeur du service reste inchangé.
- **Index du filleul conditionnel** : un doublon historique ferait échouer la migration, donc le déploiement.
- Comportement du litige (reprise et gel) : décision du propriétaire du 28/10.
