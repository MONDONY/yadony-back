# Story — Recherche en langage naturel (Backend)

**Date:** 2026-08-19
**Status:** ✅ Complète

## Résumé

Ajout de l'endpoint `POST /search/parse`, qui transforme une phrase française libre
(« 20 kilos à Bamako en mars ») en filtres exploitables par les écrans Trajets et
Colis. L'implémentation est un package autonome `com.yadony.api.search` : un
tokeniseur découpe la phrase, cinq passes de reconnaissance pures (sans Spring)
consomment tour à tour les tokens qu'elles comprennent, puis un orchestrateur les
enchaîne et mappe le résultat vers un DTO de filtres partageant exactement les noms
de paramètres de `GET /announcements`. Ce qui reste ambigu (prix vague, date vague,
ville inconnue ou ambiguë) n'est jamais deviné : c'est renvoyé au client sous forme
de question (`unresolved`).

## Fichiers créés

**Socle (types partagés, aucune dépendance Spring) :**
- `src/main/java/com/yadony/api/search/SearchMode.java` — `enum { TRIPS, PACKAGES }`, bascule l'interprétation du poids.
- `src/main/java/com/yadony/api/search/UnresolvedKind.java` — `enum { PRICE_VAGUE, DATE_VAGUE, CITY_UNKNOWN, CITY_AMBIGUOUS }`.
- `src/main/java/com/yadony/api/search/Token.java` — un mot, sa forme normalisée, ses bornes dans le texte d'origine.
- `src/main/java/com/yadony/api/search/SearchTokenizer.java` — phrase → `List<Token>`, retire les formules d'intention (« je veux envoyer »…).
- `src/main/java/com/yadony/api/search/ParseState.java` — accumulateur d'état partagé par toutes les passes (tokens consommés, valeurs trouvées, `recognized`, `unresolved`).

**Passes de reconnaissance, dans l'ordre d'exécution imposé :**
- `src/main/java/com/yadony/api/search/QuantityParser.java` — poids et prix (passe 1).
- `src/main/java/com/yadony/api/search/DateExpressionParser.java` — expressions de date françaises (passe 2).
- `src/main/java/com/yadony/api/search/FlagParser.java` — drapeaux booléens et mode de transport (passe 3).
- `src/main/java/com/yadony/api/search/ContentTypeParser.java` — catégories de contenu (passe 4).
- `src/main/java/com/yadony/api/search/SearchCityRepository.java` — lecture native `pg_trgm` sur `cities`, sans passer par `CityService`.
- `src/main/java/com/yadony/api/search/CityLexicon.java` — bean Spring, résolution floue des villes et de leur direction (passe 5, en dernier).

**Orchestration et endpoint :**
- `src/main/java/com/yadony/api/search/SearchQueryParser.java` — bean `@Service`, enchaîne les 5 passes et mappe `ParseState` vers `SearchParseResponse`.
- `src/main/java/com/yadony/api/search/SearchParseController.java` — `POST /search/parse`.
- `src/main/java/com/yadony/api/search/dto/SearchParseRequest.java` — corps de requête (`text`, `mode`, `today` facultatif).
- `src/main/java/com/yadony/api/search/dto/SearchParseResponse.java` — enveloppe de réponse (`filters`, `recognized`, `unresolved`, `ignored`).
- `src/main/java/com/yadony/api/search/dto/ParsedFilters.java` — 14 champs nullable, `@JsonInclude(NON_NULL)`, noms alignés sur `GET /announcements`.
- `src/main/java/com/yadony/api/search/dto/RecognizedField.java` — `(field, value, span, confidence)`, pour le surlignage côté client.
- `src/main/java/com/yadony/api/search/dto/UnresolvedItem.java` — `(kind, phrase, options)`, transformé en question côté client.

**Tests (91 au total sur le package) :**
- `SearchTokenizerTest`, `ParseStateTest`, `ParsedFiltersTest` — socle.
- `QuantityParserTest`, `DateExpressionParserTest`, `FlagParserTest`, `ContentTypeParserTest`, `CityLexiconTest` — une passe chacun.
- `SearchQueryParserCorpusTest` — corpus de phrases réalistes, test paramétré de bout en bout.
- `SearchParseControllerIT` — `@SpringBootTest` + `MockMvc`, l'endpoint HTTP.

## Fichiers modifiés

Aucun. Le package `search` est entièrement nouveau ; aucune classe existante
(`SecurityConfig`, `GlobalExceptionHandler`, `ContentCatalog`…) n'a eu besoin d'être
touchée — l'endpoint tombe naturellement sous `.anyRequest().authenticated()` et
`GlobalExceptionHandler.handleValidation` renvoyait déjà 422 pour les violations
Bean Validation.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. Le client (app Flutter, écrans Trajets/Colis) envoie
   `POST /search/parse { text, mode, today? }` avec un token Firebase valide.
