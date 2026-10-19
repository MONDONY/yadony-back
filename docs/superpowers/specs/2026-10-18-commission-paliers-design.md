# Commission Yadony par paliers, minimum 1 €, onglet admin « Tarification »

Date : 18/10/2026. Dépôts touchés : yadony-back (ce dépôt), dony-admin, dony_app, dony-pro, yadony-web.

## 1. Pourquoi

Aujourd'hui la commission est un taux unique (5 %, réglé dans l'admin, clé `commission_rate`), appliqué de façon différente selon le moyen de paiement :

| Rail | Calcul actuel | Minimum |
|---|---|---|
| Carte (Stripe, charges et transferts séparés) | `net × taux` (`PaymentService` l. 580-586) | aucun |
| Espèces, commission débitée du solde | `max(net × taux, 1 €)` (`CashCommissionService.computeCommission` l. 177-181) | 1 €, property `yadony.commission.minimum-amount` |
| Espèces, prix négocié (fil) | `net × taux` (`CashCommissionService` l. 589-590) | aucun (écart non voulu) |
| Mobile money (pawaPay) | `net × taux` (`MobileMoneyBidPricing` l. 72) | aucun (voulu) |

Deux problèmes :

1. **Rentabilité.** Frais vérifiés le 18/10/2026 :
   - carte EEE standard : 1,5 % + 0,25 € sur le montant payé, plus Connect 0,25 % + 0,10 € par versement et 2 € par compte voyageur actif par mois ;
   - pawaPay : dépôt de 1,8 à 2,5 % et versement de 1,3 à 2 %.

   À 5 %, un colis de 10 € payé par carte coûte plus qu'il ne rapporte (−0,03 €), et le mobile money ne laisse presque rien (+0,38 € sur 100 € chez Orange Côte d'Ivoire).
2. **Droit.** Une commission qui varie selon le moyen de paiement ressemble à un surcoût sur la carte, interdit par l'article L112-12 du Code monétaire et financier. Le minimum n'est annoncé nulle part dans l'API.

Le propriétaire veut une commission **par paliers**, un **minimum de 1 €** sur tous les moyens de paiement, et la possibilité de **tout régler depuis un onglet dédié de l'admin**.

## 2. Décisions du propriétaire (18/10/2026)

| Sujet | Décision |
|---|---|
| Forme de la grille | Grille **unique** pour tous les rails et toutes les devises, **marginale** (chaque taux s'applique à sa tranche, comme l'impôt sur le revenu, donc aucun effet de seuil) |
| Grille de départ | 7 % de 0 à 100 €, 6 % de 100 à 300 €, 5 % au-delà de 300 € |
| Minimum | 1 € en EUR, **650 FCFA fixe** en XOF et XAF |
| Promo, parrainage, taux spécial par utilisateur | La réduction s'applique au résultat de la grille, et le minimum reste dû, **sauf** quand la commission finale tombe à zéro (promo « commission offerte ») : alors 0 €. Le bon de parrainage s'applique **après** le minimum (décision prise après relecture) |
| Changement de grille | Le propriétaire choisit une **date d'effet**. L'admin l'**alerte, sans bloquer**, si une hausse prend effet moins de 30 jours plus tard. Les offres déjà créées gardent leur grille |
| Comptes pro (dony-pro) | Même grille. Les taux spéciaux existants (`users.commission_rate_override`) restent prioritaires |
| TVA | Inconnue. Le simulateur de l'admin a un champ « taux de TVA », à faire confirmer par l'expert-comptable |
| Réglage | Onglet admin « Tarification » : grille, minimums par devise, date d'effet, historique, simulateur de marge, aperçu du texte vu par l'expéditeur |

## 3. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
- Une seule formule de commission dans le back, utilisée par les quatre rails : carte, espèces, espèces négociées, mobile money.
- La grille et les minimums se règlent dans l'admin sans redéploiement. Les versions sont datées, conservées et tracées dans `audit_log`.
- Le montant affiché à l'expéditeur avant validation est exactement le montant prélevé.
- Le minimum est annoncé partout où une commission est affichée.

