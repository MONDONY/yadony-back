# Classement des annonces de trajet (recherche expéditeur)

Date : 2026-10-21. Statut : brouillon à valider par le propriétaire.

## Intention

Aujourd'hui `AnnouncementService.searchAnnouncements` filtre en dur (ville de départ, ville d'arrivée, dates, kg, prix, transport, etc.) puis trie « pro d'abord », puis par date de départ ou prix (`buildSort`). Aucun signal de qualité n'entre dans l'ordre.

Objectif : que l'expéditeur voie en premier les annonces qui correspondent exactement à sa recherche, ordonnées par un score interne de qualité du voyageur (fiabilité, note, expérience, moyen de paiement, fraîcheur). Quand peu d'annonces correspondent exactement, lui montrer d'autres annonces du même trajet en lui disant clairement qu'elles ne correspondent pas exactement.

## Objectifs

1. Recherche **stricte** sur la ville de départ et la ville d'arrivée : une annonce sur un autre trajet n'apparaît jamais.
2. Tous les autres critères de la recherche sont **souples**, sauf `kycVerifiedOnly` et `maxPricePerKg` qui restent stricts (voir Décisions) : date (fenêtre `departureDateFrom`/`departureDateTo`), kg demandés (`minAvailableKg`), prix max, mode de transport, note minimale, type de contenu, rayon de retrait, `urgent`, `weekendOnly`, `kiloProOnly`. Une annonce qui en rate au moins un n'est plus écartée, elle bascule dans le bloc 2.
3. **Deux blocs** dans la réponse :
   - Bloc 1 « Correspond à ta recherche » : respecte tous les critères.
   - Bloc 2 « Autres annonces sur ce trajet » : même départ, même arrivée, au moins un critère souple non respecté. Affiché **seulement si le bloc 1 compte moins de 7 annonces** (seuil décidé par le propriétaire ; 7 est le nombre d'annonces exactes en dessous duquel on affiche le bloc 2).
4. Chaque annonce du bloc 2 porte la **liste des critères non respectés**, lisible par l'utilisateur (ex. « Départ le 12/11 au lieu du 10/11 », « 5 kg disponibles au lieu de 8 »). Un message d'en-tête indique que ces annonces ne correspondent pas exactement.
5. Dans chaque bloc, l'ordre est donné par un **score interne caché**. Il n'est jamais exposé (ni valeur, ni poids, ni détail par critère) à aucun utilisateur.

## Non-objectifs

- Pas de score visible, pas de badge « fiabilité » public. La fiabilité sert uniquement à ordonner.
- Pas d'assouplissement des villes (pas de ville voisine).
- Pas de refonte du tri manuel proposé à l'utilisateur dans les écrans autres que cette recherche.

## Anti-objectifs (échec même si ça marche techniquement)

- Un utilisateur peut déduire le score ou la fiabilité d'un voyageur depuis l'API ou l'app.
- Une annonce du bloc 2 apparaît sans explication de ce qui ne correspond pas.
- Une annonce sur un autre trajet est montrée.

## Score interne

Calculé par annonce, de 0 à 100, somme pondérée de cinq composantes (chacune de 0 à 1), poids ramenés à 100 :

| Composante | Poids de départ | Calcul |
|---|---|---|
| Fiabilité | 30 | `(envois + 4) / (envois + 5 + 4 × incidents)` avec `incidents = annulations + 2 × no-shows` du voyageur (`cancellationCount`, `noShowCount`). Voyageur sans historique : 0,8. |
| Note | 25 | Moyenne bayésienne `(averageRating × ratingCount + 4 × 5) / (ratingCount + 5)`, ramenée de [3 ; 5] à [0 ; 1] (borné). |
| Nombre d'envois réussis | 20 | `min(1, ln(1 + envois) / ln(51))` (plafond à 50 envois). |
| Fraîcheur et départ proche | 15 | Moyenne de `clamp(1 − joursAvantDépart/30)` et `clamp(1 − âgeAnnonceEnJours/30)`. |
| Paiement en ligne | 10 | 1 si `acceptedPaymentMethods` contient `STRIPE` (carte) ou `MOBILE_MONEY` ; 0 si espèces seules (`CASH`). Carte et mobile money sont au même niveau. |

Le KYC n'entre plus dans le score : un voyageur sans KYC reste affiché. Le filtre explicite `kycVerifiedOnly` de la recherche est conservé, il n'influence pas le score.

Ces formules et poids viennent d'une simulation validée en discussion ; les poids doivent être réglables (configuration, pas en dur) et les formules ajustables sans changer le contrat d'API.

## Contraintes

- Tri « pro d'abord » (`travelerIsPro`) : supprimé comme clé primaire ; au plus un petit bonus dans le score (à la main du builder, non décidé).
- Le score est interne : jamais dans les DTO (`AnnouncementSearchResponse` et dérivés), ni dans les logs de niveau INFO au-dessus, ni dans PostHog côté client.
- Nombre d'envois réussis : aucun compteur de colis livrés n'existe aujourd'hui sur le voyageur (seul `totalTripsCompleted` via `TravelerStatsService`). Il faut en créer un source (compteur dénormalisé mis à jour à la livraison, ou comptage des bids livrés).
- Contrat d'API modifié : la réponse expose pour chaque annonce son bloc (exact ou non) et la liste des critères non respectés ; la pagination doit rester cohérente (bloc 1 d'abord, puis bloc 2 quand applicable).
- Contrat entre dépôts : PR back d'abord, PR app ensuite, citant la PR back ; l'app tolère l'ancien contrat tant que la prod back n'est pas déployée. Fiches assistant (`assistant/kb/{fr,en}`) à mettre à jour si le parcours de recherche visible change.
- Règles du projet : migrations Flyway V(n+1) uniquement, soft delete, tests avec couverture ≥ 90 %, pas de Redis, Caffeine pour le cache.