2. `SearchParseController.parse` valide le corps (`@Valid`), résout `today` (celui de
   la requête si fourni, sinon `LocalDate.now()`), et délègue tout à
   `SearchQueryParser.parse(text, mode, today)`.
3. `SearchQueryParser` tokenise la phrase puis exécute séquentiellement les 5 passes
   sur un `ParseState` partagé. Chaque passe ne regarde que les tokens qu'aucune
   autre n'a encore consommés (`state.isConsumed(i)`).
4. Le `ParseState` final est mappé vers `ParsedFilters` (14 champs typés) et empaqueté
   dans `SearchParseResponse` avec `recognized` (pour le surlignage), `unresolved`
   (pour les questions de clarification) et `ignored` (mots que rien n'a réclamés,
   diagnostic uniquement).
5. Rien n'est persisté : ni la phrase, ni le résultat, ni une entrée `audit_log`.

### Points d'entrée API

- `POST /search/parse` — traduit une phrase libre en filtres de recherche.
  Rôles autorisés : `SENDER`, `TRAVELER` (`@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")`).
  Corps : `{ "text": "20 kilos à Bamako en mars", "mode": "TRIPS", "today": null }`.
  Réponse 200 : `SearchParseResponse` (champs `null` de `filters` omis grâce à
  `@JsonInclude(NON_NULL)`).
  Réponse 422 (RFC 7807) : `text` vide/blanc ou > 200 caractères, ou `mode` manquant.
  Réponse 401 : pas de token Firebase valide.

### Entités JPA impliquées

Aucune entité n'est créée ni modifiée. `SearchCityRepository` lit `CityEntity`
(package `city`, préexistant) par une requête SQL native `pg_trgm`, sans passer par
`CityService` — conformément à la règle du repo qui interdit l'injection de service
entre packages (même contournement qu'`AnnouncementSpecification` qui lit
`UserEntity` en sous-requête).

### Logique métier critique

- **L'ordre des 5 passes n'est pas arbitraire.** `QuantityParser` → `DateExpressionParser`
  → `FlagParser` → `ContentTypeParser` → `CityLexicon`. Les villes passent
  **volontairement en dernier** et ne travaillent que sur le reliquat de tokens :
  plusieurs noms de mois (« mars ») sont aussi des noms de commune réels, et les
  trigrammes `pg_trgm` matchent volontiers l'un sur l'autre. Si la passe villes
  remontait en tête, « mars » dans « 20 kilos à Bamako en mars » deviendrait un
  candidat ville avant que `DateExpressionParser` ait pu le consommer comme date.
- **Le poids s'inverse selon le mode** (`QuantityParser.putWeight`). En mode
  `TRIPS`, l'appelant est un expéditeur qui décrit son colis : le trajet doit
  disposer d'*au moins* ce poids → `minAvailableKg`. En mode `PACKAGES`, l'appelant
  est un voyageur qui décrit sa capacité offerte : la même phrase borne le poids des
  colis proposés → `maxWeight`. La même saisie (« 20 kilos ») produit donc deux
  champs différents selon qui pose la question.
- **Un mois nommé sans année désigne sa prochaine occurrence**, jamais le mois
  révolu (`DateExpressionParser.nextOccurrence`) : le mois demandé reste sur l'année
  courante s'il n'est pas encore fini, sinon il passe à l'année suivante. Sans cette
  règle, « mars » cherché en août porterait sur mars *passé* et ne renverrait jamais
  rien d'utile à l'utilisateur.
- **Le `contentType` produit est le `label` du catalogue, jamais le `code`**
  (`ContentTypeParser`). `announcement_accepted_types` et `bids.content_category`
  persistent le label (« Vêtements & tissus »), pas la constante interne
  (`VETEMENTS`) : produire le code donnerait un filtre qui ne matche jamais aucune
  annonce. `CODE_TO_LABEL` est dérivé de `ContentCatalog.CATEGORIES` pour ne jamais
  diverger si le catalogue change.
- **Rien n'est deviné quand c'est ambigu.** Un prix vague (« pas trop cher »), une
  date vague (« bientôt »), une ville inconnue ou deux villes à égalité de
  similarité (`AMBIGUITY_MARGIN = 0.05`) ne produisent aucun filtre : ils vont dans
  `unresolved`, à charge pour le client de poser la question plutôt que de biaiser
  silencieusement la recherche.
- **Aucune migration Flyway, volontairement.** `pg_trgm` et la table `cities` (avec
  `idx_cities_name_trigram`) existent déjà depuis la migration V51. Une nouvelle
  migration n'était pas nécessaire pour cette story, et en aurait ajouté une pour
  rien alors que le numéro suivant risquait de collisionner avec des worktrees
  multidevise non mergés au moment de l'implémentation.