**Non-objectifs (v1)**
- Grille différente par moyen de paiement, par couloir, par devise ou pour les pros.
- Envoi automatique d'un e-mail ou d'une notification aux utilisateurs lors d'un changement de grille : le propriétaire annonce par ses canaux habituels.
- Facturation et TVA sur les reçus.
- Changement du modèle Stripe (charges et transferts séparés).
- Optimisation du coût Connect de 2 € par compte actif (fréquence des versements) : suite possible, hors périmètre.

**Anti-objectifs**
- Un prix affiché, à n'importe quelle étape, différent du montant réellement débité.
- Une commission qui dépend du moyen de paiement choisi.
- Une offre, une demande ou un fil déjà créé dont le prix change à cause d'une nouvelle grille.
- Un déploiement du back qui modifie un prix avant que le propriétaire ait programmé une grille.

## 4. La règle de calcul

### 4.1 Entrées
- `N` : prix net du voyageur (ce qu'il reçoit), dans la devise de l'offre `c`, parmi `SupportedCurrency` : EUR, USD, CAD, GBP, CHF, XOF, XAF.
- La grille `g` figée sur l'objet (voir 4.4), sinon la grille en vigueur à l'instant du calcul.
- Le contexte des réductions : voyageur, expéditeur, code promo, bon de parrainage, référence (bid ou fil), comme aujourd'hui dans `CommissionRateResolver.resolve(...)`.

### 4.2 Étapes
1. **Bornes dans la devise.** Les bornes de tranche sont saisies en EUR. Pour `c ≠ EUR`, elles sont converties par `CurrencyBounds.scaleFromEur`, dont la parité XOF et XAF est fixe (655,957). Elles ne sont pas arrondies.
2. **Commission de grille.** `C_grille = Σ taux_i × max(0, min(N, borne_sup_i) − borne_inf_i)`. La dernière tranche n'a pas de borne supérieure.
3. **Taux spécial.** Si le voyageur ou l'expéditeur a un `commission_rate_override` `r_o`, on prend le plus petit : `C_base = min(C_grille, N × r_o)`, soit `min` sur les deux overrides comme aujourd'hui.
4. **Code promo.** On convertit en taux effectif `r = C_base / N`, précision 6 décimales. On applique la remise du promo, sans changer sa règle (`PromoDiscount.applyTo`), avec un plancher à 0. On obtient `r_promo`, puis `C_promo = N × r_promo`.
5. **Minimum.** Si `r_promo == 0` (promo qui absorbe tout, ou taux spécial à 0), alors `C_min = 0`. Sinon `C_min = max(C_promo, minimum_c)`.
6. **Bon de parrainage, après le minimum** (décision du propriétaire 18/10) : `C = C_min × facteur du bon` (0,5 par défaut). Le parrainage fait donc toujours gagner quelque chose : 1,00 € devient 0,50 €. Avec un bon actif, le minimum effectif est donc `minimum_c × facteur`.
7. **Arrondi.** Une seule fois, à la fin, à l'unité mineure de `c` (HALF_UP via `CurrencyAmount`) : 2 décimales en EUR, 0 en XOF et XAF.
8. **Total payé.** `G = N + C`.

**Code promo sans effet** (*choix du rédacteur*, pour ne pas faire perdre un avantage pour rien) :
- quand le minimum annule tout le gain (`C` avec réduction == `C` sans réduction), `promoApplied` vaut faux ;
- le devis renvoie `promoIneffectiveReason: "COMMISSION_MINIMUM"`, et l'app affiche « Ce code ne change rien sur ce montant (frais minimum) » ;
- le code promo n'est pas consommé (`PromoService.redeem`), sur les trois rails. Le bon de parrainage s'applique toujours après le minimum, il ne peut donc pas être sans effet.

### 4.3 Calcul inverse : du total payé au net
`netForGross(G, c, g, contexte)` rend le plus grand `N` tel que `N + C(N, contexte) ≤ G`. Deux conventions, les mêmes qu'aujourd'hui :
- **Demandes de colis** : l'expéditeur saisit un budget TTC public (`PackageRequestService` l. 289, 407 et 1118). Le calcul se fait **sans réductions personnelles**, comme aujourd'hui avec `1 + globalRate()`.
- **Négociation sur un trajet**, brut et net (`BidNegotiationService` l. 175, `grossForNet` et `travelerNet` l. 309 et 922). Les réductions personnelles (taux spécial, bon de parrainage) sont **figées à l'ouverture du fil**, comme le taux l'est aujourd'hui, et l'inverse porte sur toute la chaîne : grille, taux spécial, bon, minimum. *Choix du rédacteur* : sinon un pro perdrait son taux spécial dans ses négociations, ce qui contredit le §2.
- La fonction `N ↦ N + C(N)` est strictement croissante (taux ≥ 0, minimum constant). On l'inverse donc segment par segment : zone du minimum, puis chaque tranche. On ajuste ensuite d'une unité mineure pour respecter l'arrondi, sur le modèle de `BidNegotiationPricing.grossForNet`.
- Si `G ≤ minimum_c`, le budget est trop faible : erreur 422 `budget-below-commission-minimum`, au format RFC 7807.

### 4.4 Figer la grille
- Chaque objet qui porte un prix montré à l'autre partie fige l'identifiant de la grille à sa **création** : bid, fil de négociation, demande de colis.
  - Un fil ouvert sur une demande hérite de la grille de la demande.
  - Une négociation de bid hérite de la grille du bid.
- Tout recalcul ultérieur utilise la grille figée, jamais la grille courante : acceptation, prélèvement espèces, dépôt mobile money, capture, remboursement, nouvelle tentative.
- Les objets créés avant cette fonctionnalité n'ont pas de grille. Ils gardent l'**ancien calcul**, taux figé et minimum espèces actuel, jusqu'à leur clôture. Ce chemin hérité est marqué à supprimer 90 jours après l'activation de la première grille.
- `bids.commission_rate` est en `DECIMAL(4,3)`, ce qui ne suffit pas pour un taux effectif. On l'élargit (migration `V331`) ou on ajoute une colonne : c'est au constructeur de décider. Le montant de commission reste porté par le paiement, comme aujourd'hui.

### 4.5 Avant la première grille
Sans grille programmée et en vigueur, le back calcule **exactement comme aujourd'hui**. Le déploiement ne change donc aucun prix. La grille 7/6/5 n'est pas insérée par migration : le propriétaire la crée dans l'admin, avec sa date d'effet.

### 4.6 Vecteurs de référence (grille 7/6/5, minimum 1 € / 650 FCFA)

| Cas | N | C | G |
|---|---|---|---|
| Minimum | 5,00 € | 1,00 € | 6,00 € |
| Juste sous le minimum | 14,28 € | 1,00 € | 15,28 € |
| Tranche 1 | 20,00 € | 1,40 € | 21,40 € |
| Fin de tranche 1 | 100,00 € | 7,00 € | 107,00 € |
| Tranche 2 | 150,00 € | 10,00 € | 160,00 € |
| Fin de tranche 2 | 300,00 € | 19,00 € | 319,00 € |
| Tranche 3 | 500,00 € | 29,00 € | 529,00 € |
| XOF, minimum | 5 000 | 650 | 5 650 |
| XOF, tranche 1 | 20 000 | 1 400 | 21 400 |
| XOF, tranche 2 | 100 000 | 6 656 | 106 656 |
| Promo 5 points, N = 20 € | 20,00 € | 1,00 € (le minimum prime sur 0,40 €) | 21,00 € |
| Promo offerte (points ≥ taux) | 20,00 € | 0,00 € | 20,00 € |
| Bon ×0,5, N = 100 € | 100,00 € | 3,50 € | 103,50 € |
| Bon ×0,5, N = 5 € (après minimum) | 5,00 € | 0,50 € | 5,50 € |
| Promo 5 points + bon ×0,5, N = 20 € | 20,00 € | 0,50 € (minimum 1,00 € × 0,5) | 20,50 € |
| Override 4 %, N = 100 € | 100,00 € | 4,00 € | 104,00 € |
| Inverse, G = 21,40 € | 20,00 € | | |
| Inverse, G = 1,50 € | 0,50 € | 1,00 € | |
| Inverse, G = 1,00 € | 422 | | |

Il faut ajouter des vecteurs de négociation de trajet avec un taux spécial et un bon figés, dans les deux sens (brut → net et net → brut).

Ces vecteurs vont dans un fichier JSON de fixtures dans le back. Le même fichier est **recopié** dans dony_app pour tester le portage Dart (voir 7.3).

## 5. Back (yadony-back)

### 5.1 Données (migration V331 ou suivante libre au moment de la PR, à recaler sur `main` avant fusion)
- `commission_grids` :
  - `id` uuid ;
  - `effective_from` timestamptz NOT NULL ;
  - minimums par devise, une colonne par devise de `SupportedCurrency` ou un jsonb, au choix du constructeur ;
  - `created_by`, `created_at`, `updated_at`, `deleted_at` (BaseEntity, suppression logique) ;
  - `note` texte libre facultatif.
- `commission_grid_tiers` :
  - `grid_id` ;
  - `position` ;
  - `lower_bound_eur` NUMERIC NOT NULL ;
  - `rate` NUMERIC(6,5) avec CHECK entre 0 et 0,30.
  - La borne supérieure est implicite : c'est la borne inférieure de la tranche suivante.
- Une grille dont `effective_from ≤ now()` est **immuable**, garanti par le service et par un trigger. Modifier une grille programmée, c'est la supprimer (logiquement) puis en créer une nouvelle. Une grille déjà en vigueur ne peut pas être supprimée.
- Grille en vigueur à l'instant `t` : la plus récente avec `effective_from ≤ t` et `deleted_at IS NULL`. Lecture en cache Caffeine, invalidée à chaque écriture et à chaque `effective_from` franchi (TTL court ou contrôle de l'horloge).
- Les colonnes qui figent la grille sur `bids`, `negotiation_threads` (ou équivalent) et `package_requests` sont nullables. NULL signifie ancien calcul.

