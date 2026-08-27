# Story — Lot 4 : mise en avant des voyageurs PRO dans la recherche (Backend)

**Date :** 2026-08-27
**Status :** ✅ Complète

## Résumé

Quatrième et dernier lot de la série `pro-saas-abonnement`, après `story-billing-lot1-fondation.md`
(machine à états), `story-billing-lot2-stripe.md` (paiement) et `story-billing-lot3-admin-grant.md`
(octroi administrateur). Ce lot est le seul de la série à **ne construire aucun code de
production**. L'exploration a montré que la mise en avant PRO dans la recherche d'annonces existait
déjà en production, sans aucun test, et sous une forme plus agressive que ce que décrivait le spec.
Le lot se limite donc à couvrir ce comportement de tests et à corriger le spec pour qu'il décrive la
réalité plutôt qu'une intention jamais construite.

## Fichiers créés

- `src/test/java/com/yadony/api/matching/AnnouncementProPriorityTest.java` — 4 tests couvrant la
  priorité PRO dans les deux branches de tri de `AnnouncementService.searchAnnouncements`

## Fichiers modifiés

- `docs/superpowers/specs/2026-08-27-pro-saas-abonnement-design.md` — section 7 (« Boost dans le
  matching ») amendée : un encart daté du 2026-08-27 acte l'écart entre l'intention initiale (bonus
  additif) et le comportement réel en production (tri binaire). Le paragraphe original est conservé
  en dessous, à titre d'archive de l'intention de départ.

Aucun fichier de `src/main/java` n'a été modifié. Aucune migration Flyway.

## Comment ça fonctionne (pour la maintenance)

Cette section décrit du code préexistant, non écrit par ce lot — voir « Ce qui rend cette story
particulière » plus bas.

### Vue d'ensemble du flux

1. Un expéditeur appelle la recherche d'annonces (`AnnouncementService.searchAnnouncements`), avec
   ou sans `sortBy`/`sortDir`.
2. Le service construit un `Specification<AnnouncementEntity>` à partir des filtres (ville, dates,
   prix, etc.), indépendamment du tri.
3. Selon `sortBy`, deux chemins totalement distincts s'exécutent (voir « Deux mécanismes distincts »
   ci-dessous) — mais dans les deux cas, **`travelerIsPro` est toujours le premier critère de tri,
   avant tout critère demandé par l'utilisateur.**
4. Le résultat est mappé en `AnnouncementSearchResponse` via `AnnouncementSearchMapper`, paginé, et
   mis en cache (`@Cacheable("announcements-search")`).

### Points d'entrée API

Aucun nouvel endpoint. Le comportement documenté ici est exercé par la recherche d'annonces
existante (`AnnouncementController`, non modifié par ce lot), à travers
`AnnouncementService.searchAnnouncements(...)`.

### Entités JPA impliquées

- `AnnouncementEntity` → table `announcements`, colonne `traveler_is_pro BOOLEAN NOT NULL DEFAULT
  false` (`src/main/java/com/yadony/api/matching/AnnouncementEntity.java:143-144`). Dénormalisation
  du statut PRO du voyageur au moment de la publication/mise à jour de son statut — voir point 4
  ci-dessous.

### Logique métier critique

**1. La mise en avant existait déjà, plus agressive que le spec ne le prévoyait.** Le spec
(section 7, avant amendement) décrivait un **bonus de score additif** appliqué aux voyageurs PRO,
motivé explicitement par le souci de « ne pas dégrader la pertinence pour l'expéditeur ». La
réalité du code, vérifiée dans `AnnouncementService.searchAnnouncements` :

```java
// Branche "prix" (comparateur en mémoire), ~L289
Comparator<AnnouncementEntity> comparator = Comparator
        .comparing(AnnouncementEntity::isTravelerIsPro).reversed()
        .thenComparing(byConvertedPrice)
        .thenComparing(AnnouncementEntity::getId);
```

