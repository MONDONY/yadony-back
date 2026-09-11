# Mobile money sur la négociation d'un colis, lot 2 (Backend)

**Date :** 2026-09-11
**Status :** Complète
**Branche :** `worktree-mobile-money-negociation-lot2` (base `b75ba2c4` = `main`, HEAD `06da4621`)
**Précédent :** `docs/stories-done/story-mobile-money-negociation-colis-lot1.md` (pose le rail à
blanc, fermé)

## Résumé

Ouvre réellement le rail mobile money posé par le lot 1 sur le fil de négociation d'une demande
de colis : `NegotiationService.travelerCanOffer` ne ferme plus `MOBILE_MONEY` en dur, il délègue
à `UserEntity.canReceiveMobileMoney(currency)` (compte de versement actif DANS la devise de la
demande). Les fiches demandes (recherche, favoris, détail) annoncent désormais la disponibilité
réelle du mobile money selon le compte de versement du visiteur qui consulte, au lieu du `false`
codé en dur. La réponse d'un fil expose l'échéance du dépôt mobile money en cours
(`depositExpiresAt`), non nulle seulement pendant `AWAITING_DEPOSIT`. Aucun nouvel endpoint : le
lot 1 les avait tous posés (`POST .../mobile-money/initiate`, `GET .../mobile-money/status`,
`POST .../mobile-money/cancel-deposit`) ; ce lot ne fait que lever les deux fermetures qui les
rendaient injoignables en usage réel, plus un champ de lecture supplémentaire dans la réponse du
fil.

## Fichiers créés

- `src/main/java/com/yadony/api/requests/service/ViewerPaymentCapabilities.java` — record
  `(boolean hasConnect, String mobileMoneyCurrency)`, capacités de paiement d'un visiteur
  (voyageur potentiel), calculé une fois par requête HTTP et appliqué à chaque demande d'une
  page. Garde la devise du compte de versement (pas un booléen) pour que
  `canReceiveMobileMoney(currency)` décide demande par demande sans requête supplémentaire.
  Constante `NONE` (visiteur anonyme/inconnu). Fabrique statique `of(UserEntity)`.
- Tests : `ViewerPaymentCapabilitiesTest` (3 tests unitaires du record).

## Fichiers modifiés

- `src/main/java/com/yadony/api/requests/service/NegotiationService.java` —
  `travelerCanOffer(UserEntity, PaymentMethod, String currency)` : nouveau paramètre `currency` ;
  `case MOBILE_MONEY -> t.canReceiveMobileMoney(currency)` remplace `case MOBILE_MONEY -> false`.
  `computeAvailableMethods` : l'appel `travelerCanOffer(traveler, STRIPE, ...)` passe désormais
  `request.getCurrency()` ; nouveau bloc ajoutant `MOBILE_MONEY` au set si accepté par la demande
  et fournissable par le voyageur. Le filtre devise final (`CurrencyPaymentRails.allowsCode`)
  reste inchangé et continue de retirer `MOBILE_MONEY` hors zone CFA (EUR notamment) — double
  garde, pas une redite : la première teste la capacité du voyageur, la seconde la compatibilité
  de la demande. `NegotiationThreadResponse` construit dans `toResponse` : calcul de
  `depositExpiresAt`, non nul uniquement si `status == AWAITING_DEPOSIT`.
- `src/main/java/com/yadony/api/matching/BidNegotiationService.java` — commentaire (lignes
  ~158-161) mis à jour : ne prétend plus que `NegotiationService.travelerCanOffer` « rend
  structurellement false » pour le mobile money. Comportement inchangé : un bid continue de
  rejeter `MOBILE_MONEY` en 422 (`mobile-money-negotiation-unsupported`) — hors périmètre de ce
  lot, qui ne touche que le fil de négociation d'une demande, pas les bids d'un trajet.