### 5.2 Validation à la création d'une grille (422 par champ, ProblemDetail)
- La première borne vaut 0. Les bornes sont strictement croissantes. Entre 1 et 10 tranches.
- Chaque taux est entre 0 et 30 %, la même borne que l'actuel `commission_rate`.
- Un minimum est requis pour chaque devise supportée, ≥ 0, et plafonné à l'équivalent de 5 € (garde-fou contre une faute de frappe).
  - EUR 1, XOF 650, XAF 650 sont pré-remplis.
  - USD, CAD, GBP et CHF sont pré-remplis avec la conversion de 1 €, arrondie au 0,05 supérieur. Le propriétaire peut les corriger.
- `effective_from ≥ now()` : pas de date rétroactive.
- Au plus une grille programmée à la fois : programmer une nouvelle grille remplace la précédente non encore en vigueur. Choix du rédacteur, pour garder l'écran simple.

### 5.3 Calcul
- Nouveau `CommissionCalculator` dans `common/`, source unique.
  - `quote(CommissionRequest) → CommissionQuote{net, commission, gross, effectiveRate, gridId, minimumApplied, promoApplied}` ;
  - `netForGross(gross, currency, gridId)`.
- `CommissionRateResolver` garde la résolution des overrides, promos et bons. Il expose ses étapes, ou une fonction qui applique la chaîne à un taux donné, pour que le calculateur les applique au taux effectif (étape 4.2.4).
- Les 15 appels actuels au résolveur passent par le calculateur, et `PriceBreakdown.fromNet(net, rate)` aussi :
  - `BidService`, `BidNegotiationService`, `PriceGridService` ;
  - `CashCommissionService` (les deux chemins), `PaymentService` ;
  - `MobileMoneyBidPricing` ;
  - `NegotiationService`, `PackageRequestService`, `PackageRequestSearchMapper`, `ReferralService`, `GuestSession`.
