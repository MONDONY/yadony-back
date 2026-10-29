# Sécurité du portefeuille, lot C2b : robustesse (Backend)

**Date:** 2026-10-29
**Status:** ✅ Complète

## Résumé
Dernier lot des correctifs de l'audit du portefeuille du 28/10. Il ferme les incohérences restantes :
- une recharge remboursée hors de l'app restait dépensable ;
- un taux de change périmé pouvait fausser une commission ;
- les appels Stripe pouvaient bloquer un thread plusieurs minutes ;
- les entrées non validées tombaient en erreur 500 ;
- deux remboursements n'étaient pas audités ;
- un verrou était posé avant le contrôle de propriété ;
- un jeton de reprise Stripe finissait dans les journaux.

## Fichiers créés
- `payments/wallet/WalletExternalRefundService.java` : détection d'un remboursement de recharge non émis par l'app, gel, levée.
- `payments/wallet/WalletExternalRefundHoldEntity.java`, `WalletExternalRefundHoldRepository.java`, `WalletExternalRefundHoldStatus.java` : le gel.
- `admin/dto/AdminExternalRefundHoldResponse.java` : vue admin d'un gel.
- `db/migration/V361__wallet_external_refund_holds.sql` : table `wallet_external_refund_holds`.

## Fichiers modifiés
- `payments/wallet/WalletService.java` :
  - le gel externe compte dans `isFrozen` ;
  - nouvelles méthodes `isExternalRefundHeld` et `isRefundRequestPending` ;
  - code 422 `wallet-external-refund-hold`.
- `payments/PaymentStripeWebhookHandler.java` : `charge.refunded` passe aussi au service des remboursements externes.
- `payments/wallet/WalletSelfRefundService.java`, `topupcode/WalletTopupCodeRedemptionService.java` : refus avec le motif du gel.
- `payments/wallet/WalletAdminAdjustmentService.java` : pendant un gel externe, le débit passe et le crédit est refusé. Une demande de remboursement en cours bloque toujours les deux.
- `admin/AdminUserWalletController.java` : endpoints `GET /external-refund-holds` et `POST /external-refund-holds/{id}/release`.
- `payments/cash/WalletCommissionCollector.java` : un taux périmé de la devise du colis bloque toute conversion.
- `config/StripeConfig.java`, `application.yml` : délais Stripe de 10 s (connexion) et 30 s (lecture).
- `payments/wallet/WalletController.java` : devise de l'adresse validée (422 `currency-unsupported`), page négative ramenée à 0.
- `payments/wallet/WalletPawapayRefundIssuer.java` : audit `PAWAPAY_ITEM_REFUNDED`.
- `matching/BidRepository.java` (`findPartiesById`), `payments/cash/CashCommissionService.java`, `payments/amendment/BidAmendmentService.java` : propriété vérifiée avant les verrous.
- `nginx/nginx.conf`, `nginx/nginx.staging.conf` : jeton `?t=` de reprise Stripe Connect masqué dans les journaux.
- Fiche assistant `rembourser-solde` (fr, en).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. **Remboursement Stripe fait hors de l'app** :
   - `charge.refunded` arrive, et le handler appelle `WalletExternalRefundService.recordFromCharge` ;
   - si le PaymentIntent est celui d'une recharge (`TOP_UP`), chaque remboursement est examiné ;
   - un remboursement abouti ou en cours, que l'app n'a pas émis (ni métadonnée `wallet_refund_item_id`, ni item connu), pose un gel OPEN ;
   - un audit et l'alerte `wallet-topup-refunded-externally` suivent.
2. **Pendant le gel** :
   - refusés en 422 `wallet-external-refund-hold` : dépenses, demande de remboursement, code de recharge, crédit admin ;
   - accepté : le débit admin, qui sert à régulariser.
3. **Levée** : l'admin lève le gel depuis la fiche (`WALLET_ADJUST`, note obligatoire). Un UPDATE conditionnel garantit une seule décision.
4. **Taux** : avant toute conversion vers la devise du colis, `isStale(bid)`. Si le taux est périmé, chaque autre portefeuille est écarté (`rate-stale`) et l'admin est alerté.

### Points d'entrée API
- `GET /api/v1/admin/users/{userId}/wallet/external-refund-holds` : `ADMIN` + `PAYMENT_VIEW`.
- `POST /api/v1/admin/users/{userId}/wallet/external-refund-holds/{holdId}/release` (`{ "note": "…" }`) : `ADMIN` + `WALLET_ADJUST`.
  - 400 `wallet-external-refund-hold-note-invalid` ;
  - 404 `wallet-external-refund-hold-not-found` ;
  - 409 `wallet-external-refund-hold-already-released`.