- `src/main/java/com/yadony/api/requests/service/PackageRequestSearchMapper.java` — les trois
  méthodes (`toSearchResponse` batch-aware, `toSearchResponse` public, `toSearchResponseList`)
  portent `ViewerPaymentCapabilities viewer` au lieu de `boolean viewerHasConnect`. Les deux
  appels `AnnouncementPaymentRails.offerable(...)` passent désormais
  `viewer.hasConnect(), viewer.canReceiveMobileMoney(entity.getCurrency())` au lieu de
  `viewerHasConnect, false`.
- `src/main/java/com/yadony/api/requests/service/PackageRequestService.java` —
  `resolveViewerHasConnect(UUID)` renommée `resolveViewerCapabilities(UUID)`, rend
  `ViewerPaymentCapabilities.of(userRepository.findById(id).orElse(null))` (ou `NONE` si l'id est
  nul). Les 4 sites d'appel (`getById`, `search`, `searchMatchingMyTrips`, `searchNearMe`)
  utilisent la nouvelle méthode. `toResponse(..., ViewerPaymentCapabilities viewer, ...)`
  (ex-booléen) et son appel `offerable` mis à jour.
- `src/main/java/com/yadony/api/favorites/FavoriteService.java` — appelant non listé par le plan
  initial, découvert à la compilation : `getFavoritePackageRequests` construisait localement un
  `boolean viewerHasConnect` passé à `toSearchResponseList`. Adapté pour construire
  `ViewerPaymentCapabilities.of(userRepository.findById(callerId).orElse(null))`.
- `src/main/java/com/yadony/api/requests/dto/NegotiationThreadResponse.java` — nouveau composant
  final `LocalDateTime depositExpiresAt`, après `commissionDeadline`. Le constructeur de
  compatibilité qui construit le canonique complet (32 paramètres, « contrat Task 7 round 1 »)
  passe un `null` supplémentaire ; les 3 autres constructeurs de compatibilité délèguent en
  cascade à celui-ci sans jamais lister le composant explicitement, donc n'ont pas eu besoin
  d'être touchés.
- Tests modifiés/étendus : `NegotiationServiceTest` (5 nouveaux tests répartis dans
  `SubmitTripPaymentMethodTests` et `GetByIdAndListTests`), `PackageRequestServiceTest` (2
  nouveaux tests dans `GetByIdTests`), `FavoriteServiceTest` (9 occurrences `anyBoolean()`
  remplacées par `any(ViewerPaymentCapabilities.class)`).

Détail exhaustif : `git diff --stat b75ba2c4..HEAD` (11 fichiers, +393/-78).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. **Fil de négociation (`computeAvailableMethods`)** — au moment où un voyageur soumet un
   trajet en réponse à une demande (`submitTrip`) ou que le fil recalcule les moyens disponibles,
   `travelerCanOffer(traveler, MOBILE_MONEY, request.getCurrency())` interroge
   `UserEntity.canReceiveMobileMoney(currency)` : vrai seulement si le voyageur a un compte de
   versement mobile money actif **dans la devise exacte de la demande**. Si oui et que la demande
   accepte `MOBILE_MONEY` dans `acceptedPaymentMethods`, le moyen rejoint le set
   `availablePaymentMethods` exposé côté client. Le filtre devise final
   (`CurrencyPaymentRails.allowsCode`) reste une seconde garde : une demande EUR ne verra jamais
   `MOBILE_MONEY`, même si le voyageur a un compte XOF actif.
2. **Fiches demandes (`PackageRequestSearchMapper` / `PackageRequestService`)** — chaque
   contrôleur qui rend une demande (recherche, mes-trajets, near-me, favoris, détail) calcule une
   fois `ViewerPaymentCapabilities` pour l'utilisateur courant (`resolveViewerCapabilities`), puis
   la passe à chaque demande de la page. Le mapper appelle
   `AnnouncementPaymentRails.offerable(accepted, currency, viewer.hasConnect(),
   viewer.canReceiveMobileMoney(currency))` — chaque demande décide pour SA propre devise, sans
   requête supplémentaire (la devise du compte est déjà dans le record, comparée en mémoire).