- Le minimum propre à `CashCommissionService` et le commentaire « aucun plancher » de `MobileMoneyBidPricing` disparaissent pour les objets qui ont une grille.
- **Prix au kilo affiché** (`PriceGridService` l. 311, `pricePerKgDisplay`, `unitPriceDisplay`, `minGridUnitPriceDisplay`), décision du propriétaire 18/10 :
  - le prix au kilo des listes est le **prix du voyageur**, `net/kg`, sans frais ;
  - les champs `*Display` valent donc le net. Garder les noms ou en ajouter de nouveaux : au choix du constructeur, dans le respect des PR jumelles.
  - Le total exact, frais compris, est toujours montré avant validation.
- `GET /config/commission-rate` reste en place pour les anciennes versions de l'app. Il renvoie le taux de la première tranche de la grille en vigueur, sinon `commission_rate`.

### 5.4 API publique
- `GET /config/commission-grid` (public, comme `/config/**`) renvoie :
  - `current{gridId, effectiveFrom, tiers[{fromEur, rate}], minimums{EUR, XOF, …}}`, ou `null` sans grille. Dans ce cas, l'app retombe sur le taux unique.
  - `next{…}` ou `null` : la grille programmée, pour que l'app puisse l'annoncer plus tard. Elle n'est pas affichée en v1.
