# Recherche publique d'annonces, sans connexion (Backend)

**Date:** 2026-11-02
**Status:** ✅ Complète (reste le déploiement, voir « À faire hors du code »)

## Résumé
Un visiteur anonyme (le site yadony.com) peut chercher des annonces de voyageurs avec `GET /api/v1/public/announcements`, sans jeton. L'endpoint est en lecture seule, borné (liste fermée de paramètres, plage de dates, pages), mis en cache 60 s côté back, limité à 30 req/min par IP côté Nginx, et ne sert qu'une liste blanche de champs (aucun téléphone, identifiant, photo, adresse, description libre, prix net du voyageur).

## À faire hors du code (rappels de déploiement)
- **CORS** : ajouter `https://yadony.com,https://www.yadony.com` à la variable `CORS_ALLOWED_ORIGINS` (propriété `app.cors.allowed-origins`) en staging, puis en production. La valeur par défaut ne contient que `localhost:3000/3001`. Sans cela, le navigateur du site refuse la réponse.
- **Nginx** : la nouvelle zone `public_search` et la `location = /api/v1/public/announcements` sont dans `nginx/nginx.conf` ; elles ne prennent effet qu'après déploiement de ce fichier et rechargement de Nginx.
- **Aucune migration Flyway** dans cette story.
- **Décision du propriétaire du 2026-11-02 (option A)** : tout voyageur qui a une annonce ouverte apparaît dans la recherche publique. Il n'existe aucun réglage de retrait (pas d'opt-out). Le retrait se fait sur demande, et cela doit être annoncé dans la politique de confidentialité.
- La PR du site (yadony-web) ne part qu'après la fusion de cette PR et son déploiement en staging.

## Fichiers créés
- `src/main/java/com/yadony/api/matching/PublicAnnouncementSearchController.java` : `GET /public/announcements`, liste fermée de 11 paramètres, en-tête `Cache-Control`.
- `src/main/java/com/yadony/api/matching/PublicAnnouncementSearchService.java` : recherche en lecteur anonyme et cache `public-announcements-search`.
- `src/main/java/com/yadony/api/matching/PublicAnnouncementQuery.java` : critères normalisés, validés et bornés. Sert aussi de clé de cache.
- `src/main/java/com/yadony/api/matching/dto/PublicAnnouncementCard.java` : DTO public de l'annonce, liste blanche de 11 champs.
- `src/main/java/com/yadony/api/matching/dto/PublicTravelerCard.java` : DTO public du voyageur (nom affiché, note moyenne, nombre de trajets, identité vérifiée).
- Tests : `PublicAnnouncementCardTest`, `PublicAnnouncementQueryTest`, `PublicAnnouncementSearchServiceTest`, `PublicAnnouncementSearchCacheIT`, `PublicAnnouncementSearchControllerIT`, `PublicSearchCorsIT` (voir « Tests »).