- `GET|POST /api/v1/wallet/{currency}/…` : 422 `currency-unsupported` pour une devise inconnue.

### Entités JPA impliquées
- `WalletExternalRefundHoldEntity` → `wallet_external_refund_holds` :
  - `stripe_refund_id` unique ;
  - CHECK : un gel RELEASED porte toujours son auteur, sa date et sa note ;
  - index partiel sur les gels OPEN.

### Logique métier critique
- **Pas de débit automatique** (décision du propriétaire du 29/10) : la résolution d'un ticket de remboursement manuel débite déjà le solde. L'alerte indique laquelle des deux voies suivre, selon qu'une demande de remboursement est en cours ou non.
- **Liste des remboursements** : les événements récents n'embarquent plus `charge.refunds`, le service lit donc `Refund.list`. Une lecture impossible lève une erreur, et le webhook est rejoué plutôt que de laisser passer un remboursement.
- **Délais Stripe** : avec 2 relances, un appel bloqué rend la main en environ 2 minutes au pire, contre plus de 5 avant.

### Events Spring publiés / écoutés
- Aucun.

### Pièges et points d'attention
- Une projection scalaire (`findPartiesById`) sert à vérifier la propriété avant le verrou. Un `findById` d'entité remplirait le cache de premier niveau, et le `findByIdForUpdate` suivant rendrait un état périmé.
- `WalletAdminAdjustmentService` teste `isRefundRequestPending`, et non `isFrozen` : `isFrozen` couvre aussi le gel externe, qui doit laisser passer le débit.
- Tests unitaires : `when(mock.methode())` appelle la réponse générique déjà posée. Pour surcharger un stub à réponse, utiliser `doReturn(...).when(mock)`.

## Critères d'acceptation couverts
- [x] Recharge remboursée hors de l'app :
  - gel unique par remboursement, alerte, aucun débit ;
  - débit admin permis, crédit refusé, levée tracée ;
  - tests : `WalletExternalRefundServiceTest`, `WalletExternalRefundHoldIT` (Postgres), `WalletAdminAdjustmentServiceTest`, `AdminUserWalletControllerTest`, `PaymentStripeWebhookHandlerTest`.
- [x] Taux périmé de la devise du colis : aucune conversion (`WalletCommissionCollectorTest`).
- [x] Délais Stripe bornés pour les deux points d'entrée du SDK (`StripeConfigNetworkRetriesTest`).
- [x] Devise inconnue en 422 sans création de portefeuille, page négative traitée (`WalletControllerIT`).
- [x] Item pawaPay remboursé audité, une seule fois (`WalletPawapayRefundIssuerTest`).
- [x] Tiers refusé avant tout verrou (`CashCommissionServiceTest`).
- [x] Jeton masqué dans les journaux nginx : vérifié dans un conteneur nginx 1.27 (`nginx -t`, puis requête et référent).

## Tests
- `./mvnw clean test jacoco:report` → 14 266 tests, 0 échec, 0 erreur (7 ignorés, préexistants).
- Couverture JaCoCo des lignes : 96,45 % (WalletExternalRefundService, AdminUserWalletController, StripeConfig, WalletPawapayRefundIssuer : 100 %).
- Tests ajoutés : `WalletExternalRefundServiceTest`, `WalletExternalRefundHoldIT` ; complétés : `WalletServiceTest`, `WalletAdminAdjustmentServiceTest`, `AdminUserWalletControllerTest`, `PaymentStripeWebhookHandlerTest`, `WalletCommissionCollectorTest`, `StripeConfigNetworkRetriesTest`, `WalletControllerIT`, `WalletPawapayRefundIssuerTest`, `CashCommissionServiceTest`, `CashCommissionServiceGridTest`.

## Décisions techniques
- **Table de gels dédiée** plutôt que réutiliser `wallet_dispute_holds` : un remboursement externe n'a ni montant repris ni issue gagné ou perdu, et se lève à la main avec une note.
- **Débit admin autorisé pendant le gel** : c'est le seul moyen de retirer du solde un crédit déjà rendu sur la carte, sous le contrôle C1 (double validation au-delà de 100 €, plafond de 24 h).