- Les réponses de devis (`BidQuoteResponse`, devis de négociation, `/package-requests/quote`) gagnent `minimumApplied` (booléen), `minimumAmount` et `commissionGridId`. Les champs existants restent tels quels (règle des PR jumelles).

### 5.5 API admin (permission existante `CONFIG_MANAGE`, `@PreAuthorize`)
- `GET /admin/commission-grids` : grille en vigueur, grille programmée, historique paginé (qui, quand, notes).
- `POST /admin/commission-grids` : programmer une grille.
  - Réponse : la grille plus `warnings[]`, dont `INCREASE_WITHIN_30_DAYS` quand une hausse prend effet à moins de 30 jours.
  - Hausse : la nouvelle commission dépasse l'actuelle pour au moins un montant de l'échantillon 1 € à 1 000 € (pas de 1 €) ou d'un minimum.
- `DELETE /admin/commission-grids/{id}` : suppression logique d'une grille **programmée**. Sur une grille en vigueur : 409.
- `POST /admin/commission-grids/simulate` :
  - entrée : grille brouillon ou `gridId`, montants nets, devise, taux de TVA ;
  - sortie, par montant : commission (actuelle et brouillon), total payé, coût prestataire et marge HT par rail. Les rails sont la carte standard, premium et internationale, le mobile money au pire et au meilleur opérateur pawaPay de la devise, et les espèces.
- `audit_log` : `COMMISSION_GRID_SCHEDULED` (avec la grille complète et les avertissements acceptés), `COMMISSION_GRID_CANCELLED`.
- Coûts des prestataires pour le simulateur : nouvelles properties `yadony.costs.stripe.*`, valeurs par défaut issues du tarif public Stripe France vérifié le 18/10/2026 :
  - carte EEE standard 1,5 % + 0,25 € ;
  - premium 2,8 % + 0,25 € ;
  - internationale 3,15 % + 0,25 € ;
  - Connect 0,25 % + 0,10 € par versement, 2 € par compte actif par mois, affiché à part.

  Les frais pawaPay viennent de la grille existante `yadony.pawapay.fees`, et les espèces du coût de recharge par carte. Ces chiffres ne servent qu'au simulateur, jamais au prix.

### 5.6 Textes de l'assistant (obligatoire, même PR back)
`assistant/kb/{fr,en}/commission.md` et toute fiche qui parle de frais disent :
- les frais de service Yadony dépendent du montant, avec un minimum de 1 € (650 FCFA) ;
- ils sont affichés avant validation ;
- ils sont identiques quel que soit le moyen de paiement.

Aucun pourcentage en dur. Corriger aussi la phrase actuelle « l'app n'affiche pas de pourcentage » si elle reste fausse.

## 6. Admin (dony-admin) : onglet « Tarification »

- **Structure.** Page `app/pages/tarification.vue` :
  - `definePageMeta({ middleware: 'admin-only', permission: 'CONFIG_MANAGE', pageTitle: 'Tarification' })` ;
  - `NavItem` dans `AppSidebar.vue`, entrée `SEARCH_PAGES`, ligne dans `AppSidebarGating.spec.ts` ;
  - code dans `app/features/commission-grid/{components,composables,services,types}`, avec 90 % de couverture.