3. **Réponse du fil (`depositExpiresAt`)** — `NegotiationService.toResponse` calcule
   `t.getStatus() == AWAITING_DEPOSIT ? t.getDepositExpiresAt() : null` et le passe en dernier
   argument du constructeur canonique de `NegotiationThreadResponse`. Un fil qui est repassé à
   `AWAITING_PAYMENT` (dépôt échoué, expiré ou renoncé) a déjà sa colonne `deposit_expires_at`
   remise à `null` par `revertMobileMoneyDeposit` (lot 1) : le filtre par statut n'a donc rien à
   corriger sur ce chemin. Les cas résiduels où la colonne reste renseignée hors
   `AWAITING_DEPOSIT` sont `CANCELLED` (négociation terminée pendant le dépôt en cours) et
   `AUTO_REJECTED` (accord concurrent accepté) : le filtre reste nécessaire pour eux, sous peine
   d'exposer une échéance périmée au client.
4. **Le rail lui-même (initiation du dépôt, confirmation, scellement, expiration, remboursement)
   est entièrement celui posé par le lot 1** — ce lot n'y touche pas, il ne fait que le rendre
   atteignable depuis un fil réel (avant ce lot, `travelerCanOffer` renvoyait toujours `false`
   pour `MOBILE_MONEY` donc `prepareMobileMoneyDeposit` n'était jamais appelée en usage normal ;
   `PackageRequestService` codait `false` en dur pour le 3e argument d'`offerable`).

### Points d'entrée API

**Aucun nouvel endpoint.** Les trois endpoints du rail mobile money sur un fil
(`POST /negotiations/{id}/mobile-money/initiate`, `GET .../status`, `POST .../cancel-deposit`)
existent depuis le lot 1 (voir sa story, section « Points d'entrée API ») ; ce lot ne fait que
les rendre réellement atteignables en pratique (avant, aucun fil ne pouvait proposer
`MOBILE_MONEY`, donc `initiate` répondait toujours 422 côté validation métier faute de moyen
disponible). Les réponses existantes (`GET /negotiations/{id}`, recherche/favoris de demandes)
portent un champ de plus (`depositExpiresAt`) ou une valeur différente
(`availablePaymentMethods` peut désormais contenir `MOBILE_MONEY`), mais aucune route n'a
changé.

### Entités JPA impliquées

Aucune entité ni migration nouvelle. `UserEntity.canReceiveMobileMoney(String currency)` (déjà
existante, utilisée par le portail final `prepareMobileMoneyDeposit` du lot 1) est la seule
source de vérité consultée ; ce lot ne fait que la brancher à deux endroits qui codaient un
résultat fixe (`false`) au lieu de l'appeler.

### Logique métier critique

- **Une seule règle de capacité, jamais dupliquée** : la disponibilité affichée
  (`travelerCanOffer`, `ViewerPaymentCapabilities.canReceiveMobileMoney`) et la disponibilité
  réellement exigée au moment de payer (`NegotiationService.prepareMobileMoneyDeposit`, lot 1)
  utilisent la même méthode `UserEntity.canReceiveMobileMoney(currency)`. Annoncer plus large que
  ce que le portail de dépôt accepte mènerait l'expéditeur à un 422 au moment de payer après avoir
  vu le moyen proposé — piège explicitement évité (voir Décisions techniques).
- **Devise du compte, jamais un booléen, dans `ViewerPaymentCapabilities`** : un visiteur avec un
  compte de versement actif en XOF ne doit voir le mobile money proposé que sur les demandes
  libellées en XOF, pas sur une demande XAF ou EUR de la même page de résultats. Garder la devise
  (et non un simple `hasMobileMoney: boolean`) permet cette décision demande par demande sans
  requête supplémentaire par demande.
- **Double garde devise sur le fil** : `travelerCanOffer` teste la capacité du voyageur pour LA
  devise de la demande ; le filtre `CurrencyPaymentRails.allowsCode` en fin de
  `computeAvailableMethods` teste indépendamment que la devise de la demande autorise le rail
  mobile money (zone CFA). Les deux gardes sont nécessaires et non redondantes : la première
  protège contre un voyageur non versable dans cette devise précise, la seconde contre une
  demande dans une devise hors zone CFA même si le voyageur a un compte quelque part.
- **`depositExpiresAt` filtré par statut, pas juste lu depuis la colonne** : un retour à
  `AWAITING_PAYMENT` remet déjà la colonne `negotiation_threads.deposit_expires_at` à `null` via
  `revertMobileMoneyDeposit` (lot 1). Les cas résiduels où elle reste renseignée hors
  `AWAITING_DEPOSIT` sont `CANCELLED` (négociation terminée pendant le dépôt) et `AUTO_REJECTED`
  (accord concurrent) ; le mapping conditionnel dans `toResponse` est la garantie que le client ne
  voit jamais une échéance obsolète dans ces deux cas.

### Events Spring publiés / écoutés

Aucun événement nouveau ni modifié. Ce lot ne touche à aucun listener, scheduler ni service de
paiement — uniquement des lectures (mapping DTO, calcul de capacité) en amont du rail posé par le
lot 1.

### Pièges et points d'attention

1. **`travelerCanOffer` a maintenant trois paramètres** (`UserEntity`, `PaymentMethod`,
   `String currency`) — tout futur appelant doit passer la devise de la demande concernée, pas
   celle d'une autre entité (annonce, trajet). L'unique appelant reste
   `computeAvailableMethods`.
2. **`ViewerPaymentCapabilities.NONE` pour tout visiteur non identifié** (id nul, utilisateur
   introuvable) — jamais `null` passé au mapper, qui déréférencerait un viewer nul dans
   `viewer.hasConnect()`/`canReceiveMobileMoney(...)`.
3. **`FavoriteService` était un appelant non listé par les tâches 1/2** : tout futur changement de
   signature sur `PackageRequestSearchMapper`/`PackageRequestService` doit être vérifié par
   compilation complète (`./mvnw test-compile`), pas seulement par grep des deux fichiers a
   priori évidents — un appelant peut vivre dans un package totalement différent
   (`favorites/`).
4. **`BidNegotiationService` rejette toujours `MOBILE_MONEY` sur un bid** (422
   `mobile-money-negotiation-unsupported`) — seul le commentaire a changé dans ce lot, le
   comportement est intact et volontairement hors périmètre : ce lot n'ouvre que le fil de
   négociation d'une **demande**, pas les bids d'un **trajet**.
5. **`PackageRequestSearchMapper.toSearchResponse(entity, isFavorite, viewer)` (overload public à
   3 arguments) et `PackageRequestService.toSearchResponse(entity, isFavorite)`, son unique
   appelant, ont été supprimés en revue finale (aucun appelant réel, tous les chemins de
   production passent par la surcharge batch-aware) : ne reste plus que
   `toSearchResponse(entity, isFavorite, viewer, userMap, cityMap, photoMap)` et
   `toSearchResponseList`.
6. **Côté annonces, `AnnouncementService.toResponse` et `AnnouncementSearchMapper` appellent
   encore `traveler.hasActiveMobileMoney()` sans devise** (pas `canReceiveMobileMoney(currency)`)
   — même défaut que celui corrigé dans ce lot pour `buildDedicatedTripAnnouncement`, mais côté
   annonces plutôt que côté demandes. Hors périmètre de ce lot (qui ne touche que le fil de
   négociation d'une demande) ; correctif de suite à planifier.
7. **Nouveau code d'erreur `payment-method/mobile-money-capability-required`** (422, levé par
   `NegotiationService.assertNonEmptyOrThrow` quand seul `MOBILE_MONEY` était accepté par la
   demande et que le voyageur n'a pas de compte de versement actif dans sa devise) — à cataloguer
   côté app parmi les codes `payment-method/*` déjà gérés (`card-capability-required`,
   `cash-funds-required`, `none-available`).

## Critères d'acceptation couverts

- [x] `NegotiationService.travelerCanOffer` fournit `MOBILE_MONEY` selon
      `UserEntity.canReceiveMobileMoney(currency)` au lieu d'un `false` en dur.
- [x] `computeAvailableMethods` ajoute `MOBILE_MONEY` au set exposé quand la demande l'accepte et
      que le voyageur est capable (avant filtre devise final, inchangé).
- [x] Fiches demandes (recherche, mes-trajets, near-me, favoris, détail) annoncent le mobile
      money selon le compte de versement réel du visiteur, pas un `false` codé en dur.
- [x] `ViewerPaymentCapabilities` garde la devise du compte (pas un booléen), calculé une fois par
      requête HTTP.
- [x] Réponse du fil (`GET /negotiations/{id}`) expose `depositExpiresAt`, non nul uniquement en
      `AWAITING_DEPOSIT`.
- [x] Aucun nouvel endpoint (les trois du lot 1 restent inchangés, désormais réellement
      atteignables).
- [x] Non-régression : suite complète du dépôt à 0 échec, couverture globale ≥ 90 %.
- [x] Story lot 1 mise à jour (la phrase annonçant `travelerCanOffer` fermé porte désormais une
      note pointant vers ce lot).

## Tests

- `./mvnw clean test jacoco:report` → **BUILD SUCCESS**, `Tests run: 5396, Failures: 0, Errors: 0,
  Skipped: 7` (suite complète du dépôt). Aucun flake constaté sur cette exécution (le flake
  d'infrastructure `BroadcastAudienceServiceIT`, vu lors de la tâche 2 sur une exécution
  antérieure, n'est pas réapparu ici — pas de relance ciblée nécessaire).
- Couverture globale (pied de tableau « Total » de `target/site/jacoco/index.html`, colonne
  instructions) : **90,78 %** (11 957 instructions manquées / 129 623 exécutables, 90 % affiché).
  Seuil ≥ 90 % respecté.
- Couverture par classe touchée (lue sur `target/site/jacoco/jacoco.csv`, instructions) :
  - `ViewerPaymentCapabilities` : **100 %** (47/47) — gate explicite du brief, satisfait sans
    ajout de test.
  - `NegotiationThreadResponse` : **100 %** (293/293, record).
  - `PackageRequestService` : **95,68 %** (2170/2268).
  - `NegotiationService` : **91,55 %** (5076/5545, fichier entier — la classe la plus volumineuse
    du dépôt).
  - `PackageRequestSearchMapper` : **45,24 %** (252/557) — porté à la baisse par deux méthodes
    déjà non exercées avant ce lot (`toSearchResponse(entity, isFavorite, viewer)` overload public
    à 3 arguments, `toSearchResponseList`), toutes deux mockées en totalité dans
    `FavoriteServiceTest` ; la méthode réellement utilisée en production par tous les chemins de
    recherche (`toSearchResponse` batch-aware à 6 arguments) est à 96 %. Gap pré-existant au
    renommage de paramètre de ce lot, pas une régression : aucun test n'exerçait déjà ces deux
    overloads avant le lot 2. Non traité (aucun gate explicite du brief sur cette classe, et la
    couverture globale ≥ 90 % reste respectée) ; laissé en dette signalée pour un lot futur si la
    couverture par classe devient un gate.
- Tests ajoutés (mêmes commits que le code) : `ViewerPaymentCapabilitiesTest` (3 tests),
  `NegotiationServiceTest` (+5 : `submitTrip_xofRequest_travelerWithXofAccount_offersMobileMoney`,
  `submitTrip_xofRequest_travelerWithXafAccount_hidesMobileMoney`,
  `submitTrip_eurRequest_neverOffersMobileMoney`, `getById_awaitingDeposit_exposesDepositExpiresAt`,
  `getById_otherStatus_hidesStaleDepositExpiresAt`), `PackageRequestServiceTest` (+2 :
  `getById_viewerWithXofAccount_seesMobileMoney`, `getById_viewerWithXafAccount_hidesMobileMoney`),
  `FavoriteServiceTest` (9 sites adaptés à la nouvelle signature, pas de nouveau scénario).

## Décisions techniques

1. **Capacité mobile money du visiteur = compte de versement actif DANS la devise de la demande
   (`canReceiveMobileMoney(currency)`), jamais `hasActiveMobileMoney()` seul.** Même règle que le
   portail final `prepareMobileMoneyDeposit` (lot 1) qui exige déjà cette condition avant de créer
   un dépôt. Annoncer la disponibilité sur un critère plus large (juste « a un compte actif »,
   sans vérifier la devise) aurait affiché `MOBILE_MONEY` à un voyageur versable en XOF sur une
   demande XAF, qui aurait ensuite reçu un 422 en tentant réellement le dépôt — expérience
   incohérente entre ce qui est proposé et ce qui est accepté. `ViewerPaymentCapabilities` garde
   donc la devise du compte (`mobileMoneyCurrency`), pas un booléen, précisément pour reproduire
   cette même condition côté affichage sans requête supplémentaire par demande.
2. **Aucun nouvel endpoint** : le lot 1 avait déjà posé les trois routes du rail mobile money sur
   un fil (`initiate`, `status`, `cancel-deposit`). Ce lot ouvre uniquement les deux fermetures qui
   les rendaient inatteignables en usage réel (`travelerCanOffer` et le `false` codé en dur dans
   `PackageRequestService`) et ajoute un champ de lecture (`depositExpiresAt`) à une réponse
   existante. Aucune route créée, modifiée ou supprimée.
3. **`depositExpiresAt` masqué hors `AWAITING_DEPOSIT`**, bien qu'un retour à `AWAITING_PAYMENT`
   (expiration, renoncement, échec) remette déjà la colonne `negotiation_threads.deposit_expires_at`
   à `null` via `revertMobileMoneyDeposit` (lot 1) : les cas résiduels sont `CANCELLED` (négociation
   terminée pendant le dépôt en cours) et `AUTO_REJECTED` (accord concurrent accepté, celui-ci
   perd), pour lesquels la colonne n'est jamais réinitialisée. Filtrer par statut dans le mapping
   DTO (`toResponse`) plutôt que d'ajouter une remise à `null` sur ces deux transitions supplémentaires
   évite d'étendre encore les chemins d'écriture à maintenir corrects pour un gain identique.
4. **`ViewerPaymentCapabilities` garde la devise du compte, pas un booléen** : une page de
   résultats mélange des demandes de devises différentes (recherche multi-corridors, favoris). Un
   simple `hasMobileMoney: boolean` calculé une fois par requête HTTP aurait forcé soit un
   recalcul par demande (requête supplémentaire), soit une réponse incorrecte pour les demandes
   dans une devise différente de celle du compte du visiteur. Porter la devise dans le record et
   comparer en mémoire (`canReceiveMobileMoney(entity.getCurrency())`) résout les deux : un seul
   calcul par requête HTTP, une décision correcte par demande.
5. **`FavoriteService`, appelant non anticipé par le plan initial, corrigé au même standard** :
   découvert à la compilation (le changement de signature de `PackageRequestSearchMapper` cassait
   `getFavoritePackageRequests`), pas par une revue exhaustive des appelants a priori. Corrigé en
   construisant `ViewerPaymentCapabilities.of(...)` comme les autres sites, plutôt que de garder
   un chemin `boolean` séparé qui aurait réintroduit la même incohérence que ce lot corrige
   ailleurs.
6. **Écart de package `MobileMoneyPayoutStatus` dans les briefs des tâches 1/2** (annoncé dans
   `com.yadony.api.payments.mobilemoney`, réellement dans `com.yadony.api.auth`) : corrigé dans
   les tests sans impact sur le code de production, vérifié par lecture directe du fichier avant
   écriture des tests plutôt que de faire confiance au brief.
7. **`PackageRequestSearchMapper.toSearchResponse`/`toSearchResponseList` (overloads publics non
   batch-aware) laissés à 0 % de couverture** : gap pré-existant au renommage de paramètre de ce
   lot (déjà non exercés avant, `FavoriteServiceTest` mocke le mapper en totalité), aucun gate
   explicite du brief sur cette classe précise, couverture globale toujours ≥ 90 %. Ajouter un
   test de ces deux overloads aurait dépassé le périmètre de cette tâche (aucun changement de
   logique dedans, seulement de signature) ; signalé comme dette pour un lot futur si la
   couverture par classe devient un gate contractuel.
