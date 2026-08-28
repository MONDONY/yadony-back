# Lot 4 — Mise en avant des voyageurs PRO — Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development`. Les étapes utilisent la syntaxe case à cocher (`- [ ]`).

**Objectif :** couvrir de tests la mise en avant des voyageurs PRO dans la recherche d'annonces, aujourd'hui livrée en production sans aucun test, et documenter son écart au spec.

## Pourquoi ce lot n'est pas celui qui était prévu

Le spec prévoyait « un bonus de score **additif** appliqué aux voyageurs PRO dans le classement, plutôt qu'un remplacement du score de pertinence, pour ne pas dégrader la pertinence pour l'expéditeur ».

L'exploration du code a montré que **la mise en avant existe déjà, et sous une forme plus agressive** : `travelerIsPro` est la **clé de tri primaire absolue** dans les deux branches de `AnnouncementService.searchAnnouncements`. La dernière annonce PRO d'un corridor passe donc devant la première annonce non-PRO, quels que soient son prix et sa date — exactement la dégradation de pertinence que le spec voulait éviter.

Trois options ont été pesées : garder le tri actuel, le remplacer par un vrai bonus additif, ou n'appliquer la priorité PRO que dans une bande de pertinence. **La décision retenue est de garder le tri actuel.** La promesse produit est tenue, plus fortement que prévu, et la remplacer imposerait de construire un score de classement inexistant, de faire basculer la pagination SQL vers un chargement mémoire complet, et de gérer l'invalidation d'un cache dont la clé ignore le réglage.

Ce lot se réduit donc à ce qui manque réellement : **des tests**. Aucune référence à `travelerIsPro`, `proFirst` ou `buildSort` n'existe dans toute la suite de tests — le comportement est en production, non couvert.

**Tech :** Spring Boot 3.4, Java 21, JUnit 5, Mockito, AssertJ. **Aucune migration, aucun changement de code de production.**

## Contraintes globales

- Package racine : `com.yadony.api`.
- Ce lot **ne modifie aucun code de production**. S'il apparaît nécessaire d'en modifier, c'est qu'un test a révélé un défaut : le signaler plutôt que de le corriger silencieusement.
- Tests : `@MockitoBean` et non `@MockBean`. Pas de `@InjectMocks`. AssertJ. `ReflectionTestUtils.setField(entity, "id", uuid)` pour un id hérité de `BaseEntity`.
- Profil de test : H2, Flyway désactivé, `ddl-auto: create`.
- **Jamais deux commandes Maven en parallèle** : vérifier avec `pgrep -f plexus.classworlds.launcher.Launcher`. Un « Exit 134 » ou SIGABRT avec 0 échec est un manque de mémoire JVM.
- **`target/jacoco.exec` est cumulatif** : pour mesurer, le supprimer puis lancer `test` et `jacoco:report` dans une seule commande.

## Ce qu'il faut savoir sur le code existant

**Deux branches de tri, deux mécanismes.**

La branche « tri par date », qui est le défaut, trie en **SQL** via `AnnouncementService.buildSort` :

```java
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

La branche « tri par prix » trie en **mémoire**, parce que le prix doit être converti dans la devise du lecteur avant comparaison :

```java
            Comparator<AnnouncementEntity> comparator = Comparator
                    .comparing(AnnouncementEntity::isTravelerIsPro).reversed()
                    .thenComparing(byConvertedPrice)
                    .thenComparing(AnnouncementEntity::getId);