### Events Spring publiés / écoutés

Aucun. Une recherche est une opération de lecture pure, sans effet de bord.

### Pièges et points d'attention

- **`ParseState.put(field, value, from, confidence)` écrase silencieusement toute
  valeur déjà posée sur le même champ**, sans aucune garde. `CityLexicon.assign` en
  tient explicitement compte : la règle heuristique « deux villes libres → départ
  puis arrivée » ne s'applique que si aucune préposition (« à »/« depuis ») n'a déjà
  fixé `departureCity` ou `arrivalCity` plus haut dans la même passe
  (`!departureAlreadySet && !arrivalAlreadySet`). Sans cette garde, une phrase comme
  « à Bamako Paris Lyon » verrait `arrivalCity` réécrasé par l'heuristique
  « deux villes libres » et perdrait la ville explicitement introduite par « à ».
  **Toute nouvelle passe qui appelle `state.put` sur un champ potentiellement déjà
  renseigné par une passe antérieure doit reproduire ce genre de garde** — `put` ne
  le fera jamais pour vous.
- **`YearMonth.atDay(int)` lève une `DateTimeException` sur un jour absent du mois**
  (ex : `atDay(31)` sur février). `DateExpressionParser.precedingDay` valide donc le
  jour candidat avec `target.isValidDay(day)` avant de construire la date exacte ; en
  cas d'invalidité, l'appelant retombe sur le mois entier plutôt que de laisser
  l'exception remonter jusqu'au client. Une saisie comme « le 31 février » ne doit
  jamais faire planter l'endpoint — elle doit dégrader proprement vers « février »
  tout court.
- **Le corpus de référence (`SearchQueryParserCorpusTest`) doit contenir une entrée
  « Mars »** dans les villes connues de test (`KNOWN_CITIES` / fixture de test) pour
  que le test protégeant l'ordre des passes discrimine réellement quelque chose. Une
  ville de test qui ne matche jamais « mars » sous le seuil de similarité laisse
  passer un orchestrateur cassé (villes en premier) exactement comme un
  orchestrateur correct : le test verdit dans les deux cas. Ce piège a été découvert
  pendant la Task 7 — la première version du corpus utilisait des villes qui ne
  matchaient pas assez « mars » (similarité 0.364 < seuil 0.4) pour distinguer les
  deux ordres, rendant le test aveugle au risque central du lot. Le corpus corrigé
  ajoute une ville réelle nommée « Mars » (présente en base GeoNames) pour que le
  test échoue réellement si la passe villes est remontée avant la passe dates.
- **Le controller ne modifie ni ne mock `FirebaseAuth`** dans son test
  d'intégration : `SearchParseControllerIT` pose directement un
  `UsernamePasswordAuthenticationToken` via
  `SecurityMockMvcRequestPostProcessors.authentication(...)`, ce qui court-circuite
  `FirebaseTokenFilter` sans avoir à le mocker. C'est le même pattern qu'utilisent
  les autres `@SpringBootTest` du repo pour tester `@PreAuthorize` sans dépendre de
  Firebase.
- **`today` facultatif n'était initialement exercé par aucun test HTTP.** Les 5
  tests fournis par le plan passent tous `today = null` : ils valident que
  l'endpoint ne casse pas sans `today`, mais aucun ne vérifiait que la valeur
  fournie par le client est effectivement transmise telle quelle au parseur plutôt
  qu'ignorée au profit de `LocalDate.now()`. Un sixième test
  (`parse_withExplicitToday_forwardsItToTheParserInsteadOfServerDate`) a été ajouté
  pour couvrir ce chemin, révélé par une couverture JaCoCo du controller à 87 % (3
  instructions sur 24 manquées, toutes dans la branche `today() != null`).

## Critères d'acceptation couverts

- [x] `POST /search/parse` traduit une phrase libre en `ParsedFilters` exploitables
      tels quels par `GET /announcements` (mêmes noms de champs).
- [x] Le poids d'une même phrase s'interprète différemment selon que l'appelant est
      expéditeur (`TRIPS` → `minAvailableKg`) ou voyageur (`PACKAGES` → `maxWeight`).
- [x] Un mois nommé sans année résout toujours vers une occurrence future, jamais un
      mois révolu.
- [x] Les ambiguïtés (prix vague, date vague, ville inconnue/ambiguë) ne sont jamais
      devinées : elles remontent dans `unresolved` pour que le client pose la
      question.
- [x] `contentType` produit systématiquement une valeur persistable (le `label` du
      catalogue), jamais un code interne.