- **Sous-onglets** via `?tab=`, sur le modèle de `gp-pro/index.vue`.
  1. **Grille**
     - Carte « En vigueur depuis le … », en lecture seule.
     - Carte « Programmée pour le … », avec un bouton Annuler confirmé.
     - Bouton « Préparer une nouvelle grille », qui ouvre l'éditeur pré-rempli avec la grille en vigueur :
       - lignes « À partir de (€) » / « Taux (%) », ajout et suppression de lignes ;
       - minimums par devise ;
       - date et heure d'effet, saisies et affichées en heure de Paris ;
       - note.
     - Sous l'éditeur, un tableau comparatif mis à jour en direct (simulate, avec debounce) pour 5, 10, 20, 50, 100, 200, 300 et 500 € et leur équivalent FCFA : commission actuelle, nouvelle commission, écart.
     - Validation : `ConfirmActionDialog`.
       - En cas d'avertissement `INCREASE_WITHIN_30_DAYS`, saisie de la phrase « HAUSSE SANS PRÉAVIS » ;
       - sinon, simple confirmation.
  2. **Simulateur**
     - Entrées : montant, devise, taux de TVA (défaut 20 %, mémorisé dans le navigateur).
     - Résultat : un tableau par rail (commission, total payé, coût prestataire, marge HT), avec la mention « Connect : 2 € par voyageur payé dans le mois, non inclus ».
     - **Aperçu** de la ligne vue par l'expéditeur, par exemple « Frais de service Yadony : 1,40 € » ou « 1,00 € (minimum) ».
  3. **Historique** : liste des grilles (date d'effet, auteur, note, état : en vigueur, programmée, passée ou annulée), détail dépliable.
- **Ancien réglage.** `SettingsForm` n'affiche plus `commission_rate` dès qu'une grille existe, et renvoie vers l'onglet. Sans grille, la carte reste, avec un lien vers l'onglet.
- **Formules.** Aucune formule de commission dans l'admin : tout passe par `simulate`.

## 7. Clients

### 7.1 Règle des PR jumelles
- La PR back est fusionnée et déployée d'abord.
- Les clients tolèrent l'absence de `/config/commission-grid` (404, ou `current: null`) en retombant sur le taux unique actuel.

### 7.2 Anciennes versions de l'app
- Une ancienne version calcule `net × (1 + taux)` en local et afficherait un faux total, sans le minimum, une fois la grille active.
- **Avant la date d'effet de la première grille**, monter `min_supported_build` dans Firebase Remote Config (mécanisme existant `AppUpdateService` / `ForceUpdateScreen`) jusqu'au premier build qui gère la grille.
- C'est une étape de mise en production, dans la checklist du §9.

### 7.3 dony_app
- **Modèle et chargement.**
  - Modèle `CommissionGrid`, chargé au démarrage par `ConfigBloc` depuis `/config/commission-grid` et mis en cache, à côté de `setDonyCommissionRate`.
  - Fonctions pures Dart `commissionFor(net, currency)` et `netForGross(gross, currency)`, portage exact du §4.2 et du §4.3 hors réductions personnelles.
  - Tests sur les vecteurs du §4.6, recopiés du fichier de fixtures du back.
  - Pour le fil de négociation d'un trajet, le serveur envoie dans la négociation le contexte figé : `gridId`, taux spécial et facteur du bon. L'aperçu « L'expéditeur paiera » (`bid_negotiation_thread_screen.dart`) l'applique, ce qui remplace `negotiation.commissionRate`.
- **Calculs locaux à remplacer** (inventaire du 18/10). Quand le serveur fournit les montants, on les garde :
  - `core/pricing/dony_pricing.dart` (`netToSenderPrice`, `donyCommissionMultiplier`, `convertedSenderPricePerKg`) ;
  - `package_request/data/models/price_display.dart` (`grossFromNet`, `netFromGross`, `feeFromNet`) ;
  - `matching/data/models/bid_negotiation_pricing.dart` (`grossForNet`) ;
  - `create_bid_bottom_sheet.dart` (`_localSenderTotal`, `_computeStripeTotal`, `gridNet`) ;
  - `payment_screen.dart` (`_commission`) ;
  - `step_3_recap_budget.dart` (net de base et « boost promo ») ;
  - `counter_offer_bottom_sheet.dart`, `thread_state_cta_bar.dart`, `package_request_form_bloc.dart`, `prix_conditions_step.dart`, `price_grid_item_form_sheet.dart`, `open_surplus_bottom_sheet.dart`, `traveler_announcement_card.dart`, `thread_message_bubble.dart` ;
  - tous les replis `grossPriceEur ?? grossFromNet(...)`.
- **Prix au kilo affiché** (cartes trajet, carte, affiches, grille, aperçus de publication) : prix du voyageur au kilo, suivi de « + frais de service Yadony (min. 1 €) ». Le minimum vient de la grille, dans la devise affichée. Décision du propriétaire 18/10. Point à faire relire par le juriste : le prix d'appel est affiché hors frais, et le total frais compris n'apparaît qu'avant validation.
- **Affichage du minimum.**
  - Partout où une commission ou des frais de service sont affichés, ajouter « (minimum) » quand `minimumApplied`, ou quand le calcul local applique le minimum.
  - Remplacer le « min. 1 € » codé en dur de `commission_method_screen.dart` par le minimum de la grille dans la devise.
- **Textes.** Les ARB en français et en anglais : notices commission et FAQ `faqPaymentsCommissionQ/A`. On ne cite aucun pourcentage. On peut écrire « minimum 1 € » à partir de la grille chargée, jamais en dur.

### 7.4 dony-pro
- Remplacer les replis `1 − rate` (`useCommissionRate`, `usePricing`, `bidsService`, `useAnnouncementForm`) par les montants du serveur.
- Pour les aperçus « Vous recevez », appeler un aperçu serveur. Le constructeur choisit : endpoint public `GET /config/commission-preview?net=&currency=`, ou portage TypeScript du calcul avec les mêmes fixtures.
- Retirer le repli `0.12`.

### 7.5 yadony-web (CGU)
- Section « Frais de service » :
  - principe des paliers marginaux, avec la grille en vigueur à titre d'exemple daté ;
  - minimum de 1 € (650 FCFA) ;
  - mêmes frais quel que soit le moyen de paiement ;
  - montant exact affiché avant validation ;
  - changement annoncé au moins 30 jours avant, sans effet sur les offres déjà créées.
- Retirer toute mention d'un pourcentage unique (vitrine, FAQ, et dony-pro `LandingFaq`).

## 8. Points juridiques à respecter (rappel, ne remplace pas un avocat)
- Prix total TTC, frais compris, affiché avant validation (Code de la consommation, L112-1 et L111-1). Un taux annoncé sans son minimum est une pratique trompeuse (L121-2).
- Modalités des frais décrites dans les CGU (L111-7, obligation d'information des plateformes).
- Pas de commission différente selon le moyen de paiement (Code monétaire et financier, L112-12).
- Pas de modification unilatérale sans préavis (R212-1). Pour les pros, règlement P2B : 15 jours minimum, couverts par les 30 jours.
- Une promo « commission offerte » ne doit rien prélever, d'où l'exception de l'étape 4.2.5.
- TVA : statut à faire confirmer par l'expert-comptable avant l'activation.

## 9. Ordre de livraison et mise en production
1. PR back : tables, calculateur, chemin hérité, endpoints publics et admin, fixtures, fiches de l'assistant. Aucune grille insérée.
2. PR admin (onglet), PR app, PR pro, PR web (CGU), chacune citant la PR back.
3. Déploiement du back en staging, puis recette :
   - une grille de test programmée à J+5 minutes ;
   - les vecteurs du §4.6 vérifiés sur les quatre rails ;
   - un objet créé avant la grille garde l'ancien calcul.
4. Production, faite par le propriétaire :
   - déployer le back ;
   - publier le build de l'app ;
   - monter `min_supported_build` ;
   - mettre en ligne les CGU ;
   - annoncer aux utilisateurs ;
   - programmer la grille 7/6/5 avec une date d'effet à au moins 30 jours de l'annonce.

## 10. Laissé au constructeur
- Forme exacte des colonnes (jsonb ou colonnes, élargissement de `bids.commission_rate` ou nouvelle colonne), noms des DTO, découpage en lots et en PR internes.
- Implémentation du cache de la grille et du franchissement de `effective_from`.
- Forme de l'aperçu serveur pour dony-pro.
- Disposition visuelle de l'onglet admin, dans le style existant (pas de toast, messages en ligne).

## 11. Décisions prises après la relecture (18/10/2026)
- **Prix au kilo des listes** : prix du voyageur + « + frais de service Yadony (min. 1 €) » (propriétaire).
- **Bon de parrainage** : appliqué après le minimum, pour qu'il fasse toujours gagner quelque chose (propriétaire).
- **Mise à jour forcée** : `min_supported_build` est monté avant la date d'effet de la première grille (propriétaire).
- **Négociation de trajet** : les réductions personnelles sont figées à l'ouverture du fil et inversées avec toute la chaîne (rédacteur, §4.3).
- **Code promo sans effet** : non consommé et signalé (rédacteur, §4.2).