## Fichiers modifiés
- `src/main/java/com/yadony/api/config/CacheConfig.java` : cache `public-announcements-search` (2000 entrées au plus, TTL 60 s, statistiques), enregistré par `registerCustomCache` et non `setCacheNames` (voir l'avertissement de platform-settings dans le fichier).
- `nginx/nginx.conf` : zone `public_search` (30 r/min par IP, 10 Mo) et `location = /api/v1/public/announcements` (burst 10, `nodelay`, statut 429) dans le bloc `api.yadony.com`.
- `SecurityConfig` n'est **pas** modifié : `/public/**` est déjà en `permitAll`, et la configuration CORS lit déjà `app.cors.allowed-origins`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Le navigateur de yadony.com appelle `GET https://api.yadony.com/api/v1/public/announcements?departureCity=paris&arrivalCity=dakar...`, sans en-tête personnalisé.
2. Nginx applique `public_search` (30 req/min par IP, burst 10) ; au-delà, il répond 429 sans atteindre le back.
3. `PublicAnnouncementSearchController` refuse (400 `invalid-search`) tout paramètre hors de la liste fermée, puis construit `PublicAnnouncementQuery.of(..., LocalDate.now(UTC))`, qui normalise et valide.
4. `PublicAnnouncementSearchService.search(query)` : si le record `PublicAnnouncementQuery` est déjà en cache (60 s), il est rendu tel quel. Sinon, il appelle `AnnouncementService.searchAnnouncements` en lecteur anonyme : aucun viewer (donc aucun masquage de comptes bloqués ni de favoris), ni position GPS, ni rayon, ni filtre GP Pro, week-end ou KYC, `maxAvailableKg` nul, tri par date croissante, pages de 20.
5. Chaque `AnnouncementSearchResponse` est converti en `PublicAnnouncementCard.from(...)` (construction champ par champ), puis enveloppé dans `PageResponse`.
6. Le contrôleur répond 200 avec `Cache-Control: public, max-age=30`.

### Points d'entrée API
- `GET /api/v1/public/announcements` : public, sans jeton, aucun rôle. Paramètres admis (liste fermée) : `departureCity`, `arrivalCity`, `departureDateFrom`, `departureDateTo` (dates ISO `yyyy-MM-dd`), `minAvailableKg`, `maxPricePerKg`, `minRating`, `urgent`, `transportMode`, `contentType`, `page`.
- Réponse : `PageResponse<PublicAnnouncementCard>`. Champs de la carte : `id`, `departureCity`, `arrivalCity`, `departureDate` (date seule), `availableKg`, `pricePerKgDisplay`, `currency`, `transportMode`, `urgent`, `acceptedContentTypes`, `traveler` (`displayName`, `averageRating`, `totalTrips`, `identityVerified`). Les valeurs nulles sont omises (`NON_NULL`).
- Erreurs : 400 `invalid-search` (paramètre inconnu ou borne dépassée), 400 `bad-parameter` (valeur mal formée : `page=abc`, date invalide...), 422 `exchange-rate-missing` (voir pièges), toutes en RFC 7807 `application/problem+json`. Les 400 ne portent pas `Cache-Control: public`.

### Entités JPA impliquées
Aucune entité créée ni modifiée. La lecture passe par `AnnouncementService.searchAnnouncements` et ses spécifications existantes (annonces publiques et actives uniquement : les annonces annulées, complètes, brouillons, retirées par l'admin, de trajet privé ou dont l'échéance de remise est dépassée sont absentes, ce que l'IT vérifie).

### Logique métier critique
- **Liste blanche, pas liste noire** : `PublicAnnouncementCard.from` et `PublicTravelerCard.from` construisent chaque champ explicitement. Un champ ajouté plus tard à `AnnouncementSearchResponse` ne peut donc pas fuiter. Jamais servis : téléphone, e-mail, identifiant du voyageur, photo, adresses de remise et GPS, description libre, `pricePerKg` et `convertedPricePerKg` (prix net du voyageur), taux de commission.
- **Prix** : `pricePerKgDisplay` (ce que paie l'expéditeur) dans `currency`. Un prix nul ou négatif est traité comme absent (voir pièges h).
- **Liste fermée de paramètres** : un paramètre connu de la recherche authentifiée (`userLat`, `sortBy`, `gpProOnly`, `kycVerifiedOnly`...) est refusé en 400 au lieu d'être ignoré. Le détail de l'erreur ne recopie pas les noms reçus. Cela évite aussi qu'un paramètre parasite contourne le cache.
- **Normalisation (`PublicAnnouncementQuery`)** : villes `strip`, espaces multiples réduits, minuscules (`Locale.ROOT`), vide devient null, 80 caractères au plus ; `transportMode` `strip` + majuscules, vide devient null, valeur inconnue non rejetée ; `contentType` `strip`, vide devient null. Ainsi `Paris` et ` paris ` donnent le même record, donc la même entrée de cache.
- **Bornes** : `dateFrom` absent vaut aujourd'hui (UTC) ; `dateTo` absent vaut `dateFrom + 90 jours` ; `dateFrom > dateTo` ou plage de plus de 90 jours donne 400 ; `minAvailableKg` et `maxPricePerKg` négatifs donnent 400 ; `minRating` hors 0..5 donne 400 ; `page` absent vaut 0, hors 0..4 donne 400 (donc 100 résultats visibles au plus par critère).
- **Cache** : 60 s côté serveur (`public-announcements-search`, aucune éviction manuelle, un retard d'une minute est invisible pour un visiteur), plus `max-age=30` côté navigateur et CDN.

### Events Spring publiés / écoutés
Aucun.

### Pièges et points d'attention
a. **Fraîcheur réelle jusqu'à 5 min** : le cache interne existant `announcements-search` (5 min, dans `AnnouncementService.searchAnnouncements`) reste sous le cache public de 60 s. Une annonce créée ou retirée peut donc mettre jusqu'à 5 minutes à apparaître ou disparaître.
b. **Taux de change manquant = toute la recherche en 422** : si une devise du catalogue n'a aucun taux en base, `searchAnnouncements` lève 422 `exchange-rate-missing` et toute la réponse publique échoue tant qu'une annonce de cette devise tombe dans la page. Comportement hérité de la recherche authentifiée. Les échecs ne sont pas mis en cache : pendant une panne, chaque requête anonyme relance la requête (la limite Nginx est alors la seule protection). Les taux sont gérés par l'admin.
c. **Le 429 de Nginx n'a pas d'en-têtes CORS** : le navigateur le remonte comme une erreur réseau, pas comme un 429 lisible. Le site doit traiter toute erreur réseau comme « réessayez plus tard ».
d. **Une pré-requête CORS (OPTIONS) consomme le budget de débit** quand le client envoie des en-têtes personnalisés. Le `fetch` du site ne doit en envoyer aucun : un GET « simple » n'a pas de pré-requête.
e. **`description` volontairement absente** : ce texte libre peut contenir un numéro de téléphone. Ne pas l'ajouter à `PublicAnnouncementCard`. Les tests vérifient qu'un numéro mis dans la description ne ressort jamais.
f. **Villes en minuscules, accents conservés** : `AnnouncementSpecification.hasDepartureCity` compare `lower(city) = lower(input)`. Retirer les accents à la normalisation empêcherait de retrouver une ville stockée avec ses accents.
g. **`dateTo` vaut `dateFrom + 90 jours` par défaut** : sans cette borne, une requête sans date serait non bornée. Un `dateTo` absent et un `dateTo` explicite égal à `dateFrom + 90` donnent des records égaux (même clé de cache).
h. **Prix 0 traité comme absent** : un trajet en mode de prix MIXED avec grille seule est stocké avec `price_per_kg = 0` (contrainte V94, colonne `NOT NULL`). Servir 0 afficherait « 0 €/kg » ; la carte omet `pricePerKgDisplay` dans ce cas.
i. **`Cache-Control` écrit en clair** : `CacheControl.maxAge(...).cachePublic()` sérialise `max-age=30, public` ; la valeur attendue `public, max-age=30` est posée littéralement dans le contrôleur. Spring Security ne remplace pas un `Cache-Control` déjà présent.
j. **Deux surcharges de `searchAnnouncements`** (21 et 22 paramètres) : le service appelle celle à 21 ; elle délègue à la 22 avec `AnnouncementSearchExtras.NONE`.
k. **Fuseau** : « aujourd'hui » est calculé en UTC ; `departureDate` est une date sans heure ni fuseau.
l. **Nginx** : seul le bloc `api.yadony.com` a reçu la `location` ; les blocs `yadony.com` et `pro.yadony.com` ne l'ont pas, le navigateur du site appelle `api.yadony.com`. Syntaxe vérifiée par `nginx -t` (image `nginx:1.27-alpine`, certificats factices).

## Critères d'acceptation couverts
- [x] Un visiteur sans jeton obtient 200 et la liste des annonces ouvertes : route sous `/public/**` (`permitAll`), IT `repondEn200SansJeton`.
- [x] Seules les annonces publiques et actives sont servies : IT `lesAnnoncesNonPubliquesOuInactivesSontAbsentes`.
- [x] Aucun champ sensible n'est exposé (téléphone, identifiants, photo, adresses, description, prix net) : DTO en liste blanche, `PublicAnnouncementCardTest` et IT `neContientAucunChampInterdit`.
- [x] Paramètres inconnus refusés : IT `refuseUnParametreInconnu` ; valeurs mal formées en 400 : `refuseUneValeurMalFormee`.
- [x] Pagination bornée (page 4 au plus) et plage de dates bornée à 90 jours : `PublicAnnouncementQueryTest`, IT `refuseLaPage5`.
- [x] Charge absorbée : cache de 60 s sur critères normalisés (`PublicAnnouncementSearchCacheIT`), `Cache-Control: public, max-age=30` (IT `ajouteCacheControl`), 30 req/min par IP côté Nginx.
- [x] Le site yadony.com peut appeler l'API depuis le navigateur : `PublicSearchCorsIT` (pré-requêtes acceptées pour `https://yadony.com` et `https://www.yadony.com`, refusées pour une autre origine).
- [x] La recherche authentifiée reste inchangée : `AnnouncementControllerIntegrationTest`, `GuestDataExposureIT`, `GuestAuthorizationIT` verts.

## Tests
- `./mvnw test` (suite complète, sur cette branche) → `Tests run: 14467, Failures: 0, Errors: 0, Skipped: 7`, `BUILD SUCCESS` (1 h 14). 0 rouge. Les 7 tests ignorés sont préexistants (par exemple `BidEntityMigrationTest`, `SurplusMigrationIT`), aucun n'appartient à cette story.
- `./mvnw jacoco:report` lancé sur le `target/jacoco.exec` produit par ce même run (pas de second passage de la suite) → rapport `target/site/jacoco/jacoco.csv` :
  - couverture globale : **96,5 % des lignes** (58 132 couvertes, 2 120 manquées), 95,8 % des instructions, 86,4 % des branches ;
  - classes de la story : `PublicAnnouncementSearchController` 14/14 lignes, `PublicAnnouncementQuery` 39/39 (36/36 branches), `PublicAnnouncementSearchService` 12/12, `PublicAnnouncementCard` 14/14, `PublicTravelerCard` 5/5 : 100 % des lignes partout.
- Tests ajoutés :
  - `matching/dto/PublicAnnouncementCardTest` (7) : jeu exact de clés du JSON (carte et voyageur), aucun champ interdit (téléphone dans la description, avatar, identifiant du voyageur, adresses, prix net, prix converti), prix affiché et non net, prix absent ou nul omis, voyageur absent toléré.
  - `matching/PublicAnnouncementQueryTest` (15) : normalisation des villes, bornes de dates (défaut `dateFrom + 90`, 90 jours acceptés, 91 refusés), valeurs négatives, `minRating`, `page` (4 accepté, 5 refusé), égalité des clés de cache.
  - `matching/PublicAnnouncementSearchServiceTest` (2) : appel en lecteur anonyme avec les bons paramètres, conversion en cartes.
  - `matching/PublicAnnouncementSearchCacheIT` (2) : saisies équivalentes = un seul appel à `searchAnnouncements`, critères différents = deux appels.
  - `matching/PublicAnnouncementSearchControllerIT` (10) : 200 sans jeton, annonces non publiques ou inactives absentes, aucun champ interdit de bout en bout, paramètre inconnu refusé, page 5 refusée, valeur mal formée, prix mixte sans prix au kilo, annonce en XOF, `Cache-Control`, recherche des invités fermée aux anonymes (401).
  - `config/PublicSearchCorsIT` (3) : pré-requêtes CORS acceptées pour `https://yadony.com` et `https://www.yadony.com`, refusée pour une autre origine.
- Non couvert par un test automatique : la limite Nginx. Syntaxe vérifiée par `nginx -t` seulement ; le 429 réel est à constater en staging après déploiement de `nginx.conf`.

## Décisions techniques
- **Réutiliser `searchAnnouncements` plutôt que dupliquer la requête** : mêmes filtres et mêmes règles de visibilité que l'app, un seul endroit à corriger. Écarté : une requête dédiée (risque de divergence des règles de visibilité). Contrepartie : les pièges a et b sont hérités.
- **DTO dédié en liste blanche plutôt que réutiliser `AnnouncementSearchResponse`** : une liste noire laisserait fuiter tout futur champ. Écarté : un `@JsonIgnore` sur la réponse existante.
- **Record de critères normalisés comme clé de cache** : égalité structurelle, pas de concaténation de chaîne à maintenir.
- **Cache Caffeine de 60 s sans éviction** : pas de Redis (règle du projet) ; la fraîcheur d'une minute suffit à un visiteur. Écarté : une éviction sur création d'annonce (couplerait ce cache aux écritures d'annonces, alors que le cache interne de 5 min limite déjà la fraîcheur).
- **Rejeter les paramètres inconnus plutôt que les ignorer** : empêche l'accès aux filtres réservés aux comptes et le contournement du cache. Écarté : ignorer en silence.
- **Limite Nginx de 30 req/min par IP, burst 10** : la page d'accueil appelle l'endpoint à chaque chargement et le CGNAT regroupe beaucoup d'abonnés ; trop peu pour aspirer le catalogue. Valeurs de départ, à ajuster d'après les journaux.
- **Option A (aucun opt-out)** : décision du propriétaire du 2026-11-02, retrait sur demande, mention dans la politique de confidentialité. Écarté pour l'instant : un réglage de visibilité par voyageur (aurait demandé une migration et un écran dans l'app).
- **Pas de modification de `SecurityConfig`** : la règle `/public/**` et la configuration CORS existantes suffisent ; seule la variable d'environnement change.
