# Story frais d'annulation à la charge de l'expéditeur (Backend)

**Date :** 2026-11-21
**Status :** ✅ Complète côté back, livrée ÉTEINTE (`yadony.cancellation-fee.enabled=false`). Suite complète locale : seuls deux tests de migration de fuseaux restent rouges (`V299V300TripCountryAndTimezoneMigrationTest`, `V344HandoverDeadlineLocalToUtcMigrationTest`), attribués au cache `embedded-pg` tronqué de la machine (fichiers de fuseaux absents), à confirmer sur la CI.

Spec : `docs-claude-plans-draft/2026-11-21-frais-annulation-expediteur-design.md` (hors dépôt). Dépôts jumeaux à venir : app (affichage de l'aperçu), yadony-web (barème dans les CGU).

## Résumé

Quand un expéditeur fautif annule un colis accepté et payé en ligne (carte capturée ou mobile money), qu'il ne remet pas son colis (absence confirmée) ou qu'il annule après la remise avant le départ, des frais sont retenus sur son remboursement selon un barème décompté sur le départ du trajet (0 % au-delà de 5 jours, 10 % de 5 à 3 jours, 20 % de 3 jours à 1 jour, 30 % à moins d'un jour, plafond 30 € par colis). Le net de ces frais (moins les frais du prestataire et la commission Yadony) est versé au voyageur : Transfer Stripe Connect sur la carte, payout pawaPay sur le mobile money. Le voyageur n'est jamais pénalisé en argent. La fonction est livrée éteinte et ne s'applique qu'aux colis créés à partir d'une date d'effet.

## Fichiers créés

Migrations (renumérotées avant fusion : V390 à V392 sur la branche, V390 étant pris sur `main` par #774) :
- `src/main/resources/db/migration/V393__cancellation_fees.sql` : table `cancellation_fees`, index unique partiel `ux_cancellation_fees_bid (bid_id) WHERE deleted_at IS NULL`, index `ix_cancellation_fees_status`.
- `src/main/resources/db/migration/V394__pawapay_cancellation_fee_payout.sql` : colonne `pawapay_operations.cancellation_fee_id`, purpose `CANCELLATION_FEE` (PAYOUT seulement), index unique partiel `uq_pawapay_ops_live_per_cancellation_fee`.
- `src/main/resources/db/migration/V395__cancellation_fee_payout_follow_up.sql` : colonnes `payout_pending_reason` et `payout_rearmed_at`.

Paquet `cancellation/fee/` (la décision et la ligne de la retenue) :
- `CancellationFeeEntity`, `CancellationFeeRepository`, `CancellationFeeStatus`, `CancellationFeeReason` : la ligne de la retenue.
- `CancellationFeeProperties` : interrupteur, date d'effet (lue en texte, analysée strictement), plafond, barème validé au démarrage.
- `CancellationFeeTiers` : taux du barème selon le temps restant avant le départ, et instant de la prochaine hausse.
- `CancellationFeeEligibility` : SEULE décision « ce colis peut-il porter des frais, à quel taux maintenant ».
- `SenderCancellationFeeRecorder` : écrit la ligne `DUE` au moment de la faute.
- `CancellationFeeRatePortAdapter` : implémente le port `payments.fee.CancellationFeeRatePort` (taux dû sous verrou, ligne réglée ou close).
- `CancellationFeePayoutPortAdapter` : implémente `payments.fee.CancellationFeePayoutPort` (lignes à verser, transitions du versement).
- `CancellationFeePreviewController`, `CancellationFeePreviewService`, `CancellationFeeQuotePort` : aperçu montré à l'expéditeur avant d'annuler.
- `CancellationFeeAdminController`, `CancellationFeeAdminService` : renoncer à une retenue due, relancer un versement en attente.
- `SenderRefundInFlightPort` : la renonciation demande à `payments` si un remboursement pawaPay de l'expéditeur est déjà vivant.
- DTO et événements : `cancellation/dto/CancellationFeePreviewResponse`, `AdminCancellationFeeActionRequest`, `AdminCancellationFeeResponse` ; `cancellation/events/SenderCancellationFeeRetainedEvent`, `CancellationFeePaidOutEvent`, `CancellationFeePayoutRetryRequestedEvent`.

Paquet `payments/fee/` (les montants et l'argent) :
- `CancellationFeeCalculator`, `CancellationFeeBreakdown` : calcul pur (brut, frais perdus, commission, net voyageur, rendu à l'expéditeur).
- `CancellationFeeSettlement` : rassemble les entrées du calcul et règle ou clôt la retenue pendant le remboursement.
- `CancellationFeeRatePort`, `CancellationFeePayoutPort`, `PayableCancellationFee`, `CancellationFeeRetainedEvent`.
- `CancellationFeePayoutService`, `CancellationFeePayoutListener`, `CancellationFeePayoutOutcomeListener`, `CancellationFeePayoutScheduler` : versement du net au voyageur.
- `CancellationFeeQuoteAdapter`, `SenderRefundInFlightAdapter` : réponses de `payments` aux ports de `cancellation`.
- `payments/mobilemoney/MobileMoneyPayoutRefusedException`.
- `notifications/CancellationFeeNotificationListener` : notification à l'expéditeur (retenue) et au voyageur (dédommagement versé).
- Fiches assistant `assistant/kb/{fr,en}/frais-annulation.md`.

## Fichiers modifiés

- `payments/RefundProcessor` : remboursement partiel (payé moins la retenue) sur les deux rails, repli intégral si le calcul échoue, propagation des échecs de la base.
- `payments/mobilemoney/MobileMoneyPayoutInitiator`, `payments/pawapay/*` : payout pawaPay rattaché à la retenue (`purpose CANCELLATION_FEE`), plafond d'essais.
- `payments/PaymentRepository` (`recordRefundedAmount`, `findBidIdLinkedToThread`), `payments/PaymentService` (webhook `charge.refunded` partiel par UPDATE ciblé).
- `payments/reconciliation/StripeReconciler` : compare au montant remboursé enregistré.
- `payments/integrity/MoneyInvariants` : INV-07 ignore une retenue réglée au montant exact, nouveaux invariants INV-19 et INV-20.
- `payments/wallet/fees/PawapayFeeTable` : frais d'un remboursement partiel.
- `admin/AdminPaymentController` : « Rembourser » carte clôt une retenue due, relance de remboursement mobile money.
- `cancellation/CancellationService` (`cancelAfterHandover`), `cancellation/SenderNoShowBidCancelListener` : appellent l'enregistrement.
- `matching/TripRescheduleRules` : `decisionOpen(bid, statut, trajet, instant)` pour juger un colis déjà annulé sur son statut d'avant.
- `notifications/NotificationCategory`, `NotificationDeeplink`, `NotificationPrefsService`, `NotificationTexts`, `i18n/messages_{fr,en}.properties` : deux types de notification.
- `application.yml`, `.github/workflows/deploy-{prod,staging}.yml` : `YADONY_CANCELLATION_FEE_ENABLED` et `YADONY_CANCELLATION_FEE_EFFECTIVE_FROM`.
- Fiches assistant `annuler-envoi`, `no-show`, `remboursement` (fr et en).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. L'expéditeur annule (`PUT /api/v1/bids/{bidId}/cancel` → `BidService.cancelBid`, ou `POST /api/v1/bids/{bidId}/cancel-after-handover` → `CancellationService.cancelAfterHandover`), ou son absence est confirmée (lui, l'équipe ou l'échéance de contestation → `SenderNoShowBidCancelListener`).
2. Dans LA MÊME transaction, `SenderCancellationFeeRecorder.record` demande à `CancellationFeeEligibility.assess` si le colis est couvert et à quel taux. Couvert et taux > 0 : ligne `DUE` (montants à zéro, devise du colis), audit `CANCELLATION_FEE_DUE`.
3. Après le commit, l'écouteur de remboursement habituel (asynchrone, `AFTER_COMMIT`) appelle `RefundProcessor.processRefund`. Après le claim `markRefundedIfEscrow` du paiement, `CancellationFeeSettlement` lit le taux dû SOUS LE VERROU de la ligne, calcule la ventilation, et le remboursement porte sur le payé moins le brut. Puis `refunded_amount`, ligne `RETAINED` (audit `CANCELLATION_FEE_RETAINED`), événements `CancellationFeeRetainedEvent` (versement) et `SenderCancellationFeeRetainedEvent` (notification à l'expéditeur).
4. Après ce commit, `CancellationFeePayoutListener` tente le versement du net au voyageur ; le planificateur horaire reprend toute ligne en attente.
5. Le versement abouti passe la ligne `PAID_OUT` (audit `CANCELLATION_FEE_PAID_OUT`) et prévient le voyageur.

### Activation, dans cet ordre

1. App qui affiche l'aperçu publiée et CGU (yadony-web) en ligne avec le barème.
2. En mode TEST de Stripe : vérifier qu'un Transfer avec `source_transaction` passe sur une charge DÉJÀ remboursée en partie (70 sur 100). Si Stripe refuse, les lignes restent `PAYOUT_PENDING` avec une alerte, rien n'est perdu, mais aucun voyageur carte n'est payé.
3. Poser `YADONY_CANCELLATION_FEE_EFFECTIVE_FROM` au format EXACT (instant ISO-8601 avec date, heure et fuseau : `2026-12-15T00:00:00Z` ou `2026-12-15T00:00:00+01:00`), d'abord en staging. Chiffres seuls (`20261215`), date sans heure, heure sans fuseau : le back REFUSE DE DÉMARRER, même interrupteur éteint.
4. Puis `YADONY_CANCELLATION_FEE_ENABLED=true` (variables du dépôt GitHub, transmises par les workflows de déploiement). Le démarrage écrit en WARN « Frais d'annulation ALLUMÉS » avec la date.
- Allumé sans date d'effet : la fonction reste éteinte, avec un WARN au démarrage.
- Allumé, une date antérieure au `2026-11-01T00:00:00Z` refuse le démarrage. Ce plancher ne protège PAS d'une faute de frappe entre le 2026-11-01 et aujourd'hui (qui couvrirait des colis existants) : relire la date à l'activation.
- `allow-past-effective-from` est réservé au profil de test `src/test/resources/application-cancellation-fee.yml` ; un test vérifie qu'il n'apparaît ni dans `src/main`, ni dans les workflows, ni dans les fichiers `docker-compose` et `.env` du dépôt, ni dans les profils de test partagés.

### Éteindre

L'interrupteur n'arrête que les NOUVELLES retenues. Une ligne `DUE` est réglée au remboursement suivant, une ligne `RETAINED` ou `PAYOUT_PENDING` est versée : le règlement et le versement ne lisent jamais l'interrupteur, l'argent promis au voyageur doit arriver. Pas de retour à un jar antérieur à la fonction après un premier versement mobile money d'un dédommagement : l'ancien code ne connaît pas la valeur `CANCELLATION_FEE` de `PawapayOperationPurpose` et ne peut plus lire ces lignes de `pawapay_operations`.

### Points d'entrée API

- `GET /api/v1/cancellations/bids/{bidId}/fee-preview` : aperçu, utilisateur authentifié, expéditeur du colis seul (403 `forbidden` sinon, 404 `bid-not-found`). Lecture seule.
- `POST /api/v1/admin/cancellation-fees/bids/{bidId}/waive` : renoncer à une retenue encore `DUE`. `ROLE_ADMIN` + permission `PAYMENT_REFUND`. Corps `{"reason": "..."}` (10 à 500 caractères, repris dans l'audit). 404 sans retenue, 409 `cancellation-fee-not-due`, 409 `sender-refund-in-flight` si un remboursement pawaPay de l'expéditeur est déjà vivant ou abouti.
- `GET /api/v1/admin/cancellation-fees/bids/{bidId}` : lecture de la retenue du colis (`ROLE_ADMIN` + `PAYMENT_VIEW`, SUPPORT compris), avec `waivable` (DUE) et `payoutRetryable` (PAYOUT_PENDING) calculés côté serveur ; 404 `cancellation-fee-not-found`. Le motif de `NOT_APPLICABLE` n'est que dans l'audit, il n'est pas exposé.
- `POST /api/v1/admin/cancellation-fees/{feeId}/retry-payout` : relancer un versement `PAYOUT_PENDING`. `ROLE_ADMIN` + permission `PAYMENT_RELEASE`. Même corps. La ligne est réarmée (`payout_rearmed_at`), le versement est tenté après le commit, la réponse dit l'état à l'issue. 409 `cancellation-fee-not-pending`.

### Contrat de l'aperçu pour l'app

`CancellationFeePreviewResponse(rate, fee, currency, refundAmount, nextTierAt)` :
- `rate` : taux appliqué (0.20 = 20 %), 0 quand rien ne s'applique ; `fee` : brut retenu (ce que perd l'expéditeur, pas la part du voyageur) ; `refundAmount` : payé moins `fee` ; montants à l'échelle de la devise (2 décimales en euro, aucune en franc CFA), mais un zéro peut arriver sans décimale (`0`).
- Aucun frais (taux 0, frais 0, tout le payé rendu, `nextTierAt` absent) : interrupteur éteint, colis antérieur à la date d'effet, pas encore accepté, report du trajet en attente de la réponse de l'expéditeur, paiement qui ne peut pas porter de retenue.
- Espèces ou rien de payé en ligne : `rate`, `fee` ET `refundAmount` valent 0, devise du colis.
- `nextTierAt` : dernier instant (inclus) du taux affiché ; il est OMIS du JSON quand il est nul (palier le plus cher, ou aucun frais). Dans la fenêtre gratuite, le taux est 0 et la hausse est annoncée. L'app doit relire l'aperçu après `nextTierAt` (le taux a changé).
- L'aperçu ne rejoue pas `CancellationGuard` : un colis remis après le départ peut afficher 30 % alors que l'annulation sera refusée (`cancel-locked`).

### Configuration publique et montant remboursé

**`GET /api/v1/config/cancellation-fee`** (public, sans authentification, comme tout `/config/**` ; `CancellationFeeConfigController` dans `cancellation/fee`) : l'app choisit ses textes selon `active`, et peut montrer le barème avant paiement.

```json
{ "active": true, "effectiveFrom": "2026-12-15T00:00:00Z", "capEur": 30,
  "tiers": [ {"minDaysBefore": 5, "rate": 0}, {"minDaysBefore": 3, "rate": 0.10},
             {"minDaysBefore": 1, "rate": 0.20}, {"minDaysBefore": 0, "rate": 0.30} ] }
```

- `active` : interrupteur allumé ET date d'effet posée, c'est `CancellationFeeEligibility.active()`, la même décision que la retenue et l'aperçu.
- Éteint : `{"active": false, "effectiveFrom": null, ...}` ; le champ `effectiveFrom` reste présent (nul), `capEur` et `tiers` restent (statiques).
- Sémantique des paliers : un palier s'applique quand il reste au moins `minDaysBefore` jours avant le départ, sauf le premier (le plus lointain, fenêtre gratuite) qui exige strictement plus. Taux de 0 à 1.
- Aucun montant d'utilisateur n'est exposé.

**`GET /api/v1/payments/bid/{bidId}`** (expéditeur ou voyageur du colis, contrôle de propriété inchangé) : champ additif `refundedAmount`, le montant réellement remboursé (`payments.refunded_amount`), en unités de la devise comme `amount` (70.00 = 70 EUR, jamais en centimes). Absent du JSON tant que rien n'est remboursé (la sérialisation du projet omet les nuls) ; après une retenue il est inférieur à `amount`. Les champs existants ne changent ni de sens ni d'ordre : une ancienne app l'ignore et continue d'afficher le total payé.

### Entités JPA impliquées

- `CancellationFeeEntity` → `cancellation_fees` (hérite de `BaseEntity`, soft delete) : `bid_id` (unique parmi les lignes non supprimées), `sender_id`, `traveler_id`, `rate`, `reason` (`SENDER_CANCEL`, `SENDER_CANCEL_AFTER_HANDOVER`, `SENDER_NO_SHOW`), `status`, `gross_fee`, `refund_fees`, `commission`, `traveler_net`, `currency` (colonnes NOT NULL : zéro et devise du colis tant que la ligne est `DUE`), `payout_ref` (Transfer Stripe ou opération pawaPay), `payout_pending_reason`, `payout_rearmed_at`.
- `PawapayOperationEntity` → `pawapay_operations.cancellation_fee_id` : un payout de dédommagement n'est JAMAIS rattaché à `payment_id` (il heurterait `uq_pawapay_ops_live_per_payment`, serait pris pour le versement du colis et lèverait INV-12).
- `PaymentEntity.refundedAmount` : montant réellement rendu, écrit par UPDATE ciblé (`recordRefundedAmount`), jamais par un setter sur une entité lue avant le claim.

### Cycle de vie d'une ligne

`DUE` → `RETAINED` → (`PAYOUT_PENDING`) → `PAID_OUT`, ou `DUE` → `NOT_APPLICABLE`. Une ligne close ne redevient jamais due ; `PAID_OUT` n'en sort plus.
- `DUE` : écrite par l'enregistrement, montants à zéro.
- `RETAINED` : remboursement partiel parti (carte) ou soumis (mobile money), ventilation figée.
- `PAYOUT_PENDING` : un essai de versement n'a pas abouti (garde non remplie, refus, erreur) ; dernier motif dans `payout_pending_reason` (un nouveau motif est audité, le même n'écrit rien).
- `PAID_OUT` : versé (ou net nul : `PAID_OUT` sans versement).
- `NOT_APPLICABLE`, motif dans l'audit `CANCELLATION_FEE_NOT_APPLICABLE` : `PAYMENT_NOT_CAPTURED` (autorisation carte annulée en entier, ou paiement mobile money jamais encaissé), `PAYMENT_INTENT_CANCELED`, `LEGACY_DESTINATION_CHARGE`, `PAYMENT_ALREADY_PARTIALLY_REFUNDED`, `FEE_COMPUTATION_FAILED`, `DEPOSIT_AMOUNT_MISMATCH` (dépôt pawaPay d'un autre montant que le paiement), `REFUND_ALREADY_IN_FLIGHT` (remboursement pawaPay déjà vivant d'un autre montant), `ADMIN_FULL_REFUND` (« Rembourser » carte de l'admin), `ADMIN_WAIVED` (renonciation de l'admin).

### Logique métier critique

**Décision unique.** `CancellationFeeEligibility.assess(bid, statutAuMomentDeLaFaute, instant)` décide pour l'enregistrement ET pour l'aperçu : interrupteur allumé avec une date d'effet, colis créé à cette date ou après, payé par carte (séquestre) ou mobile money, trajet connu, aucune réponse à un report du trajet encore ouverte (`TripRescheduleRules.decisionOpen`). L'enregistrement lit un colis déjà annulé : il passe le statut d'avant (accepté pour l'annulation et l'absence, remis pour l'annulation après remise). L'aperçu passe le statut courant. Le motif de la faute (`senderCancellationReason`) est partagé de la même façon. Jamais de frais pour le voyageur, l'annulation par l'équipe, l'annulation avant paiement, le retrait par le bouton du report, la suppression de compte : ces chemins n'appellent pas l'enregistrement.

**Barème.** Décompté sur `departureAt`, à défaut la date de départ à 00:00 UTC (comme `CancellationGuard.hasDeparted`), jamais la date limite de remise. Temps restant comparé EXACTEMENT au seuil, sans arrondi : strictement plus de 5 jours = 0 % ; de 5 jours inclus à 3 jours inclus = 10 % ; de moins de 3 jours à 1 jour inclus = 20 % ; moins d'un jour ou après le départ = 30 %. Barème et plafond en configuration (`yadony.cancellation-fee.tiers`, `cap-eur`), validés au démarrage. Une absence est confirmée en général après le départ : son taux est souvent 30 %.

**Base et formule** (`CancellationFeeCalculator`, `CancellationFeeSettlement`) :
1. brut = min(montant PAYÉ × taux, plafond), à l'échelle de la devise. Le plafond de 30 € est converti dans la devise du paiement avec le taux du wallet, arrondi VERS LE BAS (jamais plus que 30 €).
2. moins les frais du prestataire perdus : frais Stripe réels de la charge (repli configuré s'ils sont illisibles) ; sur le mobile money, les frais pawaPay du dépôt ET du remboursement partiel (`PawapayFeeTable.partialRefundFee`, décision du propriétaire : tous les frais d'origine sont perdus, comme sur la carte). Jamais négatif.
3. moins la commission Yadony au taux EFFECTIF de ce paiement (commission ÷ (payé − commission), plafonné à 1 ; repli sur `CommissionRateResolver` si le paiement n'en garde pas trace), appliquée sur le reste.
4. le reste va au voyageur ; il peut valoir 0 sur un petit colis. L'expéditeur récupère payé − brut.
Exemple : 100 € à J, commission 5 % : brut 30 €, frais Stripe 1,75 €, commission 1,41 €, voyageur 26,84 €, expéditeur 70 €.

**Ordre des verrous.** Annulation : verrou du colis (les trois appelants le tiennent), puis insertion de la ligne. Remboursement : paiement (claim `markRefundedIfEscrow`) puis ligne de la retenue (`findByBidIdForUpdate`, relue par `refresh` contre un état périmé en cache). Renonciation de l'admin : la ligne seule, plus des lectures simples côté paiements. Versement : la ligne seule, pour chaque transition. Aucun cycle.

**Pourquoi l'enregistrement est synchrone et `MANDATORY`.** Le remboursement part après le commit de l'annulation, sur un fil asynchrone, et doit trouver la ligne pour ne rendre que le net. Un `@TransactionalEventListener` l'écrirait après ce commit, en course avec le remboursement ; une transaction à part la ferait survivre à une annulation défaite. Écrite dans la transaction qui annule, elle existe dès que le remboursement peut partir et disparaît si l'annulation échoue. Hors transaction, `record` lève `IllegalTransactionStateException`.

**Repli « remboursement intégral » et sa limite.** Une erreur du CALCUL (taux de change absent, barème invalide, arithmétique) ne bloque jamais l'expéditeur : remboursement intégral comme avant, alerte `FEE_COMPUTE_FAILED_<paymentId>`, ligne close `FEE_COMPUTATION_FAILED`, voyageur non dédommagé. Un échec de la BASE pendant le règlement (`DataAccessException`, `TransactionException`, `PersistenceException` non traduite, ou transaction du claim déjà condamnée) n'est PAS replié : l'exception remonte avant tout appel au prestataire, la transaction `REQUIRES_NEW` défait le claim, le paiement reste `ESCROW` et la retenue `DUE`. Contrairement à un échec Stripe (alerte `STRIPE_REFUND_FAILED`), `RefundProcessor` ne lève AUCUNE alerte et rien ne relance ce remboursement : les signaux sont l'erreur journalisée par l'écouteur asynchrone (Sentry), puis INV-04 au bout d'une heure (séquestre sur un colis annulé) ; le geste est alors « Rembourser » de l'admin. Replier ici rembourserait en entier chez le prestataire puis raterait le commit : argent sorti, paiement resté en séquestre. Cela vaut pour tout remboursement, fonction éteinte comprise (la lecture de la ligne a lieu à chaque remboursement d'un paiement capturé).

**Clés d'idempotence et unicité.**
- Une ligne par colis : `ux_cancellation_fees_bid` ; l'enregistrement est sans effet si la ligne existe.
- Remboursement carte : clé `refund-<paymentId>`, la même en intégral et en partiel (un paiement n'a qu'un remboursement par ce chemin). « Rembourser » de l'admin : `admin-refund-<paymentId>`.
- Transfer au voyageur : clé `cancel-fee-transfer-<feeId>` pour tous les essais, groupe `cancel_fee_<feeId>`, métadonnée `source=cancellation_fee`. Stripe ne garde la clé que 24 h : chaque essai cherche d'abord le Transfer du groupe et le reprend.
- Payout pawaPay : `clientReferenceId` `fee-<feeId>`, au plus une opération vivante ou aboutie par retenue (`uq_pawapay_ops_live_per_cancellation_fee`, opérations `FAILED` et `SUBMIT_REJECTED` exclues).
- Règlement : sous le claim du paiement (un seul remboursement, donc un seul règlement) et sous le verrou de la ligne (`DUE` → `RETAINED` une seule fois, donc un seul audit, un seul événement, une seule notification).

**Spécificités du mobile money.** Remboursement pawaPay PARTIEL (dépôt moins le brut ; pawaPay v2 l'accepte). Un refund déjà vivant est repris tel quel, jamais resoumis ; d'un autre montant, la ligne est close avec l'alerte `FEE_REFUND_MISMATCH_<paymentId>`. Le voyageur n'est payé qu'une fois le remboursement de l'expéditeur ABOUTI et du montant enregistré (10 à 70 minutes après, par le planificateur). Après 3 payouts `FAILED` (comptés depuis `payout_rearmed_at` s'il y en a un ; `SUBMIT_REJECTED` non compté), plus d'essai automatique : alerte `CANCEL_FEE_STOPPED_<feeId>` jusqu'à la relance d'un admin.

**Asymétrie du bouton « Rembourser » de l'admin.** Carte (`POST /admin/payments/{id}/refund`) : remboursement INTÉGRAL par son propre chemin, une retenue encore due est close `ADMIN_FULL_REFUND`. Mobile money : le bouton passe par `RefundProcessor.processRefund`, qui RETIENT une retenue due (remboursement partiel). Pour tout rendre sur le mobile money, renoncer d'abord (`/waive`).

### Events Spring publiés / écoutés

- `BidCancelledByParticipantEvent` (matching) → `SenderCancellationFeeRecorder.onBidCancelledByParticipant`, `@EventListener` synchrone, `MANDATORY`.
- `CancellationFeeRetainedEvent` (payments) publié au règlement → `CancellationFeePayoutListener` après commit : essai de versement.
- `SenderCancellationFeeRetainedEvent` (cancellation) publié au passage `DUE` → `RETAINED` → `CancellationFeeNotificationListener` : notification `CANCELLATION_FEE_RETAINED` à l'expéditeur (montant retenu et remboursé).
- `CancellationFeePaidOutEvent` (cancellation) au passage `PAID_OUT` → notification `CANCELLATION_FEE_PAID_OUT` au voyageur.
- `CancellationFeePayoutRetryRequestedEvent` (cancellation) à la relance de l'admin → `CancellationFeePayoutListener.onPayoutRetryRequested` après commit.
- Issues pawaPay (`PawapayOperationCompletedEvent` / `FailedEvent` d'un payout `CANCELLATION_FEE`) → `CancellationFeePayoutOutcomeListener`.

### Alertes et gestes de l'admin

- `FEE_COMPUTE_FAILED_<paymentId>` : retenue incalculable, expéditeur remboursé en entier, voyageur à dédommager à la main.
- `FEE_REFUND_MISMATCH_<paymentId>` et `MM_PARTIAL_REFUND_<paymentId>` (mobile money) : remboursement repris d'un autre montant ; le reste du dépôt est dû à l'expéditeur, à rendre à la main.
- `CANCEL_FEE_PAYOUT_<feeId>` : versement en attente (une alerte par ligne, quel que soit le nombre d'essais) ; `CANCEL_FEE_STOPPED_<feeId>` : essais mobile money arrêtés. Geste : `retry-payout` (`PAYMENT_RELEASE`).
- `STRIPE_REFUND_FAILED`, `PAWAPAY_REFUND_REJECTED_<paymentId>`, `PAWAPAY_REFUND_FAILED` : inchangées, la retenue reste due. Geste possible : `waive` (`PAYMENT_REFUND`), puis le remboursement habituel rend tout.
- `MONEY_INVARIANT_INV-19` (sévérité HAUTE, alerte WARN) : retenue restée `DUE` alors que le paiement est remboursé depuis plus de 24 h ; dédommagement pas versé 3 jours après la retenue.
- `MONEY_INVARIANT_INV-20` (sévérité CRITIQUE, alerte CRITICAL) : remboursé + brut ≠ payé sur une ligne réglée (paiement d'un fil de négociation compris), par exemple un expéditeur remboursé en entier après la retenue alors que le voyageur est payé. Règle à part : les alertes sont dédupliquées par règle, un vieux retard de versement ne masque pas un écart d'argent. INV-07 ne signale plus un remboursement Stripe partiel égal au payé moins le brut d'une ligne réglée.
- Rapprochement Stripe quotidien : sur un paiement remboursé avec un montant enregistré, signale un remboursement absent ou inférieur, ou supérieur sans atteindre le total ; un total égal au paiement est cohérent (le webhook mettra la base à jour, INV-20 surveille ensuite la retenue).

### Cas à régler à la main

- Remboursement parti chez le prestataire, puis échec du commit (écriture de `refunded_amount` ou de la ligne, après l'appel) : paiement revenu `ESCROW`, retenue `DUE`, expéditeur déjà remboursé du partiel. Un nouvel essai peut reprendre la même réponse Stripe (même clé, gardée 24 h) et régler ; sinon Stripe refuse (alerte `STRIPE_REFUND_FAILED`) et INV-04 signale le séquestre sur un colis annulé : vérifier chez Stripe ce qui a été rendu avant tout geste.
- Alerte `FEE_COMPUTE_FAILED_` : verser le dédommagement au voyageur à la main si la retenue était due.
- Reste d'un dépôt pawaPay (`MM_PARTIAL_REFUND_`, `FEE_REFUND_MISMATCH_`) : à rendre depuis le tableau de bord pawaPay, sans audit côté back.
- Remboursement partiel pawaPay ÉCHOUÉ de façon asynchrone sur une ligne `RETAINED` : pas de sortie automatique vers un remboursement intégral ; la relance de remboursement de l'admin resoumet le même partiel.
- Transfer refusé par Stripe (par exemple `source_transaction` sur une charge partiellement remboursée) : ligne `PAYOUT_PENDING`, alerte, relance après correction.
- Remboursement intégral fait depuis le tableau de bord Stripe après une retenue : INV-20 (remboursé + brut ≠ payé) une fois le webhook arrivé.
- Colis négocié dont le paiement du fil précède la date d'effet alors que le colis est créé après : il peut porter des frais (la date d'effet lit `bids.created_at`).

### Pièges et points d'attention

- Ne jamais lire la retenue avant le claim du paiement : seul le claim garantit un règlement unique.
- Ne jamais poser `payment.setStatus(...)` ni un setter sur un paiement lu avant le claim : le flush écraserait `REFUNDED` par un statut périmé. `refunded_amount` passe par `recordRefundedAmount`.
- Le paiement d'un fil de négociation n'a pas de `bid_id` : le colis se retrouve par `bids.linked_negotiation_thread_id` (`findBidIdLinkedToThread`, et la même jointure dans INV-07, INV-19 et INV-20).
- Colonnes argent NOT NULL : une ligne `DUE` porte des zéros et la devise du colis jusqu'au règlement.
- Les profils de test partagés (`application-test.yml`, `application-e2e.yml`) laissent la fonction ÉTEINTE, comme en production. Les tests de la fonction l'allument avec `@ActiveProfiles({"test", "cancellation-fee"})` ou `({"e2e", "cancellation-fee"})`, jamais par `@TestPropertySource` (un seul contexte Spring de plus par profil de base).
- Migrations V393 à V395 : renumérotées une fois avant toute application (V390 de `main` pris par #774). Prod refuse une migration plus basse que la dernière appliquée : une branche voisine qui tient V391 ou V392 doit se renuméroter si elle fusionne après.
- Après ce renommage, un `target/` local garde les anciens `V390__cancellation_fees.sql`, `V391__…`, `V392__…` dans `target/classes/db/migration` : Flyway refuse alors de démarrer (« more than one migration with version 390 »). `./mvnw clean` une fois ; la CI part d'un dépôt propre.

## Ce qui n'existe pas encore

- Aucun bouton dans dony-admin : renoncer à une retenue (`/waive`) et relancer un versement (`/retry-payout`) ne passent que par l'API.
- Les vues finance de l'admin ne montrent pas les opérations `CANCELLATION_FEE` (payouts pawaPay des dédommagements, Transfers Stripe `source=cancellation_fee`).
- DAC7 : le dédommagement versé au voyageur n'est pas déclaré dans les rapports. Décision du propriétaire et du juridique.
- Aucun endpoint n'expose le barème avant le paiement (checkout, CGU) : l'app doit le porter elle-même tant que ce n'est pas fait.
- PR de l'app (affichage de l'aperçu) et PR des CGU (yadony-web) : à faire, dans les autres dépôts, après la PR back (règle des PR jumelles).
- Aucun geste admin pour rendre le reste d'un dépôt pawaPay après un remboursement partiel repris (`MM_PARTIAL_REFUND_`, `FEE_REFUND_MISMATCH_`) : à faire à la main depuis le tableau de bord pawaPay, sans audit côté back.
- Aucun nouvel essai automatique après un échec de la base pendant le règlement : erreur de l'écouteur asynchrone (Sentry), INV-04 au bout d'une heure, puis « Rembourser » de l'admin.
- Vérification en mode TEST de Stripe d'un Transfer avec `source_transaction` sur une charge déjà remboursée en partie : toujours à faire avant l'activation.
- Migrations : `telephone-declare` (V391) et toute autre branche ouverte doivent se renuméroter au-dessus de V395 si elles fusionnent après celle-ci.
- `origin/main` a encore avancé depuis la fusion de cette branche : une nouvelle fusion de main est nécessaire avant d'ouvrir la PR.

## Critères d'acceptation couverts

- [x] L'expéditeur qui annule un colis accepté, payé par carte capturée ou mobile money, paie selon le barème : `SenderCancellationFeeRecorder`, `RefundProcessor`, `SenderCancellationFeeRecordingIT`, `RefundProcessorCancellationFeeIT`, `CancellationFeeMobileMoneyIT`.
- [x] Absence confirmée de l'expéditeur (lui, l'équipe, l'échéance) et annulation après remise avant le départ : mêmes frais.
- [x] Colis seulement `PAYMENT_ESCROWED`, espèces, voyageur fautif, annulation par l'équipe, retrait après un report de trajet (bouton du report OU annulation ordinaire tant que la réponse est ouverte) : aucun frais.
- [x] Barème sur le départ réel, bornes exactes, plafond 30 € converti, jamais négatif : `CancellationFeeTiersTest`, `CancellationFeeCalculatorTest`.
- [x] Net versé au voyageur sans passer par le wallet, Transfer Stripe ou payout pawaPay, jamais deux fois, jamais rendu à l'expéditeur : `CancellationFeePayoutIT`, `CancellationFeeMobileMoneyIT`.
- [x] L'expéditeur voit les frais avant de confirmer (aperçu = règlement) : `CancellationFeePreviewServiceTest`, tests « aperçu puis vrai règlement ».
- [x] Notifications FR/EN à l'expéditeur (retenue) et au voyageur (dédommagement) ; fiches assistant fr et en.
- [x] Audit pour chaque retenue, clôture, versement et geste admin.
- [x] Interrupteur éteint = comportement d'avant la fonction : `CancellationFeeSwitchedOffEndToEndIT` et toute la suite, qui tourne éteinte.

## Tests

- Passe large (2026-11-21, `./mvnw test -Djacoco.skip=true` sur `cancellation/**`, `payments/**`, `notifications/**`, `assistant/**`, `common/i18n/**`, les tests du contrôleur de paiements admin et les IT voisins des annulations) : 6 632 tests, 0 échec ; 5 classes en erreur d'infrastructure (`initdb failed` et « Connection refused » du PostgreSQL embarqué), vertes à la relance (53 tests).
- Suite complète (`./mvnw test`, 2026-11-21) : 17 494 tests, 3 échecs, 125 erreurs. 123 classes en `initdb failed` (PostgreSQL embarqué, postgres orphelins sur la machine) : relancées, toutes vertes sauf deux tests de migration de fuseaux sans rapport avec la fonction, `V299V300TripCountryAndTimezoneMigrationTest` (2 échecs, « Africa/Abidjan » attendu) et `V344HandoverDeadlineLocalToUtcMigrationTest` (« invalid value for parameter TimeZone: Asia/Tokyo ») : le cache local du binaire PostgreSQL embarqué n'a plus ses fichiers de fuseaux (`share/postgresql/timezone` presque vide), problème de la machine, aucun fichier de ces tests ni de leurs migrations n'est touché par la branche.
- Couverture JaCoCo de la suite complète : 95,6 % des lignes au total ; `cancellation/fee` 99,7 %, `payments/fee` 98,4 %, `payments/integrity` 97,9 %, `RefundProcessor` 96,6 %.
- Tests de la fonction : `CancellationFeeTiersTest`, `CancellationFeeCalculatorTest`, `CancellationFeePropertiesTest`, `CancellationFeeEligibilityTest`, `SenderCancellationFeeRecorderTest`, `SenderCancellationFeeRecordingIT`, `CancellationFeePreviewServiceTest`, `CancellationFeePreviewControllerIntegrationTest`, `CancellationFeeRatePortAdapterTest`, `CancellationFeePayoutPortAdapterTest`, `CancellationFeeAdminServiceTest`, `CancellationFeeAdminControllerIT`, `CancellationFeeRepositoryTest`, `CancellationFeePostgresMigrationIT`, `CancellationFeeSettlementTest`, `CancellationFeeQuoteAdapterTest`, `CancellationFeePayoutServiceTest`, `CancellationFeePayoutListenerTest`, `CancellationFeePayoutOutcomeListenerTest`, `CancellationFeePayoutSchedulerTest`, `CancellationFeePayoutIT`, `CancellationFeeMobileMoneyTest`, `CancellationFeeMobileMoneyIT`, `SenderRefundInFlightAdapterTest`, `RefundProcessorCancellationFeeTest`, `RefundProcessorCancellationFeeIT`, `CancellationFeeSwitchedOffEndToEndIT`, `CancellationFeeNotificationListenerTest`, `MoneyIntegrityMonitorIT` (INV-07, INV-19, INV-20), `StripeReconcilerTest`, `PawapayFeeTableTest`, `NotificationTextsTest`.

## Décisions techniques

- **Versement direct** (Transfer Stripe Connect, payout pawaPay) plutôt qu'un crédit wallet : un crédit remboursable n'existe pas (le remboursement d'une recharge repart vers la carte qui l'a payée, ici celle de l'expéditeur).
- **Base = montant payé** par l'expéditeur, frais de service inclus (choix par défaut de la spec).
- **Ligne écrite au moment de la faute, réglée au remboursement** : la décision vit dans `cancellation/`, les montants dans `payments/`, reliés par des ports (aucune injection croisée de service).
- **Nouveaux colis seulement** (`effective-from`, décision du propriétaire) : un colis payé sous des CGU sans frais n'en porte jamais.
- **Frais pawaPay du dépôt ET du remboursement déduits** (décision du propriétaire) : tous les frais d'origine sont perdus, comme sur la carte.
- **Taux de l'absence calculé à la confirmation** : en pratique après le départ, donc souvent 30 %.
- **Pas de frais tant que la réponse à un report du trajet est ouverte**, quel que soit le chemin d'annulation (spec : report puis retrait, personne ne paie). Question laissée au propriétaire : un report qui AVANCE le départ peut faire monter le taux de l'expéditeur.
- **Échecs de la base propagés** plutôt que repliés : un remboursement intégral suivi d'un commit raté ferait sortir l'argent sans trace en base.
- **Bornes exactes** plutôt qu'en heures entières : « plus de 5 jours » à la seconde près, barème du propriétaire.
