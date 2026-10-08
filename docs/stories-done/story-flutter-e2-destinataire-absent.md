# FLUTTER-E2 — Destinataire absent : procédure encadrée et partage admin (Backend)

**Date :** 2026-10-08
**Statut :** ✅ Complète (hypothèses produit à valider, voir la fin)

## Résumé
Le signalement « destinataire absent » suit désormais une procédure : attente minimale après
l'arrivée déclarée, preuve de contact, garde du colis 7 jours, puis colis « non réclamé » payé
au voyageur. L'admin peut aussi résoudre un litige par un partage chiffré du séquestre.

## Comment ça fonctionne

### A. Procédure
1. `POST /announcements/{id}/mark-arrived` pose `bids.arrived_at`.
2. `GET /cancellations/bids/{bidId}/delivery-noshow` (expéditeur ou voyageur) renvoie l'état :
   `reportAvailableAt`, `waitElapsed`, `contactProof`, `canReport`, `holdUntil`,
   `retryAppointmentAt`, `unclaimedAt`, `canSetRetryAppointment`.
3. `POST /cancellations/bids/{bidId}/report-delivery-noshow` (voyageur), corps
   `{"contactConfirmed": true}`. Refus RFC 7807 :
   - 409 `delivery-noshow-arrival-not-declared` : colis pas ARRIVED ;
   - 422 `delivery-noshow-wait-not-elapsed` (`availableAt`, `minWaitMinutes`) ;
   - 422 `delivery-noshow-contact-required` : aucun appel in-app lancé par le voyageur sur le
     colis (`calls`) ni message du voyageur (`conversations.traveler_last_message_at`, posé par
     `/internal/messaging/notify`) depuis l'arrivée ;
   - 422 `delivery-noshow-confirmation-required` : case non cochée.
   Le signalement pose `hold_until = now + holdDays`, `contact_proof`, `contact_confirmed_at`.
4. Contestation inchangée (24 h, litige `RECIPIENT_NO_SHOW_CONTESTED`). Sans contestation, la
   déclaration passe CONFIRMED **sans** litige (la garde continue). Les signalements antérieurs
   (sans garde) gardent l'ancien chemin (litige `RECIPIENT_NO_SHOW`).
5. `POST /cancellations/bids/{bidId}/delivery-noshow/retry-appointment` (expéditeur),
   `{appointmentAt, note}` : rendez-vous à venir et avant `hold_until` (422
   `retry-appointment-out-of-hold`), garde ouverte (409 `retry-appointment-closed`).
   Changement de destinataire : flux existant `PUT /bids/{id}/recipient`.
6. Livraison pendant la garde → `DeliveryNoShowHoldClosingListener` passe la déclaration RESOLVED.
7. `UnclaimedParcelScheduler` (`0 30 * * * *` UTC) : déclaration CONFIRMED, garde échue, colis
   encore HANDED_OVER/IN_TRANSIT/ARRIVED, **aucun litige sur le bid** → claim atomique
   `cancellations.unclaimed_at`, audit `PARCEL_UNCLAIMED`, `ParcelUnclaimedEvent`.
8. `DeliveryEventListener#handleParcelUnclaimed` libère le net par le même chemin que la
   livraison (mêmes gardes, claim ESCROW → RELEASED, clé `transfer-<paymentId>`, payout pawaPay),
   audit `ESCROW_RELEASED_UNCLAIMED`. Espèces : rien à verser.

### B. Partage admin
`POST /admin/disputes/{id}/resolve` accepte `senderRefundAmount` et `travelerPayoutAmount`
(unité principale ; DISPUTE_RESOLVE + PAYMENT_RELEASE + PAYMENT_REFUND).
- `PaymentSplitService.plan` : validations sans effet. Net disponible =
  `amount − commission − refunded_amount` (le garde-fou de remboursement partiel réduit le net au
  lieu de bloquer). Refus 422 : espèces, **mobile money** (`split-mobile-money-unsupported`),
  carte legacy, hors séquestre ; 409 chargeback, partage existant, voyageur gelé.
- `claim` (même transaction que la décision) : `claimForSplit` ESCROW → RELEASED (ou REFUNDED si
  part voyageur nulle) avec `refunded_amount` final, ligne `payment_splits` CLAIMED, audit
  `PAYMENT_SPLIT_DECIDED`. Commité avant tout appel Stripe.
- `execute` : PI capturé → Refund partiel puis Transfer partiel ; PI autorisé → capture partielle
  (`amount_to_capture = amount − part expéditeur`) puis Transfer. Chaque étape commitée
  (CLAIMED → SENDER_REFUNDED → COMPLETED). Échec : `last_error`, `attempts`, alerte
  `PAYMENT_SPLIT_STALLED_<paymentId>`, reprise `POST /admin/disputes/{id}/split/retry`. Reprise
  idempotente : recherche du Refund (`metadata.split_id`) et du Transfer (`transfer_group`) déjà
  créés avant toute création, clés `split-refund|capture|transfer-<splitId>`.
- `GET /admin/disputes/{id}/split-options` : montants pour le formulaire.

## Migration
`V301__destinataire_absent_procedure_et_partage.sql` : `bids.arrived_at`,
`conversations.traveler_last_message_at`, colonnes de garde sur `cancellations`, montants sur
`disputes`, table `payment_splits` (index unique par paiement).

## Pièges
- `cancellations.unclaimed_at`, `conversations.traveler_last_message_at` : `updatable = false`,
  écrites seulement par leurs UPDATE ciblés.
- `refunded_amount` est posé au claim à sa valeur finale : le webhook `charge.refunded` la trouve
  déjà enregistrée et ne lève pas `REFUND_AFTER_RELEASE`.
- INV-05 et INV-07 (`MoneyInvariants`) excluent les paiements partagés et les colis non réclamés.

## Tests
`DeliveryNoShowProcedureServiceTest`, `UnclaimedParcelSchedulerTest`,
`DeliveryNoShowHoldClosingListenerTest`, `DestinataireAbsentRepositoryTest`,
`PaymentSplitServiceTest`, ajouts dans `CancellationServiceDeliveryNoShowTest`,
`CancellationControllerDeliveryNoShowTest`, `DeliveryNoShowUncontestedSchedulerTest`,
`DeliveryEventListenerTest`, `AdminDisputesControllerTest`, `MessagingNotifyControllerTest`.

## Hypothèses à valider (produit)
- Attente minimale 120 min après l'arrivée déclarée (aucun rendez-vous de livraison n'existe).
- Garde 7 jours, non prolongée par un nouveau rendez-vous.
- Tout litige sur le colis (ouvert ou résolu) bloque le versement automatique.
- Partage : la commission reste acquise ; un reliquat reste sur le solde plateforme.
- Après « non réclamé », le colis reste ARRIVED ; durée de conservation par le voyageur non définie.