```

**La colonne est dénormalisée.** `announcements.traveler_is_pro` est maintenue par `AnnouncementService.onUserProStatusChanged`, qui appelle `AnnouncementRepository.updateTravelerProStatus`. Cette requête ne met à jour que les annonces en statut `ACTIVE` ou `FULL`.

**Deux sources pour la même vérité.** Le tri lit la colonne dénormalisée `announcement.travelerIsPro`, tandis que `AnnouncementSearchMapper` lit `UserEntity.isProAccount()` en direct pour afficher le badge. Les deux peuvent diverger sur une annonce dont le statut n'est ni `ACTIVE` ni `FULL`. Ce lot documente ce constat, il ne le corrige pas.

---

## Task 1 : Couvrir la mise en avant PRO dans les deux branches de tri

**Fichiers :**
- Test : `src/test/java/com/yadony/api/matching/AnnouncementProPriorityTest.java`
- Vérifier : `src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java`

**Interfaces :**
- Consomme : `AnnouncementService.searchAnnouncements(...)`, `AnnouncementEntity.isTravelerIsPro()`
- Produit : rien — ce lot n'ajoute aucun code de production

### Ce que les tests doivent prouver

Chaque test doit être **discriminant** : il doit échouer si la priorité PRO était retirée du comparateur ou du `Sort`. Un test où toutes les annonces partagent le même statut PRO ne prouve rien — c'est précisément le défaut du seul test d'ordre existant.

**Branche « tri par prix », en mémoire.** Un jeu où l'annonce PRO est la **plus chère** : elle doit malgré tout ressortir en premier. C'est la démonstration que la priorité PRO prime sur le critère demandé par l'utilisateur, et non l'inverse.

**Branche « tri par date », en SQL.** Vérifier que le `Sort` produit place bien `travelerIsPro` en tête, en descendant, avant le critère secondaire. Le tri s'exécutant en base, un test unitaire sur le `Sort` construit est plus fiable qu'un test qui dépendrait de l'ordre rendu par H2.

**Départage à statut PRO égal.** Entre deux annonces PRO, le critère demandé — prix ou date — doit s'appliquer normalement. Sans ce test, un comparateur qui ignorerait le critère secondaire passerait inaperçu.

**Stabilité de l'ordre.** Le comparateur de la branche prix se termine par `thenComparing(getId)`. Deux annonces identiques sur tous les critères doivent donc sortir dans un ordre stable d'un appel à l'autre. C'est ce qui évite qu'une même annonce apparaisse deux fois, ou disparaisse, entre deux pages.

- [ ] **Étape 1 : Lire le test existant et comprendre pourquoi il ne prouve rien**

Ouvrir `src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java` et localiser le test assertant `containsExactly("EUR", "XOF", "USD")` sur les devises.

Ce test passe aujourd'hui uniquement parce que ses trois annonces partagent le même voyageur, donc le même `travelerIsPro` : l'égalité neutralise la clé primaire et laisse le critère secondaire décider. Il n'est pas faux, mais il n'exerce pas la priorité PRO.

Noter dans le rapport s'il faudrait le renommer ou le commenter pour que sa portée réelle soit lisible. **Ne pas le modifier** sans le signaler.

- [ ] **Étape 2 : Écrire les tests**

Créer `src/test/java/com/yadony/api/matching/AnnouncementProPriorityTest.java`, en calquant la structure et les fabriques d'entités sur `AnnouncementServiceTest` — mocks de repositories, `ReflectionTestUtils` pour les identifiants, mêmes dépendances au constructeur.

Les quatre comportements à couvrir sont décrits ci-dessus. Nommer chaque test d'après le comportement, en français, dans le style du dépôt : par exemple « une annonce PRO plus chère passe devant une annonce standard moins chère ».

Pour la branche « date », si `buildSort` est privée, la tester à travers l'appel public en capturant le `Pageable` transmis au repository avec un `ArgumentCaptor`, et asserter l'ordre du `Sort` qu'il porte. Ne pas rendre la méthode visible pour les besoins du test.

- [ ] **Étape 3 : Lancer les tests**

Commande : `./mvnw test -Dtest=AnnouncementProPriorityTest`
Attendu : SUCCÈS. Le code de production existe déjà, les tests doivent donc passer du premier coup.

**Si l'un échoue, ne le corrige pas en l'adaptant** : cela signifierait que le comportement réel diffère de ce que ce plan décrit, ce qui serait l'information la plus précieuse du lot. Le signaler.

- [ ] **Étape 4 : Vérifier que les tests sont discriminants**

Un test qui passe alors que le comportement n'existe pas ne prouve rien. Vérifier, par lecture et sans modifier le code de production, que chaque test échouerait si la priorité PRO était retirée — c'est-à-dire que chaque jeu de données comporte bien des annonces de statut PRO **différent**, et que l'ordre attendu ne serait pas obtenu par le seul critère secondaire.

Documenter ce raisonnement dans le rapport, test par test.

- [ ] **Étape 5 : Lancer la suite complète**

Commande : `./mvnw test`
Attendu : SUCCÈS. Aucun code de production n'ayant changé, aucun test existant ne doit casser. Rapporter le résultat réel, y compris en cas d'échec.

> Un rapport surefire périmé de `com.yadony.api.automation.JsonDebugTest` traîne, source supprimée : ce n'est pas un échec.

- [ ] **Étape 6 : Commit**

```bash
git add src/test/java/com/yadony/api/matching/AnnouncementProPriorityTest.java
git commit -m "test(matching): couvre la mise en avant des voyageurs PRO"
```

---

## Vérification de fin de lot

- [ ] `./mvnw test` passe intégralement
- [ ] Chaque test comporte des annonces de statut PRO différent, et échouerait si la priorité était retirée
- [ ] Aucun code de production modifié
- [ ] **Documentation de story** : `docs/stories-done/story-billing-lot4-mise-en-avant-pro.md`, selon le gabarit du `CLAUDE.md`, consignant l'écart au spec et la décision de le maintenir

## Hors périmètre

- Remplacer le tri binaire par un bonus additif : écarté, avec ses raisons, ci-dessus
- Corriger la double source de vérité entre la colonne dénormalisée et `UserEntity.isProAccount()` : constat documenté, correction non entreprise
- Le classement des demandes de colis par `MatchingService.computeMatchScore` : il classe des demandes pour un voyageur donné, tous les candidats partageant le même voyageur. Un bonus PRO n'y aurait aucun sens
- `RematchService`, qui propose des annonces alternatives après annulation, ignore la priorité PRO. Incohérence réelle avec la recherche, laissée hors périmètre et signalée dans la story