```java
// Branche "date" (Sort SQL), buildSort ~L408
private Sort buildSort(String sortBy, String sortDir) {
    Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
    Sort proFirst = Sort.by(Sort.Direction.DESC, "travelerIsPro");
    Sort secondary = switch (sortBy != null ? sortBy : "date") {
        case "price" -> Sort.by(direction, "pricePerKg");
        default -> Sort.by(direction, "departureDate");
    };
    return proFirst.and(secondary);
}
```

`travelerIsPro` n'est pas un bonus ajouté à un score : c'est la **clé de tri primaire absolue**,
dans les deux branches. La dernière annonce PRO d'un corridor passe devant la première annonce
non-PRO, quels que soient son prix et sa date. C'est exactement la dégradation de pertinence que le
spec disait vouloir éviter — sauf qu'elle est en production depuis avant l'écriture de ce spec.

**2. Pourquoi ce tri est conservé, plutôt que remplacé par le bonus additif prévu.** Trois options
ont été pesées (voir le plan) : garder le tri actuel, le remplacer par un vrai score additif, ou
n'appliquer la priorité PRO que dans une bande de pertinence proche. La décision retenue est de
garder le tri actuel, pour trois raisons cumulées :
- Un score de classement continu (le prérequis d'un bonus additif) **n'existe pas** dans ce
  service — il faudrait le construire de toutes pièces.
- La branche « prix » est déjà en mémoire (nécessaire pour la conversion multidevise), mais la
  branche « date », qui est le défaut, est en SQL avec pagination `Pageable` côté base. Un score
  additif calculé après conversion de devise imposerait de faire basculer cette branche aussi vers
  un chargement mémoire complet du jeu filtré, perdant la pagination SQL.
- Le résultat de `searchAnnouncements` est mis en cache
  (`@Cacheable(value = "announcements-search", key = "... #sortBy + '_' + #sortDir + ...")`) : la
  clé ne contient aucun réglage de bonus. Introduire un poids configurable nécessiterait de gérer
  l'invalidation de ce cache à chaque changement de réglage.

Le tout pour un gain de pertinence que le produit ne demandait pas explicitement — le tri binaire
tient déjà la promesse « les voyageurs PRO sont mis en avant », en plus fort que prévu. Décision
actée dans l'amendement du spec (section 7), pas seulement dans ce document.

**3. Deux mécanismes de tri distincts selon `sortBy`, à maintenir en parallèle.**
- **`sortBy=price`** : comparateur Java en mémoire
  (`searchAnnouncements`, ~L274-299). Nécessaire parce que le fil n'est plus cloisonné par devise
  (Tâche 10 d'un lot antérieur) : une liste mêlant EUR/XOF/USD triée sur `pricePerKg` brut n'aurait
  aucun sens, il faut convertir chaque prix dans la devise du lecteur
  (`convertedPricePerKgForSort`) avant de comparer. Cette branche charge tout le jeu filtré
  (`announcementRepository.findAll(spec)`, sans `Pageable`) et pagine manuellement en mémoire.
- **Tout autre `sortBy` (dont l'absence de `sortBy`, le défaut)** : `Sort` SQL construit par
  `buildSort`, exécuté par `announcementRepository.findAll(spec, sortedPageable)` — pagination SQL
  classique.

Quiconque touchera à l'un de ces deux chemins pour faire évoluer la mise en avant PRO (ou tout
autre critère de tri) devra penser à répliquer le changement dans l'autre : ils ne partagent aucun
code de comparaison.

**4. La colonne `traveler_is_pro` est dénormalisée, et sa mise à jour est partielle.** Elle existe
pour éviter une jointure vers `users` à chaque tri — un `ORDER BY` sur une colonne native de
`announcements` reste performant à l'échelle du fil de recherche, une jointure corrélée au statut
PRO d'un utilisateur externe le serait moins. Elle est maintenue par un listener d'événement :

```java
@EventListener
@Transactional
public void onUserProStatusChanged(UserProStatusChangedEvent event) {
    int updated = announcementRepository.updateTravelerProStatus(event.userId(), event.isPro());
    ...
}
```

qui délègue à une requête `@Modifying` explicitement bornée par statut
(`AnnouncementRepository.updateTravelerProStatus`, ~L126-130) :

```java
@Modifying
@Query("UPDATE AnnouncementEntity a SET a.travelerIsPro = :isPro " +
       "WHERE a.travelerId = :travelerId AND a.status IN " +
       "(com.yadony.api.matching.AnnouncementStatus.ACTIVE, com.yadony.api.matching.AnnouncementStatus.FULL)")
int updateTravelerProStatus(@Param("travelerId") UUID travelerId, @Param("isPro") boolean isPro);
```

Seules les annonces `ACTIVE` ou `FULL` sont mises à jour. Une annonce en `DRAFT`, `EXPIRED`,
`CANCELED` ou tout autre statut fermé ne reçoit jamais la bascule : sa colonne `traveler_is_pro`
gèle à sa dernière valeur connue. Ce n'est pas un défaut pour la recherche elle-même — seules les
annonces `ACTIVE` y apparaissent (`AnnouncementSpecification.hasStatus(ACTIVE)` dans
`searchAnnouncements`) — mais c'est la source directe de la divergence documentée au point suivant.

**5. Deux sources pour la même vérité, non unifiées.** Le tri lit la colonne dénormalisée
`announcement.travelerIsPro`. Le mapper qui construit le badge affiché à l'expéditeur lit en
revanche `UserEntity.isProAccount()` en direct, sans passer par la colonne :

```java
// AnnouncementSearchMapper.toSearchResponse, deux occurrences (~L88 et ~L153)
TravelerProfileDto profile = traveler != null
        ? new TravelerProfileDto(
                ...
                traveler.isKiloPro(),
                traveler.isProAccount(),   // ← lecture directe, pas travelerIsPro
                ...
        : null;
```

Les deux valeurs peuvent diverger : si une annonce n'est ni `ACTIVE` ni `FULL` au moment où le
voyageur perd (ou gagne) son statut PRO, sa colonne `traveler_is_pro` ne bouge pas alors que
`isProAccount()` reflète l'état courant. Dans le fil de recherche cela ne s'observe pas (seules les
`ACTIVE` y figurent, et une annonce `ACTIVE` est mise à jour par le listener), mais rien
n'interdit structurellement l'écart sur un autre point d'accès à l'entité. **Constat documenté par
ce lot, non corrigé** — corriger nécessiterait soit d'étendre la requête `updateTravelerProStatus`
à d'autres statuts, soit de faire lire le mapper depuis la colonne dénormalisée plutôt que depuis
l'entité utilisateur.

**6. Ce que les quatre tests de `AnnouncementProPriorityTest` protègent, et pourquoi ils sont
discriminants.** Le seul test d'ordre préexistant sur ce tri
(`AnnouncementServiceTest#searchAnnouncements_sortByPriceAsc_ordersByConvertedValueAcrossThreeCurrencies`)
construit ses trois annonces à partir du même voyageur : elles partagent donc le même
`travelerIsPro` (`false`), et l'ordre observé est entièrement produit par le critère secondaire
(la conversion multidevise). Ce test est valide pour ce qu'il couvre, mais **il continuerait de
passer à l'identique si la priorité PRO disparaissait du comparateur** — il ne l'exerce jamais.

C'est la leçon générale de ce lot : des tests écrits après coup, pour du code déjà en production,
passent forcément du premier coup — les quatre tests de ce lot sont passés au premier essai, sans
aucune correction du code de production. Cela ne prouve rien en soi. Leur seule valeur tient à ce
qu'ils échoueraient si le comportement disparaissait. D'où la règle appliquée à chacun : **chaque
jeu de données mélange des annonces de statut PRO différent**, choisies pour qu'un tri ignorant ce
statut produise un ordre différent de celui attendu. Sans cette précaution, l'égalité sur
`travelerIsPro` neutralise la clé primaire du comparateur et le test ne vérifie plus que le critère
secondaire — exactement le défaut du test préexistant.

Le plus parlant des quatre place l'annonce PRO comme **la plus chère** des deux
(`searchAnnouncements_sortByPriceAsc_proAnnouncementOutranksCheaperStandardOne` : PRO à 100 €/kg,
standard à 5 €/kg, tri prix croissant) : elle ressort quand même en tête. C'est la démonstration
directe que la priorité PRO prime sur le critère demandé par l'expéditeur, et non l'inverse — si un
tri ascendant classique s'appliquait seul, l'ordre serait strictement inversé.

Un autre (`searchAnnouncements_tieOnProStatusAndPrice_ordersDeterministicallyById`) est le seul à
protéger le départage final par identifiant (`thenComparing(AnnouncementEntity::getId)`). Deux
annonces PRO à prix converti identique sont volontairement renvoyées par le repository mock dans
l'ordre id-décroissant ; l'assertion attend l'ordre id-croissant. Comme `Stream.sorted` est un tri
stable, seul ce dernier maillon du comparateur peut produire l'inversion attendue — sans lui,
l'ordre d'entrée du mock resterait inchangé et le test échouerait. Cette garantie est ce qui évite
qu'une même annonce apparaisse deux fois, ou disparaisse, entre deux pages consécutives d'un
résultat autrement à égalité parfaite.

Le troisième et le quatrième couvrent respectivement le départage à statut PRO égal côté prix
(deux annonces PRO à prix différents doivent se départager normalement entre elles, tout en restant
devant une annonce standard moins chère) et la construction du `Sort` SQL de la branche « date »
(capturé via `ArgumentCaptor<Pageable>`, `buildSort` étant privée et volontairement non exposée
pour le test).

### Events Spring publiés / écoutés

Aucun nouvel événement. Ce lot ne fait qu'exercer en test l'écouteur déjà existant :
`AnnouncementService.onUserProStatusChanged` écoute `UserProStatusChangedEvent` (publié par
`ProAccessSynchronizer`, lots 1-3) et appelle `AnnouncementRepository.updateTravelerProStatus`.

### Pièges et points d'attention

- **Ne jamais modifier un seul des deux chemins de tri sans l'autre.** La branche « prix » et la
  branche « date » n'ont aucun code de comparaison en commun (comparateur Java vs. `Sort` Spring
  Data) ; toute évolution de la priorité PRO (retrait, pondération, bande de pertinence) doit être
  répliquée dans les deux, sous peine d'incohérence entre les deux modes de tri exposés au client.
- **`buildSort` est privée et le reste** : le test de la branche « date » passe par
  `ArgumentCaptor<Pageable>` sur `announcementRepository.findAll(spec, pageable)`, pas par un appel
  direct à la méthode. Ne pas la rendre `package-private`/publique pour faciliter un futur test —
  ce n'était pas nécessaire ici.
- **Un test d'ordre qui passe ne prouve rien sans un jeu de données à statuts PRO différents.**
  Avant d'ajouter un nouveau test de tri sur `AnnouncementService`, vérifier qu'au moins deux
  annonces du jeu ont des `travelerIsPro` différents, sous peine de reproduire le défaut du test
  préexistant décrit au point 6.
- **La colonne `traveler_is_pro` ne se resynchronise jamais pour une annonce fermée.** Si un futur
  besoin exige que cette colonne soit exacte y compris hors `ACTIVE`/`FULL`, il faudra étendre
  `updateTravelerProStatus` (ou accepter le décalage documenté au point 5).
- **`AnnouncementProPriorityTest` duplique `buildAnnouncement`/`buildTraveler`/`setId` de
  `AnnouncementServiceTest`** plutôt que de les réutiliser : ces méthodes sont `private` dans la
  classe d'origine. Rien à corriger, mais à savoir avant de dupliquer une troisième fois un jour —
  un éventuel fichier utilitaire de fixtures partagées serait alors justifié.

## Ce qui reste hors périmètre, et pourquoi

- **`MatchingService.computeMatchScore`** classe des demandes de colis pour un voyageur donné :
  tous les candidats comparés partagent le même voyageur, donc le même statut PRO. Un bonus ou une
  priorité PRO n'y aurait structurellement aucun effet ni aucun sens.
- **`RematchService`** (`src/main/java/com/yadony/api/cancellation/RematchService.java`), qui
  propose des annonces alternatives à un expéditeur après l'annulation d'un trajet, **ignore
  entièrement la priorité PRO** alors qu'il classe lui aussi des annonces vues par un expéditeur —
  exactement le même type de classement que `searchAnnouncements`. C'est une incohérence réelle
  entre les deux points d'entrée : un expéditeur peut voir un ordre « PRO d'abord » en recherche
  normale et un ordre sans cette priorité dans ses suggestions de rematch. Laissée hors périmètre
  de ce lot, faute de mandat pour l'étendre ; à signaler comme candidat pour un lot ultérieur.
- **La double source de vérité entre `announcement.travelerIsPro` et `UserEntity.isProAccount()`**
  (point 5 ci-dessus) : constat documenté, correction non entreprise.

## Critères d'acceptation couverts

- [x] La mise en avant des voyageurs PRO dans la recherche d'annonces est couverte par des tests
      discriminants dans les deux branches de tri (`AnnouncementProPriorityTest`, 4 tests).
- [x] L'écart entre le spec (bonus additif) et le comportement réel (tri binaire, clé primaire) est
      documenté et la décision de conserver le comportement réel est actée dans le spec amendé
      (`docs/superpowers/specs/2026-08-27-pro-saas-abonnement-design.md`, section 7).
- [x] Aucun code de production modifié — vérifié par `git status`/diff avant commit.
- [x] Aucune régression sur la suite existante.

## Tests

- `./mvnw test -Dtest=AnnouncementProPriorityTest` → 4/4 verts au premier essai, sans aucune
  correction de code de production (voir `.superpowers/sdd/2026-08-27-lot4-mise-en-avant-pro/task-1-report.md`).
- `./mvnw test` (suite complète) → **4333 tests, 0 échec, 0 erreur, 7 ignorés, BUILD SUCCESS**.
- Aucune mesure de couverture JaCoCo dédiée à ce lot : aucun code de production n'a été ajouté ou
  modifié, la couverture de `AnnouncementService` progresse mécaniquement par l'ajout des 4
  nouveaux tests sans qu'un delta ciblé ait de sens à isoler.
- Tests créés : `AnnouncementProPriorityTest` (4 tests). Aucun test existant modifié —
  `AnnouncementServiceTest` a été lu (voir point 6) mais délibérément laissé intact, conformément
  au plan.

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Traitement de l'écart au spec | Documenter et conserver le comportement réel (tri binaire) | Implémenter le bonus additif prévu par le spec initial | Score de classement inexistant à construire, bascule de la pagination SQL vers un chargement mémoire complet pour la branche « date », invalidation de cache à gérer — pour un gain de pertinence non demandé par le produit |
| Portée du lot | Couvrir de tests uniquement, zéro changement de code de production | Corriger au passage la double source de vérité (point 5) ou étendre `RematchService` | Ces deux points ne sont pas ce que ce lot a été mandaté à faire ; les corriger silencieusement aurait mélangé une découverte documentaire avec un changement de comportement non demandé |
| Test préexistant à l'ordre non discriminant | Signalé, non modifié | Le renommer ou l'étendre pour couvrir aussi la priorité PRO | Le plan demandait explicitement de ne pas le modifier sans le signaler ; toute modification y compris cosmétique sort du périmètre « zéro changement de code testé par ce test » |
| Emplacement des nouveaux tests | Nouveau fichier `AnnouncementProPriorityTest` | Ajouter les 4 tests dans `AnnouncementServiceTest` | Isole une suite entièrement dédiée à un seul comportement, plus facile à retrouver et à faire évoluer sans alourdir un fichier de tests déjà volumineux |