## Laissé au builder

- Où calculer le score (SQL, en mémoire sur le lot filtré, colonne dénormalisée) et comment paginer sur les deux blocs, selon les contraintes de performance.
- Le bonus éventuel du tri « pro d'abord ».
- Le format exact des critères non respectés dans l'API (codes + valeurs) et leur traduction dans l'app (fr/en).
- Le détail des seuils d'arrondi et la configuration des poids (propriété, table admin).

## Choix faits après relecture (mes décisions, à vérifier)

- **Seuil de 7** : porte sur le nombre **total** d'annonces exactes de la recherche (pas sur la page). Le bloc 2 est alors servi **en entier jusqu'à 20 annonces maximum** (plafond non paginé). Au-delà de 6 exactes, pas de bloc 2.
- **Pagination** : le bloc 1 est paginé normalement ; le bloc 2 est renvoyé dans un champ séparé de la réponse (deux listes, pas une liste unique à marqueur), uniquement sur la première page.
- **Écarts affichés** : on liste tous les critères non respectés. Dans le bloc 2, ordre = moins d'écarts d'abord, puis score. Pas de limite de tolérance.
- **Nombre d'envois** = colis (bids) livrés, via un compteur dénormalisé sur l'utilisateur voyageur avec migration V(n+1) et rattrapage des données existantes. `cancellationCount`, `noShowCount`, `averageRating`, `ratingCount` existent déjà sur `UserEntity`.
- **Tri manuel** (date, prix) : il remplace le score à l'intérieur de chaque bloc, les blocs restent séparés. Tri par défaut : le score.
- **Voyageur sans historique** : pas de bonus de découverte (la fiabilité de départ de 0,8 et la note bayésienne suffisent).
- **Seuil de 7 et poids** : même configuration réglable au déploiement (propriété), pas d'écran admin dans ce lot.
- **Villes** : égalité insensible à la casse comme aujourd'hui (`hasDepartureCity`), sans pays.
- **Score caché** : l'ordre résultant est acceptable ; le score n'apparaît dans aucune réponse d'API ni événement d'analytics.

## Décisions du propriétaire (21/10)

- **Filtres stricts** (restent des filtres durs, jamais assouplis) : `kycVerifiedOnly` et `maxPricePerKg`, en plus des villes. **Filtres souples** : dates, kg, mode de transport, note minimale, type de contenu, rayon de retrait, `weekendOnly`, `urgent`, `kiloProOnly`.
- **`senderIncidentCount` retiré du profil public** (`ProfilePublicResponse`, alimenté par `ProfilePublicService` via `senderReliabilityIncidentCount()`), ainsi que de tout DTO public qui le sert (ex. `PackageRequestSearchResponse` / `PackageRequestSearchMapper`). La donnée reste en base et peut servir de signal interne. Contrat modifié : PR back d'abord, PR app ensuite qui supprime l'affichage et tolère l'ancien contrat (champ absent) ; fiches assistant à relire si elles mentionnent cet affichage.