- [x] L'endpoint est authentifié comme le reste de l'API (401 sans token, RBAC
      `SENDER`/`TRAVELER`).
- [x] Aucune donnée n'est persistée : ni la phrase saisie, ni une entrée
      `audit_log`.
- [x] Les erreurs de validation (`text` vide ou > 200 caractères) renvoient 422 au
      format RFC 7807 via `GlobalExceptionHandler`, sans handler dédié à ce package.
- [x] Aucune migration Flyway nouvelle : `pg_trgm` et `cities` préexistaient (V51).

## Tests

- **Ma tâche seule (`SearchParseControllerIT`)** :
  `./mvnw test -Dtest=SearchParseControllerIT` → 6 tests verts, 0 échec.
  (5 tests du brief + 1 test ajouté sur la transmission de `today`.)
- **Package `com.yadony.api.search` complet** :
  `./mvnw test -Dtest='com.yadony.api.search.**' -DfailIfNoTests=false` →
  **91 tests verts, 0 échec** (85 préexistants avant cette tâche + 6 nouveaux).
- **Couverture JaCoCo** (`./mvnw test jacoco:report`, filtrée sur le package,
  jamais sur le module entier) :
  - `com.yadony.api.search` : **99 %** en instructions (12 manquées sur 2 122) —
    résiduel hérité des tâches précédentes (`FlagParser` 95 %, `ContentTypeParser`,
    `CityLexicon`, `DateExpressionParser` ~99 % chacun), hors périmètre de cette
    tâche.
  - `com.yadony.api.search.dto` : **100 %**.
  - `SearchParseController` (mon code) : **100 %** en instructions (0 manquée sur
    24) après ajout du test sur `today`.
  - Seuil du lot (≥ 90 %) largement tenu.
- **Suite complète du module** : non lancée par cette tâche, conformément à la
  contrainte du lot (deux `./mvnw` concurrents corrompent `target/classes`) — le
  contrôleur du lot la lance lui-même une fois tous les agents au repos.

## Décisions techniques

- **Aucune passe n'est un bean Spring, sauf `CityLexicon`.** Les 4 premières passes
  (`QuantityParser`, `DateExpressionParser`, `FlagParser`, `ContentTypeParser`) sont
  des classes `final` à méthodes statiques : elles n'ont besoin d'aucune dépendance
  externe et restent testables sans contexte Spring. Seule `CityLexicon` est un
  `@Component` car elle a besoin de `SearchCityRepository`, qui parle à la base.
  Alternative écartée : tout faire porter par un unique service Spring — aurait
  forcé `@SpringBootTest` sur chaque passe testée isolément, ralentissant
  inutilement la suite du package (les 85 tests hors `SearchParseControllerIT`
  tournent en quelques dizaines de millisecondes au total).
- **`SearchCityRepository` interroge `cities` par SQL natif plutôt que par
  `CityService`.** La règle du repo interdit l'injection de service entre packages
  (`search` ne doit pas dépendre de `city`'s service layer). Alternative écartée :
  publier un événement Spring pour demander la résolution — surdimensionné pour une
  lecture pure, synchrone, sans effet de bord.
  Il s'agit du même contournement que celui d'`AnnouncementSpecification`, déjà en
  usage dans le repo.
  `SearchCityRepository extends Repository<CityEntity, Long>` (et non
  `JpaRepository`) pour n'exposer que la seule requête native `findSimilar`,
  volontairement minimal.
- **Le poids conventionnel des contenants (valise = 23 kg, carton = 15 kg,
  sac = 10 kg) est codé en dur dans `QuantityParser`.** Alternative écartée : le
  dériver d'une table de configuration — ces valeurs ne varient jamais dans le
  produit, et une table dédiée aurait ajouté de la complexité sans bénéfice pour
  trois constantes.
- **Le corps de la réponse ne renvoie jamais d'exception brute pour une ambiguïté.**
  Un choix produit assumé (`Global Constraints` du plan) : le parseur ne devine
  jamais une valeur non fiable, il la remonte dans `unresolved` avec des `options`
  suggérées quand c'est pertinent (ex. `PRICE_VAGUE` propose `["6", "9",
  "unlimited"]`), à charge pour le client Flutter de transformer ça en question à
  l'utilisateur plutôt que d'appliquer un filtre silencieusement faux.
- **`today` accepté en entrée plutôt que dérivé uniquement du serveur.** Nécessaire
  pour que les tests (et un futur mode démo côté client) restent déterministes sans
  dépendre de l'horloge système ; en production, le champ est presque toujours
  omis et le serveur utilise sa propre date, évitant qu'un client malveillant ou
  buggé ne fausse la résolution des mois avec une date arbitraire côté filtre
  (le champ influence seulement l'interprétation de la phrase, jamais une
  autorisation ou un montant).
